"""Workout plans: slot-based templates, built-in generator, JSON importer, user plan routes.

Import schema and API: see PLANS_SCHEMA.md. Built-in plans are heuristics: verified=false, and
every claim they rest on is listed in EVIDENCE_NOTES for the user to check against the papers.
"""

from __future__ import annotations

from collections import Counter
from datetime import date  # noqa: TC003 - SQLAlchemy resolves Mapped[date] at runtime
from math import ceil
from typing import TYPE_CHECKING, Any, Literal

from flask import abort, jsonify, request
from flask_jwt_extended import jwt_required
from pydantic import Field, field_validator, model_validator
from sqlalchemy import JSON, Date, ForeignKey, Index, String, UniqueConstraint, select
from sqlalchemy.orm import Mapped, joinedload, mapped_column, relationship, selectinload

from app import MAX_ID, ApiPayload, Syncable, current_user_id, db, paged, today_local, track
from catalog import (
    Difficulty,
    Exercise,
    Max64,
    Max128,
    Max256,
    Source,
    SourceRow,
    _by_title,
    _dedupe,
    _link,
    _upsert,
    add_import_command,
    apply_source,
    exercise_json,
    import_rows,
    source_json,
)
from nutrition import UserProfile, WeightLog, WindowDay, exercise_kcal

if TYPE_CHECKING:
    from flask import Flask, Response

TrainingGoal = Literal["strength", "hypertrophy", "general"]
Split = Literal["full_body", "upper_lower", "push_pull_legs"]
Equipment = Literal["gym", "home", "bodyweight"]
LEVELS: tuple[str, ...] = ("beginner", "intermediate", "advanced")  # "medium" = intermediate
DIFFICULTY_RANK = {level: i for i, level in enumerate(LEVELS)}

# ---- built-in generator constants (heuristics; see EVIDENCE_NOTES) ----

# Weekly hard sets per target muscle, (min, max). Hypertrophy rises with weekly sets; >=10
# sets/muscle/week beat fewer (Schoenfeld, Ogborn & Krieger 2017, J Sports Sci 35:1073-1082).
# ~10-20 is the usual reading of later work (Schoenfeld et al. 2019, Med Sci Sports Exerc
# 51:94-103; Baz-Valle et al. 2022, J Hum Kinet). Beginner 6-10 is a conservative heuristic.
WEEKLY_SETS: dict[str, tuple[int, int]] = {
    "beginner": (6, 10),
    "intermediate": (10, 20),
    "advanced": (10, 20),
}
WEEKLY_SETS_TARGET: dict[str, int] = {"beginner": 8, "intermediate": 12, "advanced": 16}
MAX_SETS_PER_SLOT = 5  # spread volume over sessions instead of piling it on one exercise
# Train each muscle >= 2x/week: 2x beat 1x for hypertrophy (Schoenfeld, Ogborn & Krieger 2016,
# Sports Med 46:1689-1697).
MIN_FREQUENCY = 2
# Compound rep ranges. Hypertrophy is similar across a wide rep range taken near failure, strength
# favours heavy loads (Schoenfeld et al. 2017, J Strength Cond Res 31:3508-3523); ACSM 2009
# progression position stand (Med Sci Sports Exerc 41:687-708): 1-6 RM strength, 8-12 novice.
REP_RANGE: dict[str, tuple[int, int]] = {
    "strength": (3, 6),
    "hypertrophy": (6, 12),
    "general": (8, 12),
}
ISOLATION_REP_RANGE = (8, 15)  # heuristic: light single-joint work, any goal
# Rest: 2-3 min on compounds beat ~1 min (Schoenfeld et al. 2016, J Strength Cond Res
# 30:1805-1812; Singer et al. 2024 rest-interval meta-analysis); 60-90 s isolation is a heuristic.
COMPOUND_REST_S: dict[str, int] = {"strength": 180, "hypertrophy": 120, "general": 120}
ISOLATION_REST_S = 75
# Reps in reserve (Helms et al. 2016 RIR-RPE scale): further from failure while learning technique.
RIR: dict[str, int] = {"beginner": 3, "intermediate": 2, "advanced": 1}
# Movement pattern -> muscles an exercise may target (any primary match). Weekly sets count on
# the FIRST muscle only (direct volume); indirect volume from compounds is not counted.
PATTERN_MUSCLES: dict[str, tuple[str, ...]] = {
    "squat": ("quadriceps",),
    "lunge": ("quadriceps", "glutes"),
    "hinge": ("hamstrings", "glutes"),
    "knee_flexion": ("hamstrings",),
    "horizontal_push": ("chest",),
    "vertical_push": ("shoulders",),
    "horizontal_pull": ("back",),
    "vertical_pull": ("back",),
    "elbow_flexion": ("biceps",),
    "elbow_extension": ("triceps",),
    "lateral_raise": ("shoulders",),
    "front_raise": ("shoulders",),
    "rear_delt": ("shoulders",),
    "upright_row": ("shoulders", "back"),
    "shrug": ("back",),
    "chest_fly": ("chest",),
    "calf_raise": ("calves",),
    "core_flexion": ("abdominals",),
    "core_stability": ("abdominals",),
    "core_rotation": ("abdominals",),
    "hip_extension": ("glutes",),
    "wrist_curl": ("forearms",),
    "neck": ("neck",),
}
COMPOUNDS = frozenset(
    {"squat", "lunge", "hinge", "horizontal_push", "vertical_push", "horizontal_pull",
     "vertical_pull"}
)
ISOLATION = frozenset(PATTERN_MUSCLES) - COMPOUNDS  # single-joint, accessory and core work
_ARMS = ("elbow_flexion", "elbow_extension")
# lateral_raise, rear_delt and calf_raise have no bodyweight-capable exercise in the catalog (raw
# equipment is dumbbell/cable/bands/machine/barbell/other), so resolve_plan omits them for
# equipment=bodyweight instead of reporting them as unmet. Gym/home slots must resolve.
BODYWEIGHT_OMITTED = frozenset({"lateral_raise", "rear_delt", "calf_raise"})
SPLITS: dict[str, tuple[tuple[str, tuple[str, ...]], ...]] = {
    "full_body": (
        ("Full body A",
         ("squat", "hinge", "horizontal_push", "vertical_pull", "vertical_push", *_ARMS,
          "core_flexion")),
        ("Full body B",
         ("lunge", "hinge", "horizontal_push", "horizontal_pull", "vertical_push", *_ARMS,
          "core_stability")),
    ),
    "upper_lower": (
        ("Upper", ("horizontal_push", "horizontal_pull", "vertical_push", "vertical_pull",
                   "lateral_raise", "rear_delt", *_ARMS)),
        ("Lower", ("squat", "hinge", "lunge", "knee_flexion", "hip_extension", "calf_raise",
                   "core_flexion")),
    ),
    "push_pull_legs": (
        ("Push", ("horizontal_push", "vertical_push", "lateral_raise", "elbow_extension")),
        ("Pull", ("vertical_pull", "horizontal_pull", "rear_delt", "elbow_flexion")),
        ("Legs", ("squat", "hinge", "lunge", "knee_flexion", "hip_extension", "calf_raise",
                  "core_flexion")),
    ),
}  # fmt: skip
PROFILE_GOAL: dict[str, str] = {"gain": "hypertrophy", "maintain": "general", "lose": "general"}
SECONDS_PER_REP = 3  # assumption, only used for the kcal time estimate
LOAD_INCREASE_FRACTION = 0.05  # within ACSM 2009 +2-10% once reps exceed the target
LOAD_STEP_KG = 0.5
BUILTIN_NOTE = (
    "Built-in rule-based plan (heuristic, unverified). Attach sources via import-plans to verify."
)
EVIDENCE_NOTES: tuple[str, ...] = (
    (
        "Weekly sets/muscle: beginner 6-10, intermediate/advanced 10-20 (Schoenfeld, Ogborn & "
        "Krieger 2017; Schoenfeld et al. 2019; Baz-Valle et al. 2022). Beginner range: heuristic."
    ),
    "Only direct sets on the first muscle of each slot count; compound carry-over is ignored.",
    "Each muscle trained >= 2x/week (Schoenfeld, Ogborn & Krieger 2016).",
    (
        "Reps: strength 3-6, hypertrophy 6-12, general 8-12, isolation 8-15 (Schoenfeld et al. "
        "2017; ACSM 2009). Isolation range is a heuristic."
    ),
    (
        "Rest: compounds 120 s (180 s strength), isolation 75 s (Schoenfeld et al. 2016; Singer "
        "et al. 2024). Isolation rest is a heuristic."
    ),
    "RIR: beginner 3, intermediate 2, advanced 1 (Helms et al. 2016 scale; values are heuristics).",
    "Split by level/days and the 5% / 0.5 kg double-progression step are heuristics.",
    "kcal = exercise MET x kg x estimated minutes (3 s/rep + rest); rough estimate only.",
)
# Home/bodyweight catalogs rarely hold isolation work (curls, leg curls, triceps extensions). When an
# isolation slot has no match, try a compound pattern whose exercises also work the same muscle
# (listed as primary OR secondary: a pull-up's primary is "back", biceps is secondary). The slot
# reports `substituted_from`; every other criterion, incl. equipment, still applies.
ISOLATION_FALLBACK: dict[str, tuple[str, ...]] = {
    "elbow_flexion": ("vertical_pull", "horizontal_pull"),
    "knee_flexion": ("hinge",),
    "elbow_extension": ("horizontal_push", "vertical_push"),
}
# None = exercise with unknown equipment: only "gym" (anything goes) accepts it, since it cannot be
# shown to need no gear. home = free-weight (bands, balls, dumbbells...) + bodyweight.
EQUIPMENT_MACHINE_TYPES: dict[str, frozenset[str | None]] = {
    "gym": frozenset({None, "machine", "cable", "free-weight", "bodyweight"}),
    "home": frozenset({"free-weight", "bodyweight"}),
    "bodyweight": frozenset({"bodyweight"}),
}


def pick_split(level: str, days_per_week: int) -> str:
    """Beginners and <=3 days: full body; 4-5 days: upper/lower; 6 days: push/pull/legs x2."""
    if level == "beginner" or days_per_week <= 3:  # noqa: PLR2004
        return "full_body"
    return "push_pull_legs" if days_per_week == 6 else "upper_lower"  # noqa: PLR2004


def generate_plan(level: str, goal: str, days_per_week: int) -> dict[str, Any]:
    """Build a plan (import_plans shape) from the constants above. Never names exercises."""
    split = pick_split(level, days_per_week)
    blueprint = SPLITS[split]
    days = [blueprint[i % len(blueprint)] for i in range(days_per_week)]
    occurrences = Counter(PATTERN_MUSCLES[p][0] for _, patterns in days for p in patterns)

    def slot(pattern: str) -> dict[str, Any]:
        isolation = pattern in ISOLATION
        rep_min, rep_max = ISOLATION_REP_RANGE if isolation else REP_RANGE[goal]
        occ = occurrences[PATTERN_MUSCLES[pattern][0]]
        sets = round(WEEKLY_SETS_TARGET[level] / occ)
        if sets * occ < WEEKLY_SETS[level][0]:  # many slots on one muscle: rounding undershot
            sets = ceil(WEEKLY_SETS_TARGET[level] / occ)
        return {
            "movement_pattern": pattern,
            "muscles": list(PATTERN_MUSCLES[pattern]),
            "sets": min(MAX_SETS_PER_SLOT, max(1, sets)),
            "rep_min": rep_min,
            "rep_max": rep_max,
            "rest_s": ISOLATION_REST_S if isolation else COMPOUND_REST_S[goal],
            "rir": RIR[level],
            "difficulty_max": level,
        }

    return {
        "slug": f"builtin-{level}-{goal}-{days_per_week}d",
        "name": f"{level.title()} {goal} {days_per_week}-day {split.replace('_', ' ')}",
        "level": level,
        "goal": goal,
        "days_per_week": days_per_week,
        "split": split,
        "verified": False,
        "notes": BUILTIN_NOTE,
        "evidence_notes": list(EVIDENCE_NOTES),
        "sources": [],
        "days": [{"name": name, "slots": [slot(p) for p in patterns]} for name, patterns in days],
    }


def double_progression(
    last_reps: list[int], rep_min: int, rep_max: int, load: float
) -> dict[str, float]:
    """Next target from the reps per set last session at `load`.

    Every set at rep_max -> raise load ~5% (>= LOAD_STEP_KG, rounded to it) and restart at
    rep_min. Otherwise keep the load and aim one rep above the weakest set, within the range.
    """
    if min(last_reps) >= rep_max:
        step = max(LOAD_STEP_KG, round(load * LOAD_INCREASE_FRACTION / LOAD_STEP_KG) * LOAD_STEP_KG)
        return {"load": load + step, "reps": rep_min}
    return {"load": load, "reps": max(rep_min, min(rep_max, min(last_reps) + 1))}


# ---- models ----

plans_sources = _link("plans_sources", "sources")


class PlanTemplate(db.Model):
    __tablename__ = "plans"

    id: Mapped[int] = mapped_column(primary_key=True)
    slug: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    level: Mapped[str] = mapped_column(String(16), nullable=False)
    goal: Mapped[str] = mapped_column(String(16), nullable=False)
    days_per_week: Mapped[int] = mapped_column(nullable=False)
    split: Mapped[str] = mapped_column(String(16), nullable=False)
    verified: Mapped[bool] = mapped_column(default=False)
    notes: Mapped[str | None]
    evidence_notes: Mapped[list[str]] = mapped_column(JSON, default=list)
    sources: Mapped[list[Source]] = relationship(secondary=plans_sources)
    days: Mapped[list[PlanDay]] = relationship(
        cascade="all, delete-orphan", order_by="PlanDay.position"
    )


class PlanDay(db.Model):
    __tablename__ = "plan_days"

    id: Mapped[int] = mapped_column(primary_key=True)
    plan_id: Mapped[int] = mapped_column(ForeignKey("plans.id"), index=True, nullable=False)
    position: Mapped[int] = mapped_column(nullable=False)
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    slots: Mapped[list[PlanSlot]] = relationship(
        cascade="all, delete-orphan", order_by="PlanSlot.position"
    )


class PlanSlot(db.Model):
    __tablename__ = "plan_slots"

    id: Mapped[int] = mapped_column(primary_key=True)
    day_id: Mapped[int] = mapped_column(ForeignKey("plan_days.id"), index=True, nullable=False)
    position: Mapped[int] = mapped_column(nullable=False)
    movement_pattern: Mapped[str] = mapped_column(String(64), nullable=False)
    muscles: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    sets: Mapped[int] = mapped_column(nullable=False)
    rep_min: Mapped[int] = mapped_column(nullable=False)
    rep_max: Mapped[int] = mapped_column(nullable=False)
    rest_s: Mapped[int] = mapped_column(nullable=False)
    rir: Mapped[int] = mapped_column(nullable=False)
    difficulty_max: Mapped[str] = mapped_column(String(16), nullable=False)


class UserPlan(Syncable, db.Model):
    __tablename__ = "user_plans"

    id: Mapped[int] = mapped_column(primary_key=True)
    client_id: Mapped[str | None] = mapped_column(String(64), default="plan")  # singleton per user
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), unique=True, nullable=False)
    plan_id: Mapped[int] = mapped_column(ForeignKey("plans.id"), nullable=False)
    start_date: Mapped[date] = mapped_column(Date, nullable=False)
    equipment: Mapped[str] = mapped_column(String(16), nullable=False)
    plan: Mapped[PlanTemplate] = relationship()
    completions: Mapped[list[PlanCompletion]] = relationship(
        cascade="all, delete-orphan",
        order_by="PlanCompletion.completed_on, PlanCompletion.id",
    )


class PlanCompletion(Syncable, db.Model):
    __tablename__ = "plan_completions"
    __table_args__ = (
        UniqueConstraint("user_plan_id", "completed_on"),  # one per plan per day
        Index("uq_plan_completions_plan_client", "user_plan_id", "client_id", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_plan_id: Mapped[int] = mapped_column(ForeignKey("user_plans.id"), index=True)
    day_index: Mapped[int] = mapped_column(nullable=False)
    completed_on: Mapped[date] = mapped_column(Date, nullable=False)


track(UserPlan, "user_plans")
track(  # no user_id column: the owner is the plan's user
    PlanCompletion,
    "plan_completions",
    lambda conn, row: conn.scalar(select(UserPlan.user_id).where(UserPlan.id == row.user_plan_id)),
)

# ---- payloads ----


class SlotRow(ApiPayload):
    movement_pattern: Max64 = Field(min_length=1)
    muscles: list[Max64] = Field(min_length=1)
    sets: int = Field(ge=1, le=10)
    rep_min: int = Field(ge=1, le=100)
    rep_max: int = Field(ge=1, le=100)
    rest_s: int = Field(ge=0, le=600)
    rir: int = Field(ge=0, le=5)
    difficulty_max: Difficulty = "advanced"

    @model_validator(mode="after")
    def min_le_max(self) -> SlotRow:
        if self.rep_min > self.rep_max:
            msg = "rep_min must be <= rep_max"
            raise ValueError(msg)
        return self


class DayRow(ApiPayload):
    name: Max64 = Field(min_length=1)
    slots: list[SlotRow] = Field(min_length=1)


class PlanRow(ApiPayload):
    slug: Max128 = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    name: Max128 = Field(min_length=1)
    level: Difficulty
    goal: TrainingGoal
    days_per_week: int = Field(ge=2, le=6)
    split: Split
    verified: bool = False
    notes: str | None = None
    evidence_notes: list[str] = []
    sources: list[Max256] = []
    days: list[DayRow] = Field(min_length=1)

    _dedupe_sources = field_validator("sources")(_dedupe)


class GeneratePayload(ApiPayload):
    level: Difficulty = "beginner"
    goal: TrainingGoal | None = None  # default: from UserProfile.goal, else "general"
    days_per_week: int = Field(ge=2, le=6)
    equipment: Equipment = "gym"
    save: bool = False


class AssignPayload(ApiPayload):
    slug: Max128
    start_date: WindowDay | None = None
    equipment: Equipment = "gym"


class CompletePayload(ApiPayload):
    day_index: int | None = Field(default=None, ge=0, le=MAX_ID)
    date: WindowDay | None = None


# ---- import / json / resolution ----


def import_plans(data: dict[str, Any]) -> dict[str, Any]:
    """Upsert a plans document; bad rows are reported, never abort the file."""

    def apply_plan(row: PlanRow) -> bool:
        sources = _by_title(row.sources)
        days = [
            PlanDay(
                position=i,
                name=d.name,
                slots=[PlanSlot(position=j, **s.model_dump()) for j, s in enumerate(d.slots)],
            )
            for i, d in enumerate(row.days)
        ]
        fields = row.model_dump(exclude={"slug", "sources", "days", "verified"})
        return _upsert(
            PlanTemplate, "slug", row.slug, **fields, verified=row.verified and bool(sources),
            sources=sources, days=days,
        )[1]  # fmt: skip

    return import_rows(data, (("sources", SourceRow, apply_source), ("plans", PlanRow, apply_plan)))


def template_json(t: PlanTemplate) -> dict[str, Any]:
    keys = ("slug", "name", "level", "goal", "days_per_week", "split", "verified", "notes",
            "evidence_notes")  # fmt: skip
    slot_keys = tuple(SlotRow.model_fields)
    return {
        **{k: getattr(t, k) for k in keys},
        "sources": [source_json(s) for s in t.sources],
        "days": [
            {"name": d.name, "slots": [{k: getattr(s, k) for k in slot_keys} for s in d.slots]}
            for d in t.days
        ],
    }


def plan_list_json(t: PlanTemplate) -> dict[str, Any]:
    """Slim list item: no days/slots/sources (see GET /api/plans/<slug>)."""
    keys = ("slug", "name", "level", "goal", "days_per_week", "split", "verified")
    return {k: getattr(t, k) for k in keys}


def resolve_slot(
    slot: dict[str, Any], exercises: list[Exercise], equipment: str, kg: float | None
) -> dict[str, Any]:
    """Pick a catalog exercise for the slot, or report the first criterion nothing satisfied.

    Home/bodyweight isolation slots with no match fall back to ISOLATION_FALLBACK compounds and
    carry `substituted_from: <original pattern>`; the slot's movement_pattern is then the new one.
    """
    result = _resolve(slot, exercises, equipment, kg, secondary=False)
    original = slot["movement_pattern"].lower()
    if result["exercise"] is None and equipment != "gym" and original in ISOLATION_FALLBACK:
        for pattern in ISOLATION_FALLBACK[original]:
            alt = _resolve({**slot, "movement_pattern": pattern}, exercises, equipment, kg,
                           secondary=True)  # fmt: skip
            if alt["exercise"] is not None:
                return {**alt, "substituted_from": original}
    return result


def _resolve(
    slot: dict[str, Any], exercises: list[Exercise], equipment: str, kg: float | None,
    *, secondary: bool,
) -> dict[str, Any]:  # fmt: skip
    muscles = {m.lower() for m in slot["muscles"]}

    def worked(e: Exercise) -> set[str]:
        extra = e.secondary_muscles if secondary else []
        return {m.name.lower() for m in (*e.primary_muscles, *extra)}

    checks = (
        ("movement_pattern",
         lambda e: (e.movement_pattern or "").lower() == slot["movement_pattern"].lower()),
        ("muscles", lambda e: bool(muscles & worked(e))),
        ("difficulty_max",
         lambda e: DIFFICULTY_RANK[e.difficulty] <= DIFFICULTY_RANK[slot["difficulty_max"]]),
        ("equipment",
         lambda e: (e.machine.type if e.machine else None) in EQUIPMENT_MACHINE_TYPES[equipment]),
    )  # fmt: skip
    for criterion, check in checks:
        exercises = [e for e in exercises if check(e)]
        if not exercises:
            return {**slot, "exercise": None, "unmet": criterion, "kcal": None}
    best = min(
        exercises,
        key=lambda e: (
            not e.verified,
            equipment == "gym" and e.machine is None,  # gym: prefer machine-based
            -DIFFICULTY_RANK[e.difficulty],
            e.slug,
        ),
    )
    reps = (slot["rep_min"] + slot["rep_max"]) / 2
    minutes = slot["sets"] * (reps * SECONDS_PER_REP + slot["rest_s"]) / 60
    kcal = round(exercise_kcal(best.met, kg, minutes), 1) if best.met and kg else None
    return {**slot, "exercise": exercise_json(best), "unmet": None, "kcal": kcal}


def resolve_plan(plan: dict[str, Any], equipment: str, kg: float | None = None) -> dict[str, Any]:
    """Resolve every slot; day kcal_estimate sums slots with a known kcal (null if none).

    equipment=bodyweight drops BODYWEIGHT_OMITTED patterns (no catalog exercise can fill them).
    """
    exercises = list(  # one pass over the catalog; slots are resolved in memory
        db.session.scalars(
            select(Exercise).options(
                joinedload(Exercise.machine),
                selectinload(Exercise.primary_muscles),
                selectinload(Exercise.secondary_muscles),
                selectinload(Exercise.sources),
            )
        )
    )
    days = []
    for day in plan["days"]:
        slots = [
            resolve_slot(s, exercises, equipment, kg)
            for s in day["slots"]
            if equipment != "bodyweight" or s["movement_pattern"].lower() not in BODYWEIGHT_OMITTED
        ]
        known = [s["kcal"] for s in slots if s["kcal"] is not None]
        kcal = round(sum(known), 1) if known else None
        days.append({**day, "slots": slots, "kcal_estimate": kcal})
    return {**plan, "equipment": equipment, "days": days}


def _weight_kg(user_id: int) -> float | None:
    """Latest weigh-in, else profile weight, else None."""
    latest = db.session.scalar(
        select(WeightLog.kg).where(WeightLog.user_id == user_id).order_by(WeightLog.date.desc())
    )
    if latest is not None:
        return latest
    return db.session.scalar(select(UserProfile.weight_kg).where(UserProfile.user_id == user_id))


def _next_index(up: UserPlan) -> int:
    """Day after the latest completion (by date, then id); modulo copes with a shrunk plan."""
    return (up.completions[-1].day_index + 1) % len(up.plan.days) if up.completions else 0


def register_plans(app: Flask) -> None:  # noqa: C901, PLR0915 - flat route table
    def plan_by_slug(slug: str) -> PlanTemplate:
        return db.first_or_404(
            select(PlanTemplate)
            .where(PlanTemplate.slug == slug)
            .options(
                selectinload(PlanTemplate.sources),
                selectinload(PlanTemplate.days).selectinload(PlanDay.slots),
            )
        )

    def my_plan() -> UserPlan:
        return db.first_or_404(select(UserPlan).where(UserPlan.user_id == current_user_id()))

    @app.get("/api/plans")
    def list_plans() -> Response:
        query = select(PlanTemplate).order_by(PlanTemplate.slug)
        for key in ("level", "goal"):
            if value := request.args.get(key):
                query = query.where(getattr(PlanTemplate, key) == value)
        if days := request.args.get("days", type=int):
            if abs(days) > MAX_ID:
                abort(400, "days out of range")
            query = query.where(PlanTemplate.days_per_week == days)
        return paged(query, plan_list_json)

    @app.get("/api/plans/<slug>")
    def get_plan(slug: str) -> tuple[Response, int] | Response:
        equipment = request.args.get("equipment", "gym")
        if equipment not in EQUIPMENT_MACHINE_TYPES:
            return jsonify(error="equipment must be gym, home or bodyweight"), 400
        return jsonify(resolve_plan(template_json(plan_by_slug(slug)), equipment))

    @app.post("/api/plans/generate")
    @jwt_required()
    def generate() -> Response:
        p = GeneratePayload.model_validate_json(request.get_data())
        user_id = current_user_id()
        profile = db.session.scalar(select(UserProfile).where(UserProfile.user_id == user_id))
        goal = p.goal or (PROFILE_GOAL[profile.goal] if profile else "general")
        plan = generate_plan(p.level, goal, p.days_per_week)
        if p.save:  # create-only: never touch a plan that already holds this slug
            existing = db.session.scalar(select(PlanTemplate).where(PlanTemplate.slug == plan["slug"]))
            if existing is not None:
                plan = template_json(existing) | {"existing": True}
            else:
                import_plans({"plans": [plan]})
        return jsonify(resolve_plan(plan, p.equipment, _weight_kg(user_id)) | {"saved": p.save})

    @app.post("/api/me/plan")
    @jwt_required()
    def assign_plan() -> tuple[Response, int]:
        p = AssignPayload.model_validate_json(request.get_data())
        plan = plan_by_slug(p.slug)
        up, _ = _upsert(  # completions=[]: a new assignment restarts the rotation
            UserPlan, "user_id", current_user_id(), plan=plan,
            start_date=p.start_date or today_local(), equipment=p.equipment, completions=[],
        )  # fmt: skip
        db.session.commit()
        start = up.start_date.isoformat()
        return jsonify(plan=plan.slug, start_date=start, equipment=up.equipment), 201

    @app.get("/api/me/plan/today")
    @jwt_required()
    def plantoday_local() -> Response:
        """Next unfinished day in rotation; if a day was completed today, that day (done=true)."""
        up, today = my_plan(), today_local()
        last = next((c for c in up.completions if c.completed_on == today), None)
        done = last is not None
        index = last.day_index % len(up.plan.days) if last else _next_index(up)
        template = template_json(up.plan)
        template["days"] = [template["days"][index]]
        day = resolve_plan(template, up.equipment, _weight_kg(up.user_id))["days"][0]
        return jsonify(
            date=today.isoformat(),
            plan=up.plan.slug,
            day_index=index,
            completed_today=done,
            day=day,
        )

    @app.post("/api/me/plan/complete-day")
    @jwt_required()
    def complete_day() -> tuple[Response, int]:
        p = CompletePayload.model_validate_json(request.get_data())
        up = my_plan()
        index = _next_index(up) if p.day_index is None else p.day_index
        if index >= len(up.plan.days):
            return jsonify(error="day_index out of range"), 400
        day = p.date or today_local()
        same_day = next((c for c in up.completions if c.completed_on == day), None)
        if same_day is not None:  # idempotent: one completion per plan per date
            return jsonify(day_index=same_day.day_index, date=day.isoformat(),
                           next_day_index=_next_index(up)), 200  # fmt: skip
        up.completions.append(PlanCompletion(day_index=index, completed_on=day))
        db.session.commit()
        return jsonify(day_index=index, date=day.isoformat(), next_day_index=_next_index(up)), 201

    add_import_command(app, "import-plans", import_plans)
