from __future__ import annotations

import os
import sqlite3
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Any, Final, Literal, TypedDict
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from flask import Flask, Response, abort, jsonify, request
from flask_jwt_extended import (
    JWTManager,
    current_user,
    get_jwt_identity,
    jwt_required,
    verify_jwt_in_request,
)
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_migrate import Migrate
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
from sqlalchemy import (
    JSON,
    BigInteger,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    event,
    func,
    inspect,
    insert,
    select,
    text,
)
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from werkzeug.exceptions import HTTPException
from werkzeug.routing import IntegerConverter
# security.py calls these through this module so tests can spy on every hash
from werkzeug.security import check_password_hash, generate_password_hash  # noqa: F401

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
NewPassword = Annotated[str, StringConstraints(min_length=10, max_length=128)]
Email = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True, to_lower=True, max_length=254, pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$"
    ),
]
DeviceId = Annotated[  # the app's per-install UUIDv4
    str, StringConstraints(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9-]+$")
]
Label = Annotated[str, StringConstraints(strip_whitespace=True, max_length=128)]
ExerciseType = Literal["squat", "push-up", "other"]  # other = any non-pose-tracked exercise_slug
SetType = Literal["warmup", "working", "drop", "failure"]
POSE_SLUG: Final = {"squat": "bodyweight-squat", "push-up": "pushups"}  # pose-tracked exercises
MINIMUM_SECRET_LENGTH: Final = 32
COUNT_MISMATCH_CODE: Final = "count_mismatch"
COUNT_MISMATCH_MESSAGE: Final = "clean_count + attempted_count must equal rep_count"
DEFAULT_TZ: Final = "Asia/Kolkata"  # app targets Indian users; override with env SAHIREP_TZ
AUTH_LIMIT: Final = "5 per minute"
MAX_REPS: Final = 1000
MAX_ID: Final = 2**31 - 1  # ids beyond this overflow SQLite/driver ints; they cannot exist
MAX_OFFSET: Final = 10**9
# Lowercased, >= 10 chars (shorter ones already fail the length rule).
COMMON_PASSWORDS: Final = frozenset(
    """1234567890 12345678910 123456789012 1234567890123 0123456789 0987654321 1q2w3e4r5t
    qwertyuiop qwerty1234 qwerty12345 qwertyuiop123 asdfghjkl1 asdfghjklqwerty 1qaz2wsx3edc
    password12 password123 password1234 password12345 password123456 password1! password@123
    passw0rd123 p@ssw0rd123 p@ssword123 changeme123 changemenow welcome123 welcome1234
    letmein123 letmein1234 iloveyou123 iloveyou12 iloveyou1234 admin12345 admin123456
    administrator abcdefghij abcd123456 abc1234567 abcdefg123 football123 baseball123
    superman123 monkey1234 dragon1234 trustno1234 sunshine123 princess123 master1234
    shadow1234 qazwsxedcrfv zaq12wsxcde3 1111111111 0000000000 9876543210""".split()  # noqa: SIM905
)


class Base(DeclarativeBase):
    pass


db = SQLAlchemy(model_class=Base)
jwt = JWTManager()
migrate = Migrate()
# storage: env RATELIMIT_STORAGE_URI (redis://...), else per-process memory (see create_app)
limiter = Limiter(get_remote_address)
# Verified against for unknown usernames so login timing does not reveal which accounts exist.
DUMMY_HASH: Final = generate_password_hash("sahirep-dummy-password")


@event.listens_for(Engine, "connect")
def _sqlite_pragmas(dbapi_connection: object, _record: object) -> None:
    if isinstance(dbapi_connection, sqlite3.Connection):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()


def app_tz() -> ZoneInfo:
    """The one timezone for day boundaries (streaks, nutrition); env SAHIREP_TZ."""
    return ZoneInfo(os.environ.get("SAHIREP_TZ") or DEFAULT_TZ)


def today_local() -> date:
    """Today's date in SAHIREP_TZ; the one place "today" is computed."""
    return datetime.now(app_tz()).date()


def env_flag(name: str) -> bool:
    return (os.environ.get(name) or "").strip().lower() in {"1", "true", "yes", "on"}


def database_uri_from_env() -> str:
    """SAHIREP_DATABASE_URI, else DATABASE_URL (postgresql+psycopg://...), else the local SQLite."""
    uri = (
        os.environ.get("SAHIREP_DATABASE_URI")
        or os.environ.get("DATABASE_URL")
        or "sqlite:///sahirep.db"
    )
    for plain in ("postgres://", "postgresql://"):  # bare schemes would pick the psycopg2 driver
        if uri.startswith(plain):
            return "postgresql+psycopg://" + uri.removeprefix(plain)
    return uri


def engine_options(uri: str) -> dict[str, Any]:
    options: dict[str, Any] = {"pool_pre_ping": True}
    if not uri.startswith("sqlite"):  # sqlite keeps the driver's default pool
        options |= {
            "pool_size": int(os.environ.get("SAHIREP_DB_POOL_SIZE") or 10),
            "max_overflow": int(os.environ.get("SAHIREP_DB_MAX_OVERFLOW") or 10),
            "pool_recycle": 1800,
        }
    return options


def user_key() -> str:
    """Rate-limit key: the JWT's user, else the client IP (bad/missing token: the route 401s)."""
    try:
        verify_jwt_in_request(optional=True)
        return f"user:{get_jwt_identity()}" if get_jwt_identity() else get_remote_address()
    except Exception:  # noqa: BLE001
        return get_remote_address()


class BoundedIntConverter(IntegerConverter):
    """`<int:x>` that does not match absurd values (-> 404 instead of an OverflowError 500)."""

    regex = r"\d{1,9}"  # < MAX_ID


def page_args(default: int = 50, cap: int = 100) -> tuple[int, int]:
    """(limit, offset) from the query string; limit is clamped to cap, junk is a 400."""
    try:
        limit = int(request.args.get("limit", default))
        offset = int(request.args.get("offset", 0))
    except ValueError:
        return abort(400, "limit and offset must be integers")
    if limit < 1 or not 0 <= offset <= MAX_OFFSET:
        return abort(400, "limit must be >= 1 and offset >= 0")
    return min(limit, cap), offset


def paged(query: Any, to_json: Any, *options: Any) -> Response:  # noqa: ANN401
    """Envelope {items,total,limit,offset}; options (selectinload...) apply to the page only."""
    limit, offset = page_args()
    total = db.session.scalar(select(func.count()).select_from(query.order_by(None).subquery()))
    rows = db.session.scalars(query.options(*options).limit(limit).offset(offset))
    return jsonify(items=[to_json(r) for r in rows], total=total, limit=limit, offset=offset)


class InvalidTimezoneError(RuntimeError):
    def __init__(self) -> None:
        super().__init__("SAHIREP_TZ must be a valid IANA timezone name, e.g. Asia/Kolkata.")


class MissingJwtSecretError(RuntimeError):
    def __init__(self) -> None:
        super().__init__(
            "Set SAHIREP_JWT_SECRET to a random value containing at least 32 characters."
        )


class ApiPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class AuthPayload(ApiPayload):
    username: Username
    password: Password


class LoginPayload(AuthPayload):
    device_id: DeviceId | None = None  # required once SAHIREP_DEVICE_ENFORCE is on
    device_token: Annotated[str, StringConstraints(max_length=128)] | None = None
    device_label: Label | None = None

    @model_validator(mode="after")
    def token_needs_device(self) -> LoginPayload:
        if self.device_token and not self.device_id:
            msg = "device_token requires device_id"
            raise ValueError(msg)
        return self


class RegisterPayload(AuthPayload):
    password: NewPassword
    consent: bool = False  # optional so older clients still register; stored as users.consent_at
    # optional until SAHIREP_DEVICE_ENFORCE (then required); not unique (SECURITY_DESIGN fork 2)
    email: Email | None = None
    device_id: DeviceId | None = None
    device_label: Label | None = None

    @model_validator(mode="after")
    def password_policy(self) -> RegisterPayload:
        lowered = self.password.lower()
        if self.username in lowered:
            msg = "password must not contain the username"
            raise ValueError(msg)
        if lowered in COMMON_PASSWORDS:
            msg = "password is too common"
            raise ValueError(msg)
        return self


class CompletedSetPayload(ApiPayload):
    exercise_type: ExerciseType
    exercise_slug: Annotated[str, StringConstraints(max_length=128)] | None = None
    load_kg: float | None = Field(default=None, ge=0, le=1000)
    rpe: float | None = Field(default=None, ge=1, le=10)
    set_type: SetType = "working"
    rep_count: int = Field(gt=0, le=MAX_REPS)
    # required for pose-tracked squat/push-up; optional (null = not measured) for "other"
    clean_count: int | None = Field(default=None, ge=0, le=MAX_REPS)
    attempted_count: int | None = Field(default=None, ge=0, le=MAX_REPS)
    formscore: float | None = Field(default=None, ge=0, le=100, allow_inf_nan=False)

    @model_validator(mode="after")
    def counts_match_total(self) -> CompletedSetPayload:
        if self.exercise_type == "other" and not self.exercise_slug:
            msg = "exercise_type 'other' requires exercise_slug"
            raise ValueError(msg)
        if self.exercise_slug and self.exercise_type in POSE_SLUG:
            if self.exercise_slug != POSE_SLUG[self.exercise_type]:
                msg = f"exercise_type '{self.exercise_type}' contradicts exercise_slug"
                raise ValueError(msg)
        form = (self.clean_count, self.attempted_count, self.formscore)
        if self.exercise_type in POSE_SLUG and None in form:
            msg = "clean_count, attempted_count and formscore are required for squat and push-up"
            raise ValueError(msg)
        if None not in form[:2] and self.clean_count + self.attempted_count != self.rep_count:
            raise PydanticCustomError(
                COUNT_MISMATCH_CODE,
                COUNT_MISMATCH_MESSAGE,
            )
        return self


class SetJson(TypedDict):
    id: int
    exercise_type: str
    rep_count: int
    clean_count: int | None
    attempted_count: int | None
    formscore: float | None
    timestamp: str
    exercise_slug: str | None
    load_kg: float | None
    rpe: float | None
    set_type: str


class User(db.Model):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(256), nullable=False)
    token_version: Mapped[int] = mapped_column(default=0, server_default="0", nullable=False)
    consent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    email: Mapped[str | None] = mapped_column(String(254), index=True)  # not unique (fork 2)
    email_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


def as_utc(stamp: datetime) -> datetime:
    # SQLite hands back naive datetimes; they are stored as UTC.
    return stamp if stamp.tzinfo else stamp.replace(tzinfo=UTC)


class Syncable:
    """Columns of every table the phone syncs. updated_at is the last-writer-wins clock."""

    # nullable in the DB so raw-SQL writers of old keep working; the ORM always fills both
    client_id: Mapped[str | None] = mapped_column(String(64), default=lambda: str(uuid4()))
    updated_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class SyncChange(db.Model):
    """Append-only change feed; its id is the pull cursor. Tombstones are op='delete' rows."""

    __tablename__ = "sync_changes"
    __table_args__ = (
        Index("ix_sync_changes_user_id_id", "user_id", "id"),
        {"sqlite_autoincrement": True},
    )

    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    entity: Mapped[str] = mapped_column(String(32), nullable=False)
    client_id: Mapped[str] = mapped_column(String(64), nullable=False)
    op: Mapped[str] = mapped_column(String(6), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class SyncOp(db.Model):
    """Result of every pushed op, so a replay returns it instead of applying twice (30 d retention)."""

    __tablename__ = "sync_ops"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    op_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    result: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), index=True, nullable=False
    )


SYNCED: dict[str, Any] = {}  # entity name -> model, filled by track()


def track(model: Any, entity: str, owner: Any = None) -> None:  # noqa: ANN401
    """Register a synced model: stamp updated_at and append every write to sync_changes.

    Mapper events fire for ORM writes from any route (REST or sync push), cascades included,
    so no write site can forget to log. owner(connection, row) -> user id (default row.user_id).
    """
    SYNCED[entity] = model

    def changed(row: Any) -> bool:  # noqa: ANN401
        return inspect(row).attrs.updated_at.history.has_changes()

    def stamp(_mapper: object, _conn: object, row: Any) -> None:  # noqa: ANN401
        session = inspect(row).session
        if not changed(row) and session and session.is_modified(row, include_collections=False):
            row.updated_at = datetime.now(UTC)  # an explicit (sync) value wins

    def log(op: str, *, only_if_changed: bool = False) -> Any:  # noqa: ANN401
        def handler(_mapper: object, conn: Any, row: Any) -> None:  # noqa: ANN401
            if only_if_changed and not changed(row):
                return  # flagged dirty without a net change
            uid = owner(conn, row) if owner else row.user_id
            if uid is None:
                return
            if conn.dialect.name == "postgresql":  # ids must commit in order per user (cursor)
                conn.execute(text("SELECT pg_advisory_xact_lock(:uid)"), {"uid": uid})
            conn.execute(
                insert(SyncChange).values(
                    user_id=uid, entity=entity, client_id=row.client_id, op=op,
                    created_at=datetime.now(UTC),
                )
            )  # fmt: skip

        return handler

    event.listen(model, "before_update", stamp)
    event.listen(model, "after_insert", log("upsert"))
    event.listen(model, "after_update", log("upsert", only_if_changed=True))
    event.listen(model, "after_delete", log("delete"))


class WorkoutSet(Syncable, db.Model):
    __tablename__ = "workout_sets"
    __table_args__ = (
        Index("uq_workout_sets_user_id_client_id", "user_id", "client_id", unique=True),
        CheckConstraint("load_kg BETWEEN 0 AND 1000", name="ck_workout_sets_load_kg"),
        CheckConstraint("rpe BETWEEN 1 AND 10", name="ck_workout_sets_rpe"),
        CheckConstraint(
            "set_type IN ('warmup','working','drop','failure')", name="ck_workout_sets_set_type"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    exercise_type: Mapped[str] = mapped_column(String(7), nullable=False)
    rep_count: Mapped[int] = mapped_column(nullable=False)
    clean_count: Mapped[int | None]  # form fields: null = not measured (non-pose exercises)
    attempted_count: Mapped[int | None]
    formscore: Mapped[float | None] = mapped_column(Float)
    exercise_slug: Mapped[str | None] = mapped_column(
        String(128), ForeignKey("exercises.slug", name="fk_workout_sets_exercise_slug")
    )
    load_kg: Mapped[float | None] = mapped_column(Float)
    rpe: Mapped[float | None] = mapped_column(Float)
    set_type: Mapped[str] = mapped_column(
        String(7), default="working", server_default="working", nullable=False
    )
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(UTC),
        index=True,
        nullable=False,
    )

    @property
    def aware_timestamp(self) -> datetime:
        return as_utc(self.timestamp)

    def to_json(self) -> SetJson:
        timestamp = self.aware_timestamp
        return {
            "id": self.id,
            "exercise_type": self.exercise_type,
            "rep_count": self.rep_count,
            "clean_count": self.clean_count,
            "attempted_count": self.attempted_count,
            "formscore": self.formscore,
            "timestamp": timestamp.astimezone(UTC).isoformat().replace("+00:00", "Z"),
            "exercise_slug": self.exercise_slug,
            "load_kg": self.load_kg,
            "rpe": self.rpe,
            "set_type": self.set_type,
        }


track(WorkoutSet, "workout_sets")


def check_set_slug(payload: CompletedSetPayload) -> None:
    """400 unless exercise_slug exists and pose-tracked exercises keep their own exercise_type."""
    if not payload.exercise_slug:
        return
    from catalog import Exercise  # noqa: PLC0415 - models need db defined first

    exercise = db.session.scalar(select(Exercise).where(Exercise.slug == payload.exercise_slug))
    if exercise is None:
        abort(400, "unknown exercise_slug")
    # slug check too: the live DB has has_pose_tracking=0 for these (0006 data step missed)
    pose = exercise.has_pose_tracking or exercise.slug in POSE_SLUG.values()
    if payload.exercise_type == "other" and pose:
        abort(400, "pose-tracked exercises use their own exercise_type")


def build_set(
    user_id: int, payload: CompletedSetPayload, timestamp: datetime | None = None
) -> WorkoutSet:
    workout = WorkoutSet()
    workout.user_id = user_id
    workout.exercise_slug = payload.exercise_slug
    workout.load_kg = payload.load_kg
    workout.rpe = payload.rpe
    workout.set_type = payload.set_type
    workout.exercise_type = payload.exercise_type
    workout.rep_count = payload.rep_count
    workout.clean_count = payload.clean_count
    workout.attempted_count = payload.attempted_count
    workout.formscore = payload.formscore
    if timestamp is not None:
        workout.timestamp = timestamp
    return workout


def current_user_id() -> int:
    """Authenticated user's id; the JWT user loader already 401s deleted/revoked users."""
    return current_user.id


def login_username() -> str:
    body = request.get_json(silent=True)
    name = body.get("username") if isinstance(body, dict) else None
    return str(name).strip().lower() if name else get_remote_address()


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


def create_app(  # noqa: C901, PLR0915 - flat route table
    *,
    database_uri: str | None = None,
    jwt_secret: str | None = None,
    testing: bool = False,
    otp_sender: Any = None,  # security.OtpSender; default from SAHIREP_OTP_SENDER  # noqa: ANN401
) -> Flask:
    resolved_secret = jwt_secret or os.environ.get("SAHIREP_JWT_SECRET")
    if resolved_secret is None or len(resolved_secret) < MINIMUM_SECRET_LENGTH:
        raise MissingJwtSecretError
    try:
        app_tz()
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise InvalidTimezoneError from error

    app = Flask(__name__, instance_relative_config=True)
    app.config.update(
        JWT_ACCESS_TOKEN_EXPIRES=timedelta(hours=12),
        JWT_SECRET_KEY=resolved_secret,
        JWT_ENCODE_ISSUER="sahirep",
        JWT_DECODE_ISSUER="sahirep",
        RATELIMIT_ENABLED=not testing,  # test suites register dozens of users from one IP
        MAX_CONTENT_LENGTH=16 * 1024,
        SQLALCHEMY_DATABASE_URI=database_uri or database_uri_from_env(),
        RATELIMIT_STORAGE_URI=os.environ.get("RATELIMIT_STORAGE_URI") or "memory://",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        TESTING=testing,
        # SECURITY_DESIGN.md: off during cutover (legacy logins allowed, no threat refusals)
        DEVICE_ENFORCE=env_flag("SAHIREP_DEVICE_ENFORCE"),
        HASH_SLOTS=int(os.environ.get("SAHIREP_HASH_SLOTS") or max(1, (os.cpu_count() or 2) - 1)),
        MIN_CLIENT_VERSION=int(os.environ.get("SAHIREP_MIN_CLIENT_VERSION") or 1),
        ADMIN_TOKEN=os.environ.get("SAHIREP_ADMIN_TOKEN") or None,
    )
    app.config["SQLALCHEMY_ENGINE_OPTIONS"] = engine_options(app.config["SQLALCHEMY_DATABASE_URI"])
    app.url_map.converters["int"] = BoundedIntConverter  # before any route is registered
    db.init_app(app)
    jwt.init_app(app)
    limiter.init_app(app)

    @jwt.user_lookup_loader
    def load_user(_header: dict[str, object], data: dict[str, object]) -> User | None:
        try:
            user = db.session.get(User, int(str(data["sub"])))
        except ValueError:  # pre-0003 tokens carried the username
            return None
        if user is None or user.token_version != data.get("tv"):
            return None
        did = data.get("did")
        if did is None:  # legacy JWT: refused once device enforcement is on
            return None if app.config["DEVICE_ENFORCE"] else user
        from security import Device  # noqa: PLC0415 - models need db defined first

        device = db.session.get(Device, did) if isinstance(did, int) else None
        alive = device is not None and device.user_id == user.id and device.revoked_at is None
        return user if alive else None

    @jwt.unauthorized_loader
    @jwt.invalid_token_loader
    @jwt.expired_token_loader
    @jwt.user_lookup_error_loader
    def jwt_failed(*_args: object) -> tuple[Response, int]:
        return jsonify(error="authentication required"), 401

    @app.errorhandler(HTTPException)
    def http_error(error: HTTPException) -> tuple[Response, int]:
        return jsonify(error=error.description), error.code or 500

    @app.errorhandler(Exception)
    def unexpected_error(error: Exception) -> tuple[Response, int]:
        app.logger.error("unhandled error", exc_info=error)
        return jsonify(error="internal server error"), 500

    @app.errorhandler(IntegrityError)
    def conflict(_error: IntegrityError) -> tuple[Response, int]:
        db.session.rollback()  # lost a check-then-insert race (unique constraint)
        return jsonify(error="conflict, please retry"), 409

    @app.after_request
    def api_headers(response: Response) -> Response:
        if request.path.startswith("/api/"):
            response.headers["X-Content-Type-Options"] = "nosniff"
            response.headers.setdefault("Cache-Control", "no-store")
        return response

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
    @limiter.limit(AUTH_LIMIT)
    def register() -> Response:
        payload = RegisterPayload.model_validate_json(request.get_data())
        return security.register_flow(payload)  # 201, or 202 otp_required when email is given

    @app.post("/api/auth/login")
    @limiter.limit(AUTH_LIMIT)
    @limiter.limit(AUTH_LIMIT, key_func=login_username)
    def login() -> Response:
        payload = LoginPayload.model_validate_json(request.get_data())
        return security.login_flow(payload)  # the SECURITY_DESIGN 6.2 order; one hash at most

    @app.post("/api/sets")
    @jwt_required()
    def submit_set() -> tuple[Response, int]:
        payload = CompletedSetPayload.model_validate_json(request.get_data())
        check_set_slug(payload)
        workout = build_set(current_user_id(), payload)
        db.session.add(workout)
        db.session.commit()
        return jsonify(workout.to_json()), 201

    @app.get("/api/history")
    @jwt_required()
    def history() -> Response:
        user_id = current_user_id()
        limit, offset = page_args(default=200, cap=200)
        sets = db.session.scalars(
            select(WorkoutSet)
            .where(WorkoutSet.user_id == user_id)
            .order_by(WorkoutSet.timestamp.desc())
            .limit(limit)
            .offset(offset)
        ).all()
        stamps = db.session.scalars(  # the streak spans all history, not just this page
            select(WorkoutSet.timestamp).where(WorkoutSet.user_id == user_id)
        )
        streak = calculate_streak(
            [as_utc(stamp).astimezone(app_tz()).date() for stamp in stamps], today_local()
        )
        return jsonify(streak=streak, sets=[workout.to_json() for workout in sets])

    import security  # noqa: PLC0415 - models need db defined first
    from catalog import register_catalog  # noqa: PLC0415 - models need db defined first

    from nutrition import register_nutrition  # noqa: PLC0415 - models need db defined first
    from me import register_me  # noqa: PLC0415 - models need db defined first
    from plans import register_plans  # noqa: PLC0415 - models need db defined first
    from sync import register_sync  # noqa: PLC0415 - models need db defined first

    register_catalog(app)
    register_nutrition(app)
    register_plans(app)
    register_me(app)
    register_sync(app)
    security.register_security(app, otp_sender)  # logout, logout-all, OTP, admin, CLI

    migrate.init_app(
        app, db, directory=f"{app.root_path}/migrations", render_as_batch=True
    )
    if database_uri is not None:  # tests/tmp DBs only; the default DB evolves via flask db upgrade
        with app.app_context():
            db.create_all()

    return app

