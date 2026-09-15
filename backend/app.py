from __future__ import annotations

import os
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Final, Literal, TypedDict

from flask import Flask, Response, jsonify, request
from flask_jwt_extended import (
    JWTManager,
    create_access_token,
    get_jwt_identity,
    jwt_required,
)
from flask_sqlalchemy import SQLAlchemy
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    ValidationError,
    model_validator,
)
from pydantic_core import PydanticCustomError
from sqlalchemy import DateTime, Float, ForeignKey, String, select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from werkzeug.security import check_password_hash, generate_password_hash

Username = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        to_lower=True,
        min_length=3,
        max_length=32,
        pattern=r"^[A-Za-z0-9]+$",
    ),
]
Password = Annotated[str, StringConstraints(min_length=8, max_length=128)]
ExerciseType = Literal["squat", "push-up"]
MINIMUM_SECRET_LENGTH: Final = 32
COUNT_MISMATCH_CODE: Final = "count_mismatch"
COUNT_MISMATCH_MESSAGE: Final = "clean_count + attempted_count must equal rep_count"


class Base(DeclarativeBase):
    pass


db = SQLAlchemy(model_class=Base)
jwt = JWTManager()


class MissingJwtSecretError(RuntimeError):
    def __init__(self) -> None:
        super().__init__(
            "Set SAHIREP_JWT_SECRET to a random value containing at least 32 characters."
        )


class ApiPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class AuthPayload(ApiPayload):
    username: Username
    password: Password


class CompletedSetPayload(ApiPayload):
    exercise_type: ExerciseType
    rep_count: int = Field(gt=0)
    clean_count: int = Field(ge=0)
    attempted_count: int = Field(ge=0)
    formscore: float = Field(ge=0, le=100, allow_inf_nan=False)

    @model_validator(mode="after")
    def counts_match_total(self) -> CompletedSetPayload:
        if self.clean_count + self.attempted_count != self.rep_count:
            raise PydanticCustomError(
                COUNT_MISMATCH_CODE,
                COUNT_MISMATCH_MESSAGE,
            )
        return self


class SetJson(TypedDict):
    id: int
    exercise_type: str
    rep_count: int
    clean_count: int
    attempted_count: int
    formscore: float
    timestamp: str


class User(db.Model):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(256), nullable=False)


class WorkoutSet(db.Model):
    __tablename__ = "workout_sets"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    exercise_type: Mapped[str] = mapped_column(String(7), nullable=False)
    rep_count: Mapped[int] = mapped_column(nullable=False)
    clean_count: Mapped[int] = mapped_column(nullable=False)
    attempted_count: Mapped[int] = mapped_column(nullable=False)
    formscore: Mapped[float] = mapped_column(Float, nullable=False)
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(UTC),
        index=True,
        nullable=False,
    )

    def to_json(self) -> SetJson:
        timestamp = self.timestamp
        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=UTC)
        return {
            "id": self.id,
            "exercise_type": self.exercise_type,
            "rep_count": self.rep_count,
            "clean_count": self.clean_count,
            "attempted_count": self.attempted_count,
            "formscore": self.formscore,
            "timestamp": timestamp.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        }


def calculate_streak(workout_dates: list[date], today: date) -> int:
    unique_dates = sorted(set(workout_dates), reverse=True)
    if not unique_dates or unique_dates[0] < today - timedelta(days=1):
        return 0

    streak = 0
    expected = unique_dates[0]
    for workout_date in unique_dates:
        if workout_date != expected:
            break
        streak += 1
        expected -= timedelta(days=1)
    return streak


def create_app(
    *,
    database_uri: str | None = None,
    jwt_secret: str | None = None,
    testing: bool = False,
) -> Flask:
    resolved_secret = jwt_secret or os.environ.get("SAHIREP_JWT_SECRET")
    if resolved_secret is None or len(resolved_secret) < MINIMUM_SECRET_LENGTH:
        raise MissingJwtSecretError

    app = Flask(__name__, instance_relative_config=True)
    app.config.update(
        JWT_ACCESS_TOKEN_EXPIRES=timedelta(hours=12),
        JWT_SECRET_KEY=resolved_secret,
        MAX_CONTENT_LENGTH=16 * 1024,
        SQLALCHEMY_DATABASE_URI=database_uri or "sqlite:///sahirep.db",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        TESTING=testing,
    )
    db.init_app(app)
    jwt.init_app(app)

    @app.errorhandler(ValidationError)
    def invalid_payload(error: ValidationError) -> tuple[Response, int]:
        return (
            jsonify(
                error="invalid request",
                details=error.errors(
                    include_context=False,
                    include_input=False,
                    include_url=False,
                ),
            ),
            400,
        )

    @app.post("/api/auth/register")
    def register() -> tuple[Response, int]:
        payload = AuthPayload.model_validate_json(request.get_data())
        existing = db.session.execute(
            select(User).where(User.username == payload.username)
        ).scalar_one_or_none()
        if existing is not None:
            return jsonify(error="username already exists"), 409

        user = User()
        user.username = payload.username
        user.password_hash = generate_password_hash(payload.password)
        db.session.add(user)
        db.session.commit()
        return jsonify(message="registered"), 201

    @app.post("/api/auth/login")
    def login() -> tuple[Response, int]:
        payload = AuthPayload.model_validate_json(request.get_data())
        user = db.session.execute(
            select(User).where(User.username == payload.username)
        ).scalar_one_or_none()
        if user is None or not check_password_hash(user.password_hash, payload.password):
            return jsonify(error="invalid username or password"), 401
        return jsonify(access_token=create_access_token(identity=user.username)), 200

    @app.post("/api/sets")
    @jwt_required()
    def submit_set() -> tuple[Response, int]:
        payload = CompletedSetPayload.model_validate_json(request.get_data())
        username = str(get_jwt_identity())
        user_id = db.session.execute(select(User.id).where(User.username == username)).scalar_one()
        workout = WorkoutSet()
        workout.user_id = user_id
        workout.exercise_type = payload.exercise_type
        workout.rep_count = payload.rep_count
        workout.clean_count = payload.clean_count
        workout.attempted_count = payload.attempted_count
        workout.formscore = payload.formscore
        db.session.add(workout)
        db.session.commit()
        return jsonify(workout.to_json()), 201

    @app.get("/api/history")
    @jwt_required()
    def history() -> Response:
        username = str(get_jwt_identity())
        user_id = db.session.execute(select(User.id).where(User.username == username)).scalar_one()
        workouts = db.session.execute(
            select(WorkoutSet)
            .where(WorkoutSet.user_id == user_id)
            .order_by(WorkoutSet.timestamp.desc())
        ).scalars()
        sets = list(workouts)
        streak = calculate_streak(
            [workout.timestamp.date() for workout in sets],
            datetime.now(UTC).date(),
        )
        return jsonify(streak=streak, sets=[workout.to_json() for workout in sets])

    with app.app_context():
        db.create_all()

    return app


if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=5000, debug=False)
