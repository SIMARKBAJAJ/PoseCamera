"""Nutrition and calorie tracking: foods, profile, targets, food/weight logs.

Import schema: see NUTRITION_SCHEMA.md. All targets are ESTIMATES, not medical advice.
"""

from __future__ import annotations

import math
from datetime import date
from typing import TYPE_CHECKING, Annotated, Any, Literal

from flask import abort, jsonify, request
from flask_jwt_extended import jwt_required
from pydantic import AfterValidator, Field, field_validator, model_validator
from sqlalchemy import Date, ForeignKey, Index, String, UniqueConstraint, func, select
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app import (
    MAX_ID,
    ApiPayload,
    Syncable,
    current_user_id,
    db,
    page_args,
    paged,
    today_local,
    track,
)
from catalog import (
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
    import_rows,
    source_json,
)

if TYPE_CHECKING:
    from flask import Flask, Response

Sex = Literal["male", "female"]
ActivityLevel = Literal["sedentary", "light", "moderate", "active", "very_active"]
Goal = Literal["lose", "maintain", "gain"]
DietPref = Literal["veg", "egg", "nonveg", "vegan"]
Meal = Literal["breakfast", "lunch", "snack", "dinner"]
MEALS: tuple[str, ...] = ("breakfast", "lunch", "snack", "dinner")
MACROS: tuple[str, ...] = ("kcal", "protein_g", "carbs_g", "fat_g")
SNAPSHOT: tuple[str, ...] = (*MACROS, "fiber_g")  # FoodLog.<name>_per100 copies Food.<name>
Day = date  # alias: fields named `date` shadow the type inside class bodies
NonNeg = Annotated[float, Field(ge=0, le=1_000_000, allow_inf_nan=False)]  # per-100 g food data
Kcal = Annotated[float, Field(ge=0, le=10_000, allow_inf_nan=False)]
MacroG = Annotated[float, Field(ge=0, le=2_000, allow_inf_nan=False)]
Grams = Annotated[float, Field(ge=1, le=5_000, allow_inf_nan=False)]
DATE_WINDOW_DAYS = 366  # dates are accepted within one year of today

# Activity multipliers on BMR (Harris-Benedict/FAO-WHO style "PAL" factors, as popularised by
# McArdle, Katch & Katch, Exercise Physiology). Estimates; individual TDEE varies by +-10-20%.
ACTIVITY_FACTORS: dict[str, float] = {
    "sedentary": 1.2,
    "light": 1.375,
    "moderate": 1.55,
    "active": 1.725,
    "very_active": 1.9,
}
# Conservative energy adjustment vs TDEE. Lose: mid of the usual 10-20% deficit (larger deficits
# risk lean-mass loss). Gain: mid of a 5-10% lean-gain surplus.
GOAL_ADJUSTMENT: dict[str, float] = {"lose": -0.15, "maintain": 0.0, "gain": 0.075}
# Protein g/kg/day: ISSN Position Stand on protein and exercise (Jager et al. 2017) gives
# 1.4-2.0 g/kg for exercising people. Top of range in a deficit (preserve muscle), middle otherwise.
PROTEIN_G_PER_KG: dict[str, float] = {"lose": 2.0, "maintain": 1.6, "gain": 1.8}
# Fat floor: >= 20% of energy (low end of the 20-35% AMDR, IOM 2005) and >= 0.8 g/kg.
FAT_MIN_ENERGY_FRACTION = 0.20
FAT_MIN_G_PER_KG = 0.8
# Calorie floor for the "lose" goal only: ~1200 kcal/day women, ~1500 kcal/day men, the commonly
# cited conservative minimums for unsupervised dieting (approximate rules of thumb, not a standard).
MIN_KCAL_LOSE: dict[str, int] = {"female": 1200, "male": 1500}
KCAL_PER_G_PROTEIN = 4
KCAL_PER_G_CARB = 4
KCAL_PER_G_FAT = 9


def bmr_mifflin_st_jeor(sex: str, weight_kg: float, height_cm: float, age: int) -> float:
    """Mifflin-St Jeor (Mifflin et al., Am J Clin Nutr 1990;51:241): kcal/day at rest.

    10*kg + 6.25*cm - 5*age + 5 (male) or - 161 (female). Estimate only.
    """
    return 10 * weight_kg + 6.25 * height_cm - 5 * age + (5 if sex == "male" else -161)


def tdee(bmr: float, activity_level: str) -> float:
    """Total daily energy expenditure = BMR * ACTIVITY_FACTORS[level]. Estimate only."""
    return bmr * ACTIVITY_FACTORS[activity_level]


def calorie_target(tdee_kcal: float, goal: str, sex: str = "male") -> int:
    """Daily kcal = TDEE * (1 + GOAL_ADJUSTMENT[goal]), rounded; "lose" floored by sex. Estimate."""
    kcal = max(0, round(tdee_kcal * (1 + GOAL_ADJUSTMENT[goal])))
    return max(kcal, MIN_KCAL_LOSE[sex]) if goal == "lose" else kcal


def protein_target_g(weight_kg: float, goal: str) -> float:
    """Protein grams/day = PROTEIN_G_PER_KG[goal] * kg (ISSN 1.4-2.0 g/kg). Estimate only."""
    return PROTEIN_G_PER_KG[goal] * weight_kg


def macro_targets(kcal: float, weight_kg: float, goal: str) -> dict[str, float]:
    """Protein by goal, fat at its floor (or more), carbs fill the remaining energy (>= 0)."""
    kcal = max(0.0, kcal)
    protein = protein_target_g(weight_kg, goal)
    fat = max(FAT_MIN_G_PER_KG * weight_kg, FAT_MIN_ENERGY_FRACTION * kcal / KCAL_PER_G_FAT)
    carbs = max(
        0.0, (kcal - protein * KCAL_PER_G_PROTEIN - fat * KCAL_PER_G_FAT) / KCAL_PER_G_CARB
    )
    return {"kcal": kcal, "protein_g": protein, "carbs_g": carbs, "fat_g": fat}


def exercise_kcal(met: float, weight_kg: float, minutes: float) -> float:
    """Compendium of Physical Activities: kcal = MET * kg * hours. Estimate only."""
    return met * weight_kg * minutes / 60


food_sources = _link("foods_sources", "sources")


class Food(db.Model):
    __tablename__ = "foods"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    name_local: Mapped[str | None] = mapped_column(String(128))
    slug: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    category: Mapped[str | None] = mapped_column(String(64), index=True)
    region: Mapped[str | None] = mapped_column(String(64), index=True)
    kcal: Mapped[float] = mapped_column(nullable=False)
    protein_g: Mapped[float] = mapped_column(nullable=False)
    carbs_g: Mapped[float] = mapped_column(nullable=False)
    fat_g: Mapped[float] = mapped_column(nullable=False)
    fiber_g: Mapped[float | None]  # null = not reported (USDA); IFCT blank = 0 (below detection)
    sugar_g: Mapped[float | None]
    sodium_mg: Mapped[float | None]
    iron_mg: Mapped[float | None]
    calcium_mg: Mapped[float | None]
    vitamin_b12_ug: Mapped[float | None]
    verified: Mapped[bool] = mapped_column(default=False)
    sources: Mapped[list[Source]] = relationship(secondary=food_sources)
    servings: Mapped[list[FoodServing]] = relationship(
        back_populates="food", cascade="all, delete-orphan"
    )


class FoodServing(db.Model):
    __tablename__ = "food_servings"

    id: Mapped[int] = mapped_column(primary_key=True)
    food_id: Mapped[int] = mapped_column(ForeignKey("foods.id"), index=True, nullable=False)
    label: Mapped[str] = mapped_column(String(64), nullable=False)
    grams: Mapped[float] = mapped_column(nullable=False)
    food: Mapped[Food] = relationship(back_populates="servings")


class UserProfile(Syncable, db.Model):
    __tablename__ = "user_profiles"

    id: Mapped[int] = mapped_column(primary_key=True)
    client_id: Mapped[str | None] = mapped_column(String(64), default="profile")  # singleton per user
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), unique=True, nullable=False)
    sex: Mapped[str] = mapped_column(String(6), nullable=False)
    birth_year: Mapped[int] = mapped_column(nullable=False)
    height_cm: Mapped[float] = mapped_column(nullable=False)
    weight_kg: Mapped[float] = mapped_column(nullable=False)
    activity_level: Mapped[str] = mapped_column(String(16), nullable=False)
    goal: Mapped[str] = mapped_column(String(8), nullable=False)
    diet_pref: Mapped[str | None] = mapped_column(String(8))


class FoodLog(Syncable, db.Model):
    """A food entry (food_id set; macros = *_per100 snapshot * quantity_g/100) or a quick-add.

    The snapshot is taken from the Food row at creation so re-importing a food never rewrites
    past days.
    """

    __tablename__ = "food_logs"
    __table_args__ = (Index("uq_food_logs_user_id_client_id", "user_id", "client_id", unique=True),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    food_id: Mapped[int | None] = mapped_column(ForeignKey("foods.id"))
    serving_id: Mapped[int | None] = mapped_column(
        ForeignKey("food_servings.id", ondelete="SET NULL")
    )
    quantity_g: Mapped[float | None]
    name: Mapped[str | None] = mapped_column(String(128))
    kcal: Mapped[float | None]
    protein_g: Mapped[float | None]
    carbs_g: Mapped[float | None]
    fat_g: Mapped[float | None]
    kcal_per100: Mapped[float | None]
    protein_g_per100: Mapped[float | None]
    carbs_g_per100: Mapped[float | None]
    fat_g_per100: Mapped[float | None]
    fiber_g_per100: Mapped[float | None]
    meal: Mapped[str] = mapped_column(String(9), nullable=False)
    date: Mapped[Day] = mapped_column(Date, index=True, nullable=False)
    food: Mapped[Food | None] = relationship()
    serving: Mapped[FoodServing | None] = relationship()


class WeightLog(Syncable, db.Model):
    __tablename__ = "weight_logs"
    __table_args__ = (
        UniqueConstraint("user_id", "date"),
        Index("uq_weight_logs_user_id_client_id", "user_id", "client_id", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    date: Mapped[Day] = mapped_column(Date, nullable=False)
    kg: Mapped[float] = mapped_column(nullable=False)


track(UserProfile, "profile")
track(FoodLog, "food_logs")
track(WeightLog, "weight_logs")


def _in_window(day: date) -> date:
    if abs((day - today_local()).days) > DATE_WINDOW_DAYS:
        msg = "date must be within one year of today"
        raise ValueError(msg)
    return day


WindowDay = Annotated[date, AfterValidator(_in_window)]  # Day (plain) is for the DB models


class ServingRow(ApiPayload):
    label: Max64 = Field(min_length=1)
    grams: float = Field(gt=0, le=5_000, allow_inf_nan=False)


class FoodRow(ApiPayload):
    name: Max128 = Field(min_length=1)
    name_local: Max128 | None = None
    slug: Max128 = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    category: Max64 | None = None
    region: Max64 | None = None
    kcal: NonNeg
    protein_g: NonNeg
    carbs_g: NonNeg
    fat_g: NonNeg
    fiber_g: NonNeg | None = None
    sugar_g: NonNeg | None = None
    sodium_mg: NonNeg | None = None
    iron_mg: NonNeg | None = None
    calcium_mg: NonNeg | None = None
    vitamin_b12_ug: NonNeg | None = None
    verified: bool = False
    sources: list[Max256] = []
    servings: list[ServingRow] = []

    _dedupe_sources = field_validator("sources")(_dedupe)


class ProfilePayload(ApiPayload):
    sex: Sex
    birth_year: int = Field(ge=1900, le=2100)
    height_cm: float = Field(ge=100, le=250, allow_inf_nan=False)
    weight_kg: float = Field(ge=20, le=300, allow_inf_nan=False)
    activity_level: ActivityLevel
    goal: Goal
    diet_pref: DietPref | None = None

    @field_validator("birth_year")
    @classmethod
    def not_in_future(cls, year: int) -> int:
        if year > today_local().year:
            msg = "birth_year cannot be in the future"
            raise ValueError(msg)
        return year


class LogPayload(ApiPayload):
    """Either food_slug (+ quantity_g or serving_id) or a quick-add (name + macros)."""

    meal: Meal
    date: WindowDay | None = None
    food_slug: Max128 | None = None
    serving_id: int | None = Field(default=None, ge=1, le=MAX_ID)
    quantity_g: Grams | None = None
    name: Max128 | None = Field(default=None, min_length=1)
    kcal: Kcal | None = None
    protein_g: MacroG = 0
    carbs_g: MacroG = 0
    fat_g: MacroG = 0

    @model_validator(mode="after")
    def food_xor_quick_add(self) -> LogPayload:
        if (self.food_slug is None) == (self.name is None):
            msg = "give exactly one of food_slug or name"
            raise ValueError(msg)
        if self.food_slug is not None and self.quantity_g is None and self.serving_id is None:
            msg = "food entries need quantity_g or serving_id"
            raise ValueError(msg)
        if self.food_slug is not None and set(MACROS) & self.model_fields_set:
            msg = "food entries derive macros from quantity_g; do not send kcal or macros"
            raise ValueError(msg)
        if self.name is not None and self.kcal is None:
            msg = "quick add needs kcal"
            raise ValueError(msg)
        if self.name is not None and (self.serving_id is not None or self.quantity_g is not None):
            msg = "quick add cannot have serving_id or quantity_g"
            raise ValueError(msg)
        return self


class LogPatch(ApiPayload):
    quantity_g: Grams | None = None
    meal: Meal | None = None
    date: WindowDay | None = None
    kcal: Kcal | None = None
    protein_g: MacroG | None = None
    carbs_g: MacroG | None = None
    fat_g: MacroG | None = None


class WeightPayload(ApiPayload):
    kg: float = Field(ge=20, le=300, allow_inf_nan=False)
    date: WindowDay | None = None


def food_list_json(f: Food) -> dict[str, Any]:
    """Slim list item: per-100 g headline macros only (servings/sources are on the detail)."""
    keys = ("name", "name_local", "slug", "category", "region", "kcal", "protein_g", "carbs_g",
            "fat_g", "verified")  # fmt: skip
    return {k: getattr(f, k) for k in keys}


def food_json(f: Food) -> dict[str, Any]:
    fields = ("name", "name_local", "slug", "category", "region", "kcal", "protein_g", "carbs_g",
              "fat_g", "fiber_g", "sugar_g", "sodium_mg", "iron_mg", "calcium_mg",
              "vitamin_b12_ug", "verified")  # fmt: skip
    return {
        **{k: getattr(f, k) for k in fields},
        "servings": [{"id": s.id, "label": s.label, "grams": s.grams} for s in f.servings],
        "sources": [source_json(s) for s in f.sources],
    }


def _finite(value: float) -> float:
    return value if math.isfinite(value) else 0.0  # legacy rows must not emit Infinity JSON


def snapshot_food(log: FoodLog, food: Food) -> None:
    """Freeze the food's per-100 g values on the log (see FoodLog)."""
    log.food = food
    for m in SNAPSHOT:
        setattr(log, f"{m}_per100", getattr(food, m))


def fill_log(log: FoodLog, p: LogPayload) -> None:
    """Set a log's fields from a payload (404/400 via abort); shared by REST and sync push."""
    log.meal, log.date = p.meal, p.date or today_local()
    log.serving = None
    if p.food_slug is not None:
        food = db.first_or_404(select(Food).where(Food.slug == p.food_slug))
        if log.food is None or log.food.slug != food.slug:  # same food: keep the frozen snapshot
            snapshot_food(log, food)
        if p.serving_id is not None:
            serving = next((s for s in food.servings if s.id == p.serving_id), None)
            if serving is None:
                abort(400, "serving does not belong to food")
            log.serving = serving
        log.quantity_g = p.quantity_g or log.serving.grams
        log.name = log.kcal = log.protein_g = log.carbs_g = log.fat_g = None
    else:
        log.food, log.quantity_g = None, None
        for m in SNAPSHOT:
            setattr(log, f"{m}_per100", None)
        log.name, log.kcal = p.name, p.kcal
        log.protein_g, log.carbs_g, log.fat_g = p.protein_g, p.carbs_g, p.fat_g


def entry_macros(log: FoodLog) -> dict[str, float]:
    """Macros at read time: snapshot per-100g * quantity_g/100, or the quick-add's own values."""
    if log.food is not None:
        scale = (log.quantity_g or 0) / 100
        return {m: _finite((getattr(log, f"{m}_per100") or 0.0) * scale) for m in MACROS}
    return {m: _finite(getattr(log, m) or 0.0) for m in MACROS}


def entry_fiber(log: FoodLog) -> float | None:
    """Fiber g for the entry; None = unknown (null snapshot or quick-add), never invented."""
    per100 = log.fiber_g_per100
    if log.food is None or per100 is None:
        return None
    return _finite(per100 * (log.quantity_g or 0) / 100)


def _round(macros: dict[str, float]) -> dict[str, float]:
    return {k: round(v, 1) for k, v in macros.items()}


def log_json(log: FoodLog) -> dict[str, Any]:
    return {
        "id": log.id,
        "date": log.date.isoformat(),
        "meal": log.meal,
        "food": log.food.slug if log.food else None,
        "name": log.food.name if log.food else log.name,
        "serving": log.serving.label if log.serving else None,
        "quantity_g": log.quantity_g,
        **_round(entry_macros(log)),
        "fiber_g": None if (f := entry_fiber(log)) is None else round(f, 1),
    }


def _logs_for(user_id: int, day: date) -> list[FoodLog]:
    query = select(FoodLog).where(FoodLog.user_id == user_id, FoodLog.date == day)
    return list(db.session.scalars(query.order_by(FoodLog.id)))


def _sum(entries: list[FoodLog]) -> dict[str, float]:
    totals = dict.fromkeys(MACROS, 0.0)
    for entry in entries:
        for k, v in entry_macros(entry).items():
            totals[k] += v
    return {k: _finite(v) for k, v in totals.items()}


def import_foods(data: dict[str, Any]) -> dict[str, Any]:
    """Upsert a foods document; bad rows are reported, never abort the file."""

    def apply_food(row: FoodRow) -> bool:
        sources = _by_title(row.sources)
        fields = row.model_dump(exclude={"slug", "sources", "servings", "verified"})
        food, created = _upsert(
            Food, "slug", row.slug, **fields, verified=row.verified and bool(sources),
            sources=sources,
        )  # fmt: skip
        existing = {s.label: s for s in food.servings}
        servings = []
        for s in row.servings:
            serving = existing.get(s.label) or FoodServing(label=s.label)
            serving.grams = s.grams
            servings.append(serving)
        food.servings = servings
        return created

    return import_rows(data, (("sources", SourceRow, apply_source), ("foods", FoodRow, apply_food)))


def _profile_targets(profile: UserProfile, today: date) -> dict[str, float]:
    bmr = bmr_mifflin_st_jeor(
        profile.sex, profile.weight_kg, profile.height_cm, today.year - profile.birth_year
    )
    kcal = calorie_target(tdee(bmr, profile.activity_level), profile.goal, profile.sex)
    return _round(macro_targets(kcal, profile.weight_kg, profile.goal)) | {"bmr": round(bmr)}


def _profile_json(p: UserProfile) -> dict[str, Any]:
    keys = ("sex", "birth_year", "height_cm", "weight_kg", "activity_level", "goal", "diet_pref")
    return {k: getattr(p, k) for k in keys}


def _parse_date(raw: str | None) -> date:
    try:
        return _in_window(date.fromisoformat(raw)) if raw else today_local()
    except ValueError:
        return abort(400, "date must be YYYY-MM-DD, within one year of today")


def register_nutrition(app: Flask) -> None:  # noqa: C901, PLR0915 - flat route table
    def own_log(log_id: int) -> FoodLog:
        return db.first_or_404(
            select(FoodLog).where(FoodLog.id == log_id, FoodLog.user_id == current_user_id())
        )

    @app.get("/api/foods")
    def list_foods() -> Response:
        query = select(Food).order_by(Food.name, Food.id)
        if q := request.args.get("q"):
            q = q.lower()
            query = query.where(
                func.lower(Food.name).contains(q, autoescape=True)
                | func.lower(func.coalesce(Food.name_local, "")).contains(q, autoescape=True)
            )
        if category := request.args.get("category"):
            query = query.where(func.lower(Food.category) == category.lower())
        if region := request.args.get("region"):
            query = query.where(func.lower(Food.region) == region.lower())
        return paged(query, food_list_json)

    @app.get("/api/foods/<slug>")
    def get_food(slug: str) -> Response:
        return jsonify(food_json(db.first_or_404(select(Food).where(Food.slug == slug))))

    @app.put("/api/profile")
    @jwt_required()
    def put_profile() -> Response:
        payload = ProfilePayload.model_validate_json(request.get_data())
        profile, _ = _upsert(UserProfile, "user_id", current_user_id(), **payload.model_dump())
        db.session.commit()
        return jsonify(_profile_json(profile))

    def my_profile() -> UserProfile:
        return db.first_or_404(select(UserProfile).where(UserProfile.user_id == current_user_id()))

    @app.get("/api/profile")
    @jwt_required()
    def get_profile() -> Response:
        return jsonify(_profile_json(my_profile()))

    @app.get("/api/nutrition/targets")
    @jwt_required()
    def get_targets() -> Response:
        return jsonify(_profile_targets(my_profile(), today_local()))

    @app.post("/api/nutrition/log")
    @jwt_required()
    def add_log() -> tuple[Response, int]:
        p = LogPayload.model_validate_json(request.get_data())
        log = FoodLog(user_id=current_user_id())
        fill_log(log, p)
        db.session.add(log)
        db.session.commit()
        return jsonify(log_json(log)), 201

    @app.get("/api/nutrition/log")
    @jwt_required()
    def list_logs() -> Response:
        day = _parse_date(request.args.get("date"))
        return jsonify([log_json(r) for r in _logs_for(current_user_id(), day)])

    @app.patch("/api/nutrition/log/<int:log_id>")
    @jwt_required()
    def patch_log(log_id: int) -> tuple[Response, int] | Response:
        log = own_log(log_id)
        changes = LogPatch.model_validate_json(request.get_data()).model_dump(exclude_unset=True)
        own = {"quantity_g"} if log.food else {"kcal", "protein_g", "carbs_g", "fat_g"}
        if bad := set(changes) - own - {"meal", "date"}:
            return jsonify(error=f"cannot edit {sorted(bad)} on this entry"), 400
        for key, value in changes.items():
            if value is None:
                return jsonify(error=f"{key} cannot be null"), 400
            setattr(log, key, value)
        if "quantity_g" in changes:
            log.serving = None  # the serving no longer describes the amount
        db.session.commit()
        return jsonify(log_json(log))

    @app.delete("/api/nutrition/log/<int:log_id>")
    @jwt_required()
    def delete_log(log_id: int) -> tuple[str, int]:
        db.session.delete(own_log(log_id))
        db.session.commit()
        return "", 204

    @app.get("/api/nutrition/day")
    @jwt_required()
    def day_summary() -> Response:
        day = _parse_date(request.args.get("date"))
        user_id = current_user_id()
        entries = _logs_for(user_id, day)
        totals = _sum(entries)
        fiber = sum(entry_fiber(e) or 0.0 for e in entries)  # null counts as 0, none invented
        profile = db.session.scalar(select(UserProfile).where(UserProfile.user_id == user_id))
        targets = _profile_targets(profile, day) if profile else None
        meals = {
            meal: {
                "entries": [log_json(e) for e in entries if e.meal == meal],
                "totals": _round(_sum([e for e in entries if e.meal == meal])),
            }
            for meal in MEALS
        }
        remaining = (
            {k: round(targets[k] - totals[k], 1) for k in ("kcal", "protein_g")} if targets else None
        )
        return jsonify(
            date=day.isoformat(),
            totals=_round(totals),
            fiber_g=round(fiber, 1),
            targets=targets,
            remaining=remaining,
            meals=meals,
        )

    @app.post("/api/weight")
    @jwt_required()
    def add_weight() -> tuple[Response, int]:
        p = WeightPayload.model_validate_json(request.get_data())
        user_id, day = current_user_id(), p.date or today_local()
        row = db.session.scalar(
            select(WeightLog).where(WeightLog.user_id == user_id, WeightLog.date == day)
        )
        if row is None:  # one weigh-in per day: re-posting a date replaces it
            row = WeightLog(user_id=user_id, date=day)
            db.session.add(row)
        row.kg = p.kg
        db.session.commit()
        return jsonify(id=row.id, date=day.isoformat(), kg=row.kg), 201

    @app.get("/api/weight")
    @jwt_required()
    def list_weight() -> Response:
        limit, offset = page_args(default=200, cap=200)
        rows = db.session.scalars(  # newest page first, returned oldest-first as before
            select(WeightLog)
            .where(WeightLog.user_id == current_user_id())
            .order_by(WeightLog.date.desc())
            .limit(limit)
            .offset(offset)
        ).all()
        return jsonify([{"id": r.id, "date": r.date.isoformat(), "kg": r.kg} for r in reversed(rows)])

    @app.delete("/api/weight/<int:weight_id>")
    @jwt_required()
    def delete_weight(weight_id: int) -> tuple[str, int]:
        row = db.first_or_404(
            select(WeightLog).where(WeightLog.id == weight_id, WeightLog.user_id == current_user_id())
        )
        db.session.delete(row)
        db.session.commit()
        return "", 204

    add_import_command(app, "import-foods", import_foods)
