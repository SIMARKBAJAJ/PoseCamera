"""Per-user analytics (PRs, volume) and data rights (export, erasure). All JWT, own data only."""

from __future__ import annotations

from collections import Counter
from datetime import UTC, date, datetime, timedelta
from typing import TYPE_CHECKING, Any

from flask import Response, abort, jsonify, request
from flask_jwt_extended import jwt_required
from sqlalchemy import delete, func, select, update
from werkzeug.security import check_password_hash

from app import (
    AUTH_LIMIT,
    ApiPayload,
    Password,
    SyncChange,
    SyncOp,
    User,
    WorkoutSet,
    app_tz,
    as_utc,
    current_user,
    current_user_id,
    db,
    limiter,
    page_args,
    today_local,
    user_key,
)
from catalog import Exercise, MuscleGroup, exercises_primary
from nutrition import FoodLog, UserProfile, WeightLog
from plans import PlanCompletion, UserPlan
from security import Device, OtpChallenge, SecurityEvent

if TYPE_CHECKING:
    from flask import Flask

MAX_E1RM_REPS = 12
MAX_WEEKS = 26


class DeletePayload(ApiPayload):
    password: Password


def epley_1rm(load_kg: float, reps: int) -> float:
    """Estimated 1RM = load * (1 + reps/30): Epley (1985). An estimate, unreliable above ~12 reps."""
    return load_kg * (1 + reps / 30)


def _row(obj: Any, skip: tuple[str, ...] = ()) -> dict[str, Any]:  # noqa: ANN401
    out = {}
    for col in obj.__table__.columns:
        value = getattr(obj, col.key)
        out[col.key] = value.isoformat() if isinstance(value, date | datetime) else value
    return {k: v for k, v in out.items() if k not in skip}


def register_me(app: Flask) -> None:
    @app.get("/api/me/prs")
    @jwt_required()
    def my_prs() -> Response:
        uid = current_user_id()
        limit, offset = page_args()
        mine = (WorkoutSet.user_id == uid) & WorkoutSet.exercise_slug.is_not(None)
        base = select(WorkoutSet.exercise_slug).where(mine, WorkoutSet.load_kg > 0)
        total = db.session.scalar(select(func.count()).select_from(base.distinct().subquery()))
        heaviest = db.session.execute(
            select(WorkoutSet.exercise_slug, func.max(WorkoutSet.load_kg))
            .where(mine, WorkoutSet.load_kg > 0)
            .group_by(WorkoutSet.exercise_slug)
            .order_by(WorkoutSet.exercise_slug)
            .limit(limit)
            .offset(offset)
        ).all()
        e1rm = WorkoutSet.load_kg * (1 + WorkoutSet.rep_count / 30.0)
        ranked = (
            select(
                WorkoutSet,
                func.row_number()
                .over(
                    partition_by=WorkoutSet.exercise_slug,
                    order_by=(e1rm.desc(), WorkoutSet.timestamp.desc()),
                )
                .label("rn"),
            )
            .where(
                mine,
                WorkoutSet.load_kg > 0,
                WorkoutSet.rep_count <= MAX_E1RM_REPS,
                WorkoutSet.exercise_slug.in_([slug for slug, _ in heaviest]),
            )
            .subquery()
        )
        best = {
            w.exercise_slug: w
            for w in db.session.scalars(
                select(WorkoutSet).join(ranked, WorkoutSet.id == ranked.c.id).where(ranked.c.rn == 1)
            )
        }
        items = []
        for slug, kg in heaviest:
            w = best.get(slug)
            items.append(
                {
                    "exercise_slug": slug,
                    "heaviest_kg": kg,
                    "best_e1rm": w
                    and {
                        "e1rm_kg": round(epley_1rm(w.load_kg, w.rep_count), 1),
                        "load_kg": w.load_kg,
                        "reps": w.rep_count,
                        "set_id": w.id,
                        "timestamp": w.to_json()["timestamp"],
                    },
                }
            )
        return jsonify(items=items, total=total, limit=limit, offset=offset)

    @app.get("/api/me/volume")
    @jwt_required()
    def my_volume() -> Response:
        try:
            weeks = int(request.args.get("weeks", 4))
        except ValueError:
            return abort(400, "weeks must be an integer")
        if not 1 <= weeks <= MAX_WEEKS:
            return abort(400, f"weeks must be between 1 and {MAX_WEEKS}")
        today = today_local()
        first_monday = today - timedelta(days=today.weekday() + 7 * (weeks - 1))
        start = datetime.combine(first_monday, datetime.min.time(), app_tz()).astimezone(UTC)
        # ponytail: rows are bucketed in Python (tz weeks); <= 26 weeks of one user's sets.
        rows = db.session.execute(
            select(WorkoutSet.timestamp, MuscleGroup.name)
            .join(Exercise, Exercise.slug == WorkoutSet.exercise_slug)
            .join(exercises_primary, exercises_primary.c.owner_id == Exercise.id)
            .join(MuscleGroup, MuscleGroup.id == exercises_primary.c.other_id)
            .where(
                WorkoutSet.user_id == current_user_id(),
                WorkoutSet.set_type != "warmup",
                WorkoutSet.timestamp >= start,
            )
        )
        counts: Counter[tuple[date, str]] = Counter()
        for stamp, muscle in rows:
            day = as_utc(stamp).astimezone(app_tz()).date()
            counts[(day - timedelta(days=day.weekday()), muscle)] += 1
        items = [
            {
                "week": f"{monday.isocalendar().year}-W{monday.isocalendar().week:02d}",
                "week_start": monday.isoformat(),
                "muscle": muscle,
                "hard_sets": n,
            }
            for (monday, muscle), n in sorted(counts.items(), key=lambda kv: (-kv[0][0].toordinal(), kv[0][1]))
        ]
        return jsonify(weeks=weeks, timezone=str(app_tz()), items=items)

    @app.get("/api/me/export")
    @jwt_required()
    def export_me() -> Response:
        uid = current_user_id()

        def all_of(model: Any) -> list[dict[str, Any]]:  # noqa: ANN401
            rows = db.session.scalars(
                select(model).where(model.user_id == uid).order_by(model.id)
            )
            return [_row(r) for r in rows]

        plan = db.session.scalar(select(UserPlan).where(UserPlan.user_id == uid))
        body = {
            "user": _row(current_user, skip=("password_hash", "token_version")),
            "profile": all_of(UserProfile),
            "workout_sets": all_of(WorkoutSet),
            "food_logs": all_of(FoodLog),
            "weight_logs": all_of(WeightLog),
            "user_plans": [_row(plan)] if plan else [],
            "plan_completions": [_row(c) for c in plan.completions] if plan else [],
            "devices": [  # no token hashes
                {k: _row(d)[k] for k in ("label", "created_at", "last_seen_at", "revoked_at")}
                for d in db.session.scalars(select(Device).where(Device.user_id == uid).order_by(Device.id))
            ],
        }
        response = jsonify(body)
        response.headers["Content-Disposition"] = 'attachment; filename="sahirep-export.json"'
        return response

    @app.delete("/api/me")
    @jwt_required()
    @limiter.limit(AUTH_LIMIT)
    @limiter.limit(AUTH_LIMIT, key_func=user_key)
    def delete_me() -> Response | tuple[Response, int]:
        payload = DeletePayload.model_validate_json(request.get_data())
        if not check_password_hash(current_user.password_hash, payload.password):
            return jsonify(error="invalid password"), 401
        uid = current_user_id()
        plan_ids = select(UserPlan.id).where(UserPlan.user_id == uid)
        db.session.execute(delete(PlanCompletion).where(PlanCompletion.user_plan_id.in_(plan_ids)))
        models = (UserPlan, FoodLog, WeightLog, UserProfile, WorkoutSet, SyncChange, SyncOp,
                  Device, OtpChallenge)  # fmt: skip
        for model in models:
            db.session.execute(delete(model).where(model.user_id == uid))
        # the audit log is kept, unlinked from the account
        db.session.execute(update(SecurityEvent).where(SecurityEvent.user_id == uid).values(user_id=None))
        db.session.execute(delete(User).where(User.id == uid))
        db.session.commit()
        response = Response(status=204)
        del response.headers["Content-Type"]  # empty body: no html content type
        return response
