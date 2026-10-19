"""Offline-first sync server half: push ops, pull changes, catalog export. See SYNC_API.md.

Every write (REST or push) lands in sync_changes through the mapper events registered by
app.track(), so the pull feed never misses an edit. Push only validates and applies ops.
"""

from __future__ import annotations

import gzip
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Annotated, Any, Literal, NamedTuple
from uuid import UUID

import click
from flask import Response, abort, jsonify, request
from flask_jwt_extended import jwt_required
from pydantic import AwareDatetime, Field, StringConstraints, ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import joinedload, selectinload
from werkzeug.exceptions import HTTPException, NotFound

from app import (
    SYNCED,
    ApiPayload,
    CompletedSetPayload,
    SyncChange,
    SyncOp,
    WorkoutSet,
    as_utc,
    build_set,
    check_set_slug,
    current_user_id,
    db,
    limiter,
    page_args,
    user_key,
)
from catalog import (
    CATALOG_ENTITIES,
    CatalogMeta,
    Exercise,
    Machine,
    MuscleGroup,
    _upsert,
    exercise_json,
    machine_json,
)
from nutrition import (
    Food,
    FoodLog,
    LogPayload,
    ProfilePayload,
    UserProfile,
    WeightLog,
    WeightPayload,
    _profile_json,
    _round,
    entry_fiber,
    entry_macros,
    fill_log,
    food_json,
)
from plans import (
    AssignPayload,
    CompletePayload,
    PlanCompletion,
    PlanDay,
    PlanTemplate,
    UserPlan,
    template_json,
)

if TYPE_CHECKING:
    from collections.abc import Callable

    from flask import Flask

MAX_OPS = 200
PUSH_MAX_BYTES = 1024 * 1024  # this route only; the global limit is 16 KB
PUSH_LIMIT = "30 per minute"
PULL_LIMIT = "120 per minute"
CATALOG_LIMIT = "60 per minute"
CLOCK_SKEW = timedelta(minutes=5)  # client_updated_at is clamped to server now + this
SET_MAX_AGE = timedelta(days=366)
MAX_CURSOR = 2**62
Short = Annotated[str, StringConstraints(min_length=1, max_length=64)]


class Refused(Exception):  # noqa: N818 - a status, not an error
    """An op the server will not apply; status is 'rejected' (never retry) or 'conflict'."""

    def __init__(self, status: str, error: str) -> None:
        super().__init__(error)
        self.status, self.error = status, error


class SyncSet(CompletedSetPayload):
    timestamp: AwareDatetime  # when the set was done (offline phones cannot use server time)


class PushOp(ApiPayload):
    op_id: Short
    entity: Literal[
        "workout_sets", "food_logs", "weight_logs", "profile", "user_plans", "plan_completions"
    ]
    client_id: Short
    action: Literal["upsert", "delete"]
    data: dict[str, Any] | None = None
    client_updated_at: AwareDatetime


class PushBody(ApiPayload):
    device_id: Short  # accepted for diagnostics; not stored
    ops: list[dict[str, Any]] = Field(max_length=MAX_OPS)


# ---- per-entity write (upsert) and read (pull) ----


def _scope(model: Any, uid: int) -> Any:  # noqa: ANN401
    """SELECT of the user's rows (plan completions belong to the user through their plan)."""
    if model is PlanCompletion:
        return (
            select(PlanCompletion)
            .join(UserPlan, UserPlan.id == PlanCompletion.user_plan_id)
            .where(UserPlan.user_id == uid)
        )
    return select(model).where(model.user_id == uid)


def _free_slot(model: Any, uid: int, column: str, value: Any, row: Any, ts: datetime) -> None:  # noqa: ANN401
    """One row per day (weigh-in, plan completion): a newer write replaces another client's row."""
    other = db.session.scalar(_scope(model, uid).where(getattr(model, column) == value))
    if other is not None and other is not row:
        if ts <= as_utc(other.updated_at):
            raise Refused("conflict", f"another entry for {value} on the server is newer")
        db.session.delete(other)
        db.session.flush()  # the delete must hit the DB before the unique slot is reused


def _need(value: Any, name: str) -> None:  # noqa: ANN401
    if value is None:
        raise Refused("rejected", f"{name} is required in sync pushes")


def _w_set(uid: int, row: Any, cid: str, p: SyncSet, ts: datetime) -> None:  # noqa: ANN401
    now = datetime.now(UTC)
    stamp = min(p.timestamp.astimezone(UTC), now)  # a fast phone clock must not post-date a set
    if stamp < now - SET_MAX_AGE:
        raise Refused("rejected", "timestamp is more than a year old")
    check_set_slug(p)
    workout = build_set(uid, p, stamp)
    workout.client_id, workout.updated_at = cid, ts
    db.session.add(workout)


def _w_log(uid: int, row: FoodLog | None, cid: str, p: LogPayload, ts: datetime) -> None:
    _need(p.date, "date")
    row = row or FoodLog(user_id=uid, client_id=cid)
    try:
        fill_log(row, p)
    except NotFound as error:
        raise Refused("rejected", "unknown food_slug") from error
    row.updated_at = ts
    db.session.add(row)


def _w_weight(uid: int, row: WeightLog | None, cid: str, p: WeightPayload, ts: datetime) -> None:
    _need(p.date, "date")
    _free_slot(WeightLog, uid, "date", p.date, row, ts)
    row = row or WeightLog(user_id=uid, client_id=cid)
    row.date, row.kg, row.updated_at = p.date, p.kg, ts
    db.session.add(row)


def _w_profile(uid: int, _row: Any, _cid: str, p: ProfilePayload, ts: datetime) -> None:  # noqa: ANN401
    profile, _ = _upsert(UserProfile, "user_id", uid, **p.model_dump())
    profile.updated_at = ts


def _w_plan(uid: int, row: UserPlan | None, _cid: str, p: AssignPayload, ts: datetime) -> None:
    _need(p.start_date, "start_date")
    plan = db.session.scalar(select(PlanTemplate).where(PlanTemplate.slug == p.slug))
    if plan is None:
        raise Refused("rejected", "unknown plan slug")
    row = row or UserPlan(user_id=uid)
    if row.id is not None and row.plan_id != plan.id:
        row.completions = []  # a different plan restarts the rotation, as POST /api/me/plan does
    row.plan, row.start_date, row.equipment, row.updated_at = plan, p.start_date, p.equipment, ts
    db.session.add(row)


def _w_completion(
    uid: int, row: PlanCompletion | None, cid: str, p: CompletePayload, ts: datetime
) -> None:
    _need(p.day_index, "day_index")
    _need(p.date, "date")
    up = db.session.scalar(select(UserPlan).where(UserPlan.user_id == uid))
    if up is None:
        raise Refused("rejected", "no plan assigned")
    if p.day_index >= len(up.plan.days):
        raise Refused("rejected", "day_index out of range")
    _free_slot(PlanCompletion, uid, "completed_on", p.date, row, ts)
    row = row or PlanCompletion(user_plan_id=up.id, client_id=cid)
    row.day_index, row.completed_on, row.updated_at = p.day_index, p.date, ts
    db.session.add(row)


def _r_log(row: FoodLog) -> dict[str, Any]:
    fiber = entry_fiber(row)
    return {
        "meal": row.meal, "date": row.date.isoformat(),
        "food_slug": row.food.slug if row.food else None, "serving_id": row.serving_id,
        "quantity_g": row.quantity_g, "name": row.name, "kcal": row.kcal,
        "protein_g": row.protein_g, "carbs_g": row.carbs_g, "fat_g": row.fat_g,
        # read-only (never push): macros frozen from the food's per-100 g at creation
        "computed": _round(entry_macros(row)) | {"fiber_g": None if fiber is None else round(fiber, 1)},
    }  # fmt: skip


class Entity(NamedTuple):
    model: Any
    payload: type[ApiPayload]
    write: Callable[..., None]
    read: Callable[[Any], dict[str, Any]]
    options: tuple[Any, ...] = ()
    fixed_id: str | None = None  # singleton per user: the only client_id allowed
    append_only: bool = False


ENTITIES: dict[str, Entity] = {
    "workout_sets": Entity(
        WorkoutSet, SyncSet, _w_set,
        lambda r: {k: v for k, v in r.to_json().items() if k != "id"}, append_only=True,
    ),
    "food_logs": Entity(FoodLog, LogPayload, _w_log, _r_log, (selectinload(FoodLog.food),)),
    "weight_logs": Entity(
        WeightLog, WeightPayload, _w_weight,
        lambda r: {"kg": r.kg, "date": r.date.isoformat()},
    ),
    "profile": Entity(UserProfile, ProfilePayload, _w_profile, _profile_json, fixed_id="profile"),
    "user_plans": Entity(
        UserPlan, AssignPayload, _w_plan,
        lambda r: {"slug": r.plan.slug, "start_date": r.start_date.isoformat(),
                   "equipment": r.equipment},
        (joinedload(UserPlan.plan),), fixed_id="plan",
    ),
    "plan_completions": Entity(
        PlanCompletion, CompletePayload, _w_completion,
        lambda r: {"day_index": r.day_index, "date": r.completed_on.isoformat()},
    ),
}  # fmt: skip
assert set(ENTITIES) == set(SYNCED), "every synced model needs a sync entity"  # noqa: S101


# ---- push ----


def _msg(error: Exception) -> str:
    if isinstance(error, ValidationError):
        found = error.errors(include_url=False, include_context=False, include_input=False)
        return "; ".join(f"{'.'.join(map(str, e['loc']))}: {e['msg']}" for e in found[:3])
    if isinstance(error, HTTPException):
        return str(error.description)
    return str(error)


def _apply(uid: int, op: PushOp, now: datetime) -> dict[str, Any]:
    """Apply one op; returns {'status': ...} or raises Refused/ValidationError/HTTPException."""
    ent = ENTITIES[op.entity]
    if ent.fixed_id:
        if op.client_id != ent.fixed_id:
            raise Refused("rejected", f"client_id must be '{ent.fixed_id}'")
        cid = op.client_id
    else:
        try:
            cid = str(UUID(op.client_id))
        except ValueError as error:
            raise Refused("rejected", "client_id must be a UUID") from error
    ts = min(op.client_updated_at.astimezone(UTC), now + CLOCK_SKEW)
    row = db.session.scalar(_scope(ent.model, uid).where(ent.model.client_id == cid))
    if op.action == "delete":
        if ent.fixed_id:
            raise Refused("rejected", f"{op.entity} cannot be deleted")
        if row is None:
            return {"status": "duplicate"}  # already gone (or never existed)
        if not ent.append_only and ts < as_utc(row.updated_at):
            raise Refused("conflict", "the server version is newer than this delete")
        db.session.delete(row)
        return {"status": "applied"}
    if row is not None and ent.append_only:
        return {"status": "duplicate"}
    if row is not None and ts <= as_utc(row.updated_at):
        raise Refused("conflict", "the server version is newer or equal")
    if row is None:  # ponytail: tombstone time = when the server saw the delete, not the client's
        last = db.session.scalar(
            select(SyncChange)
            .where(SyncChange.user_id == uid, SyncChange.entity == op.entity,
                   SyncChange.client_id == cid)
            .order_by(SyncChange.id.desc()).limit(1)
        )  # fmt: skip
        if last is not None and last.op == "delete" and ts <= as_utc(last.created_at):
            raise Refused("conflict", "deleted on the server after this edit")
    ent.write(uid, row, cid, ent.payload.model_validate(op.data or {}), ts)
    return {"status": "applied"}


def _remember(uid: int, op_id: str, result: dict[str, Any], now: datetime) -> None:
    try:
        with db.session.begin_nested():
            db.session.add(SyncOp(user_id=uid, op_id=op_id, result=result, created_at=now))
    except IntegrityError:
        pass  # a concurrent replay stored it first


def _push_one(uid: int, raw: dict[str, Any], known: dict[str, dict[str, Any]], now: datetime) -> dict[str, Any]:
    try:
        op = PushOp.model_validate(raw)
    except ValidationError as error:
        op_id = raw.get("op_id")
        return {"op_id": op_id if isinstance(op_id, str) else None, "status": "rejected",
                "error": _msg(error)}  # fmt: skip
    if op.op_id in known:
        return {"op_id": op.op_id, **known[op.op_id]}
    try:
        with db.session.begin_nested():  # the op and its stored result commit or roll back together
            result = _apply(uid, op, now)
            db.session.add(SyncOp(user_id=uid, op_id=op.op_id, result=result, created_at=now))
    except (Refused, ValidationError, HTTPException) as error:
        status = error.status if isinstance(error, Refused) else "rejected"
        result = {"status": status, "error": _msg(error)}
        _remember(uid, op.op_id, result, now)
    except IntegrityError:
        stored = db.session.get(SyncOp, (uid, op.op_id))
        result = stored.result if stored else {"status": "conflict", "error": "concurrent write, retry"}
    except SQLAlchemyError:  # not remembered: an identical retry may succeed
        result = {"status": "rejected", "error": "database error"}
    known[op.op_id] = result
    return {"op_id": op.op_id, **result}


# ---- pull ----


def _int_arg(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(request.args.get(name, default))
    except ValueError:
        value = low - 1
    if not low <= value <= high:
        abort(400, f"{name} must be an integer between {low} and {high}")
    return value


def _iso(stamp: datetime) -> str:
    return as_utc(stamp).astimezone(UTC).isoformat().replace("+00:00", "Z")


def _current_rows(uid: int, changes: list[SyncChange]) -> dict[tuple[str, str], Any]:
    """Current state of every row the page refers to: one query per entity, not per change."""
    wanted: dict[str, set[str]] = {}
    for c in changes:
        if c.op == "upsert":
            wanted.setdefault(c.entity, set()).add(c.client_id)
    found: dict[tuple[str, str], Any] = {}
    for entity, ids in wanted.items():
        ent = ENTITIES[entity]
        query = _scope(ent.model, uid).where(ent.model.client_id.in_(ids)).options(*ent.options)
        found |= {(entity, r.client_id): r for r in db.session.scalars(query)}
    return found


# ---- catalog export ----

CATALOG_EXPORT: dict[str, tuple[Any, Any, Callable[[Any], dict[str, Any]], tuple[Any, ...]]] = {
    "foods": (Food, Food.slug, food_json, (selectinload(Food.servings), selectinload(Food.sources))),
    "exercises": (
        Exercise, Exercise.slug, exercise_json,
        (joinedload(Exercise.machine), selectinload(Exercise.primary_muscles),
         selectinload(Exercise.secondary_muscles), selectinload(Exercise.sources)),
    ),
    "machines": (Machine, Machine.name, machine_json, (selectinload(Machine.sources),)),
    "muscles": (MuscleGroup, MuscleGroup.name, lambda m: {"name": m.name}, ()),
    "plans": (
        PlanTemplate, PlanTemplate.slug, template_json,
        (selectinload(PlanTemplate.sources),
         selectinload(PlanTemplate.days).selectinload(PlanDay.slots)),
    ),
}  # fmt: skip
assert set(CATALOG_EXPORT) == set(CATALOG_ENTITIES)  # noqa: S101


def _catalog_versions() -> dict[str, int]:
    stored = {e: v for e, v in db.session.execute(select(CatalogMeta.entity, CatalogMeta.version))}
    return {e: stored.get(e, 0) for e in CATALOG_ENTITIES}


def register_sync(app: Flask) -> None:
    @app.post("/api/sync/push")
    @jwt_required()
    @limiter.limit(PUSH_LIMIT, key_func=user_key)
    def sync_push() -> Response:
        request.max_content_length = PUSH_MAX_BYTES  # raised for this route only
        body = PushBody.model_validate_json(request.get_data())
        uid, now = current_user_id(), datetime.now(UTC)
        ids = [r["op_id"] for r in body.ops if isinstance(r.get("op_id"), str)]
        known = {
            o.op_id: o.result
            for o in db.session.scalars(
                select(SyncOp).where(SyncOp.user_id == uid, SyncOp.op_id.in_(ids))
            )
        }
        results = [_push_one(uid, raw, known, now) for raw in body.ops]
        db.session.commit()
        return jsonify(results=results)

    @app.get("/api/sync/pull")
    @jwt_required()
    @limiter.limit(PULL_LIMIT, key_func=user_key)
    def sync_pull() -> Response:
        cursor = _int_arg("cursor", 0, 0, MAX_CURSOR)
        limit = _int_arg("limit", 500, 1, 1000)
        uid = current_user_id()
        rows = list(
            db.session.scalars(
                select(SyncChange)
                .where(SyncChange.user_id == uid, SyncChange.id > cursor)
                .order_by(SyncChange.id)
                .limit(limit + 1)
            )
        )
        more, rows = len(rows) > limit, rows[:limit]
        current = _current_rows(uid, rows)
        changes = []
        for c in rows:
            row = current.get((c.entity, c.client_id))  # upsert of a row gone since = tombstone
            changes.append({
                "cursor": c.id, "entity": c.entity, "client_id": c.client_id,
                "op": "upsert" if row is not None else "delete",
                "updated_at": _iso(row.updated_at if row is not None else c.created_at),
                "data": ENTITIES[c.entity].read(row) if row is not None else None,
            })  # fmt: skip
        return jsonify(changes=changes, next_cursor=rows[-1].id if rows else cursor, has_more=more)

    @app.get("/api/catalog/version")
    @limiter.limit(CATALOG_LIMIT)
    def catalog_version() -> Response:
        return jsonify(_catalog_versions())

    @app.get("/api/catalog/export")
    @limiter.limit(CATALOG_LIMIT)
    def catalog_export() -> Response:
        entity = request.args.get("entity", "")
        if entity not in CATALOG_EXPORT:
            abort(400, f"entity must be one of {', '.join(CATALOG_ENTITIES)}")
        limit, offset = page_args(default=500, cap=1000)
        version = _catalog_versions()[entity]
        etag = f"{entity}-{version}-{limit}-{offset}"
        headers = {"ETag": f'W/"{etag}"', "Cache-Control": "no-cache", "Vary": "Accept-Encoding"}
        if request.if_none_match.contains_weak(etag):
            return Response(status=304, headers=headers)
        model, order, to_json, options = CATALOG_EXPORT[entity]
        total = db.session.scalar(select(func.count()).select_from(model))
        rows = db.session.scalars(
            select(model).order_by(order).options(*options).limit(limit).offset(offset)
        )
        body = app.json.dumps({
            "entity": entity, "version": version, "total": total, "limit": limit,
            "offset": offset, "items": [to_json(r) for r in rows],
        }).encode()  # fmt: skip
        if "gzip" in request.headers.get("Accept-Encoding", ""):
            body = gzip.compress(body, mtime=0)  # stdlib; flask-compress would add a dependency
            headers["Content-Encoding"] = "gzip"
        return Response(body, mimetype="application/json", headers=headers)

    @app.cli.command("purge-sync-ops")
    @click.option("--days", default=30, show_default=True, type=click.IntRange(min=1))
    def purge_sync_ops(days: int) -> None:
        """Delete idempotency records older than DAYS (a phone must not replay an op that old)."""
        cutoff = datetime.now(UTC) - timedelta(days=days)
        done = db.session.execute(delete(SyncOp).where(SyncOp.created_at < cutoff))
        db.session.commit()
        click.echo(f"purged {done.rowcount} sync ops older than {days} days")
