"""Exercise/machine catalog: models, read endpoints, and JSON importer.

Import schema: see CATALOG_SCHEMA.md.
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING, Annotated, Any, Literal
from urllib.parse import urlparse

import click
from flask import jsonify, request
from pydantic import Field, StringConstraints, ValidationError, field_validator
from sqlalchemy import Column, ForeignKey, String, Table, func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Mapped, joinedload, mapped_column, relationship, selectinload

from app import ApiPayload, db, paged

if TYPE_CHECKING:
    from flask import Flask, Response

MachineType = Literal["machine", "cable", "free-weight", "bodyweight"]
Difficulty = Literal["beginner", "intermediate", "advanced"]
# Importer string limits mirror the String(N) columns.
Max64 = Annotated[str, StringConstraints(max_length=64)]
Max128 = Annotated[str, StringConstraints(max_length=128)]
Max256 = Annotated[str, StringConstraints(max_length=256)]
Max512 = Annotated[str, StringConstraints(max_length=512)]


def _link(name: str, other: str) -> Table:
    return Table(
        name,
        db.metadata,
        Column("owner_id", ForeignKey(f"{name.split('_')[0]}.id"), primary_key=True),
        Column("other_id", ForeignKey(f"{other}.id"), primary_key=True),
    )


machine_sources = _link("machines_sources", "sources")
exercises_sources = _link("exercises_sources", "sources")
exercises_primary = _link("exercises_primary", "muscle_groups")
exercises_secondary = _link("exercises_secondary", "muscle_groups")


class MuscleGroup(db.Model):
    __tablename__ = "muscle_groups"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)


class Source(db.Model):
    __tablename__ = "sources"

    id: Mapped[int] = mapped_column(primary_key=True)
    title: Mapped[str] = mapped_column(String(256), unique=True, nullable=False)
    url: Mapped[str | None] = mapped_column(String(512))
    publisher: Mapped[str | None] = mapped_column(String(128))
    year: Mapped[int | None]
    evidence_level: Mapped[str | None] = mapped_column(String(64))


class Machine(db.Model):
    __tablename__ = "machines"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    description: Mapped[str | None]
    verified: Mapped[bool] = mapped_column(default=False)
    sources: Mapped[list[Source]] = relationship(secondary=machine_sources)


class Exercise(db.Model):
    __tablename__ = "exercises"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    slug: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    machine_id: Mapped[int | None] = mapped_column(ForeignKey("machines.id"))
    movement_pattern: Mapped[str | None] = mapped_column(String(64))
    difficulty: Mapped[str] = mapped_column(String(16), nullable=False)
    instructions: Mapped[str | None]
    met: Mapped[float | None]
    has_pose_tracking: Mapped[bool] = mapped_column(default=False)
    verified: Mapped[bool] = mapped_column(default=False)
    machine: Mapped[Machine | None] = relationship()
    primary_muscles: Mapped[list[MuscleGroup]] = relationship(secondary=exercises_primary)
    secondary_muscles: Mapped[list[MuscleGroup]] = relationship(secondary=exercises_secondary)
    sources: Mapped[list[Source]] = relationship(secondary=exercises_sources)


CATALOG_ENTITIES = ("foods", "exercises", "machines", "muscles", "plans")
# importer section -> exported entities it changes (sources are embedded in all but muscles)
SECTION_ENTITIES = {"sources": ("foods", "exercises", "machines", "plans")}


class CatalogMeta(db.Model):
    """One row per exported catalog entity; version bumps whenever an import touched it."""

    __tablename__ = "catalog_meta"

    entity: Mapped[str] = mapped_column(String(16), primary_key=True)
    version: Mapped[int] = mapped_column(default=0, nullable=False)


def bump_catalog(entities: set[str]) -> None:
    """The one place catalog versions move (called by import_rows, in its transaction)."""
    for entity in sorted(entities):
        meta = db.session.get(CatalogMeta, entity)
        if meta is None:
            db.session.add(CatalogMeta(entity=entity, version=1))
        else:
            meta.version += 1


def source_json(s: Source) -> dict[str, Any]:
    return {
        "title": s.title,
        "url": s.url,
        "publisher": s.publisher,
        "year": s.year,
        "evidence_level": s.evidence_level,
    }


def machine_json(m: Machine) -> dict[str, Any]:
    return {
        "id": m.id,
        "name": m.name,
        "type": m.type,
        "description": m.description,
        "verified": m.verified,
        "sources": [source_json(s) for s in m.sources],
    }


def exercise_json(e: Exercise) -> dict[str, Any]:
    return {
        "name": e.name,
        "slug": e.slug,
        "primary_muscles": [m.name for m in e.primary_muscles],
        "secondary_muscles": [m.name for m in e.secondary_muscles],
        "machine": e.machine.name if e.machine else None,
        "movement_pattern": e.movement_pattern,
        "difficulty": e.difficulty,
        "instructions": e.instructions,
        "met": e.met,
        "has_pose_tracking": e.has_pose_tracking,
        "verified": e.verified,
        "sources": [source_json(s) for s in e.sources],
    }


def exercise_list_json(e: Exercise) -> dict[str, Any]:
    """Slim list item: no instructions, secondary muscles or sources (see the detail endpoint)."""
    return {
        "name": e.name,
        "slug": e.slug,
        "primary_muscles": [m.name for m in e.primary_muscles],
        "machine": e.machine.name if e.machine else None,
        "movement_pattern": e.movement_pattern,
        "difficulty": e.difficulty,
        "has_pose_tracking": e.has_pose_tracking,
        "verified": e.verified,
    }


class SourceRow(ApiPayload):
    title: Max256 = Field(min_length=1)
    url: Max512 | None = None
    publisher: Max128 | None = None
    year: int | None = Field(default=None, ge=1, le=2100)
    evidence_level: Max64 | None = None

    @field_validator("url")
    @classmethod
    def http_url_only(cls, url: str | None) -> str | None:
        if url is not None and (
            urlparse(url).scheme not in ("http", "https") or not urlparse(url).netloc
        ):
            msg = "url must be an http(s) URL"
            raise ValueError(msg)
        return url


def _dedupe(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


class MachineRow(ApiPayload):
    name: Max128 = Field(min_length=1)
    type: MachineType
    description: str | None = None
    verified: bool = False
    sources: list[Max256] = []

    _dedupe_sources = field_validator("sources")(_dedupe)


class ExerciseRow(ApiPayload):
    name: Max128 = Field(min_length=1)
    slug: Max128 = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    primary_muscles: list[Max64] = Field(min_length=1)
    secondary_muscles: list[Max64] = []
    machine: Max128 | None = None
    movement_pattern: Max64 | None = None
    difficulty: Difficulty
    instructions: str | None = None
    met: float | None = Field(default=None, gt=0, le=50, allow_inf_nan=False)
    has_pose_tracking: bool = False
    verified: bool = False
    sources: list[Max256] = []

    _dedupe_lists = field_validator("primary_muscles", "secondary_muscles", "sources")(_dedupe)


def _upsert(model: Any, key: str, value: Any, **fields: Any) -> tuple[Any, bool]:  # noqa: ANN401
    row = db.session.scalar(select(model).where(getattr(model, key) == value))
    created = row is None
    if row is None:
        row = model(**{key: value})
        db.session.add(row)
    for name, val in fields.items():
        setattr(row, name, val)
    return row, created


def _by_title(titles: list[str]) -> list[Source]:
    found = {s.title: s for s in db.session.scalars(select(Source).where(Source.title.in_(titles)))}
    missing = [t for t in titles if t not in found]
    if missing:
        msg = f"unknown sources: {missing}"
        raise LookupError(msg)
    return [found[t] for t in titles]


def section_rows(data: dict[str, Any], section: str, report: dict[str, Any]) -> list[Any]:
    """data[section] as a list; anything else is reported (never iterated: "chest" != 5 rows)."""
    rows = data.get(section) or []
    if isinstance(rows, list):
        return rows
    report["errors"].append({"section": section, "index": None, "error": "must be a list"})
    return []


def add_import_command(app: Flask, name: str, importer: Any) -> None:  # noqa: ANN401
    """Register `flask <name> PATH`: unreadable/bad JSON -> ClickException, else print the report."""

    @app.cli.command(name)
    @click.argument("path", type=click.Path(exists=True, dir_okay=False))
    def command(path: str) -> None:
        try:
            with open(path, encoding="utf-8") as handle:  # noqa: PTH123
                document = json.load(handle)
        except (OSError, ValueError) as error:  # ValueError covers JSON and unicode errors
            msg = f"cannot read {path}: {error}"
            raise click.ClickException(msg) from error
        report = importer(document)
        click.echo(json.dumps(report, indent=2))
        if not isinstance(document, dict):
            raise click.ClickException(report["errors"][0]["error"])


def apply_source(row: SourceRow) -> bool:
    return _upsert(Source, "title", row.title, **row.model_dump(exclude={"title"}))[1]


def import_rows(
    data: Any,  # noqa: ANN401 - untrusted JSON
    handlers: tuple[tuple[str, type[ApiPayload], Any], ...],
    *,
    extra: tuple[str, ...] = (),
    pre: Any = None,  # noqa: ANN401 - pre(data, report): runs on a valid document, before the rows
    after: Any = None,  # noqa: ANN401 - after(report, section, index, row): per row that loaded
) -> dict[str, Any]:
    """Shared importer loop: each row validated and applied in a savepoint.

    handlers: (section, row model, apply(row) -> created). Bad rows/sections are reported, never
    abort the file. extra: further counted sections without handlers (filled by pre/after).
    Returns {"created": {section: n}, "updated": {section: n}, "errors": [...]}.
    """
    sections = (*extra, *(h[0] for h in handlers))
    report: dict[str, Any] = {
        "created": dict.fromkeys(sections, 0),
        "updated": dict.fromkeys(sections, 0),
        "errors": [],
    }
    if not isinstance(data, dict):
        report["errors"].append(
            {"section": None, "index": None, "error": "document must be a JSON object"}
        )
        return report
    if pre:
        pre(data, report)
    for section, model, apply in handlers:
        for index, raw in enumerate(section_rows(data, section, report)):
            try:
                row = model.model_validate(raw)
                with db.session.begin_nested():
                    created = apply(row)
            except SQLAlchemyError as error:
                detail = f"database error: {error.__class__.__name__}"
            except ValidationError as error:
                detail = error.errors(include_context=False, include_input=False, include_url=False)
            except LookupError as error:
                detail = str(error)
            else:
                report["created" if created else "updated"][section] += 1
                if after:
                    after(report, section, index, row)
                continue
            report["errors"].append({"section": section, "index": index, "error": detail})
    bump_catalog({
        e for s in sections if report["created"][s] + report["updated"][s]
        for e in SECTION_ENTITIES.get(s, (s,))
    })  # fmt: skip
    db.session.commit()
    return report


def import_catalog(data: dict[str, Any]) -> dict[str, Any]:
    """Upsert a catalog document; bad rows are reported, never abort the file.

    Returns {"created": {section: n}, "updated": {section: n}, "errors": [...], "warnings": [...]}.
    """
    new_muscles: list[str] = []  # counted only once the row's savepoint has succeeded

    def muscle(name: str) -> MuscleGroup:
        row, created = _upsert(MuscleGroup, "name", name)
        if created:
            new_muscles.append(name)
        return row

    def load_muscles(doc: dict[str, Any], report: dict[str, Any]) -> None:
        for index, name in enumerate(section_rows(doc, "muscles", report)):
            if isinstance(name, str) and 0 < len(name) <= 64:  # noqa: PLR2004 - String(64) column
                muscle(name)
                report["created"]["muscles"] += len(new_muscles)
                new_muscles.clear()
            else:
                report["errors"].append(
                    {"section": "muscles", "index": index, "error": "must be a non-empty string"}
                )

    def row_loaded(report: dict[str, Any], section: str, index: int, row: Any) -> None:  # noqa: ANN401
        report["created"]["muscles"] += len(new_muscles)
        new_muscles.clear()
        if getattr(row, "verified", False) and not row.sources:
            report["warnings"].append(
                {"section": section, "index": index, "warning": "verified ignored: no sources"}
            )

    def apply_machine(row: MachineRow) -> bool:
        sources = _by_title(row.sources)
        return _upsert(
            Machine,
            "name",
            row.name,
            type=row.type,
            description=row.description,
            verified=row.verified and bool(sources),
            sources=sources,
        )[1]

    def apply_exercise(row: ExerciseRow) -> bool:
        new_muscles.clear()  # leftovers of a row that failed and rolled back
        sources = _by_title(row.sources)
        machine = None
        if row.machine:
            machine = db.session.scalar(select(Machine).where(Machine.name == row.machine))
            if machine is None:
                msg = f"unknown machine: {row.machine}"
                raise LookupError(msg)
        return _upsert(
            Exercise,
            "slug",
            row.slug,
            name=row.name,
            machine=machine,
            primary_muscles=[muscle(n) for n in row.primary_muscles],
            secondary_muscles=[muscle(n) for n in row.secondary_muscles],
            movement_pattern=row.movement_pattern,
            difficulty=row.difficulty,
            instructions=row.instructions,
            met=row.met,
            has_pose_tracking=row.has_pose_tracking,
            verified=row.verified and bool(sources),
            sources=sources,
        )[1]

    def start(doc: dict[str, Any], report: dict[str, Any]) -> None:
        report["warnings"] = []
        load_muscles(doc, report)

    report = import_rows(
        data,
        (
            ("sources", SourceRow, apply_source),
            ("machines", MachineRow, apply_machine),
            ("exercises", ExerciseRow, apply_exercise),
        ),
        extra=("muscles",),
        pre=start,
        after=row_loaded,
    )
    report.setdefault("warnings", [])  # also for a non-object document
    return report


def register_catalog(app: Flask) -> None:
    @app.get("/api/exercises")
    def list_exercises() -> Response:
        query = select(Exercise).order_by(Exercise.name, Exercise.id)
        if muscle := request.args.get("muscle"):
            match = func.lower(MuscleGroup.name) == muscle.lower()
            query = query.where(
                Exercise.primary_muscles.any(match) | Exercise.secondary_muscles.any(match)
            )
        if machine := request.args.get("machine"):
            query = query.where(
                Exercise.machine.has(func.lower(Machine.name) == machine.lower()),
            )
        if difficulty := request.args.get("difficulty"):
            query = query.where(Exercise.difficulty == difficulty)
        if q := request.args.get("q"):
            query = query.where(func.lower(Exercise.name).contains(q.lower(), autoescape=True))
        return paged(
            query, exercise_list_json, joinedload(Exercise.machine),
            selectinload(Exercise.primary_muscles),
        )  # fmt: skip

    @app.get("/api/exercises/<slug>")
    def get_exercise(slug: str) -> Response:
        row = db.session.scalar(select(Exercise).where(Exercise.slug == slug))
        if row is None:
            return jsonify({"error": "not found"}), 404
        return jsonify(exercise_json(row))

    @app.get("/api/machines")
    def list_machines() -> Response:
        rows = db.session.scalars(select(Machine).order_by(Machine.name))
        return jsonify([machine_json(m) for m in rows])

    @app.get("/api/machines/<int:machine_id>")
    def get_machine(machine_id: int) -> Response:
        row = db.session.get(Machine, machine_id)
        if row is None:
            return jsonify({"error": "not found"}), 404
        return jsonify(machine_json(row))

    @app.get("/api/muscles")
    def list_muscles() -> Response:
        return jsonify(list(db.session.scalars(select(MuscleGroup.name).order_by(MuscleGroup.name))))

    add_import_command(app, "import-catalog", import_catalog)
