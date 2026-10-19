from __future__ import annotations

from datetime import UTC, date, datetime
from typing import TYPE_CHECKING

import pytest
from flask_jwt_extended import create_access_token, decode_token
from sqlalchemy.exc import IntegrityError

import app as app_module
from app import InvalidTimezoneError, User, WorkoutSet, create_app, db

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient

PASSWORD = "strong-password-123"


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'sec.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


@pytest.fixture
def limited_client(tmp_path: Path) -> Generator[FlaskClient, None, None]:
    # the limiter is only wired in when not testing=True (see create_app)
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'lim.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
    )
    yield application.test_client()
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


def signup(client: FlaskClient, name: str = "simar") -> str:
    reg = client.post("/api/auth/register", json={"username": name, "password": PASSWORD})
    assert reg.status_code == 201
    return client.post("/api/auth/login", json={"username": name, "password": PASSWORD}).json[
        "access_token"
    ]


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_login_is_rate_limited_per_ip_with_json_429(limited_client: FlaskClient) -> None:
    body = {"username": "nobody", "password": PASSWORD}
    codes = [limited_client.post("/api/auth/login", json=body).status_code for _ in range(6)]
    last = limited_client.post("/api/auth/login", json=body)
    assert codes[:5] == [401] * 5
    assert codes[5] == 429
    assert last.status_code == 429
    assert "error" in last.json


def test_login_is_rate_limited_per_username_across_ips(limited_client: FlaskClient) -> None:
    body = {"username": "victim", "password": PASSWORD}
    codes = [
        limited_client.post("/api/auth/login", json=body, environ_base={"REMOTE_ADDR": f"10.0.0.{i}"})
        .status_code
        for i in range(6)
    ]
    assert codes == [401] * 5 + [429]


def test_register_is_rate_limited(limited_client: FlaskClient) -> None:
    codes = [
        limited_client.post("/api/auth/register", json={"username": f"user{i}", "password": PASSWORD})
        .status_code
        for i in range(6)
    ]
    assert codes == [201] * 5 + [429]


@pytest.mark.parametrize(
    ("username", "password"),
    [
        ("simar", "short9chr"),
        ("simar", "my-SIMAR-pass-1"),
        ("simar", "Password123"),
        ("simar", "1234567890"),
    ],
)
def test_register_rejects_weak_passwords(client: FlaskClient, username: str, password: str) -> None:
    response = client.post("/api/auth/register", json={"username": username, "password": password})
    assert response.status_code == 400


def test_register_accepts_ten_char_unique_password(client: FlaskClient) -> None:
    response = client.post("/api/auth/register", json={"username": "simar", "password": "x7!kq2#mzp"})
    assert response.status_code == 201


def test_unknown_user_login_still_verifies_a_hash(
    client: FlaskClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen: list[str] = []
    real = app_module.check_password_hash

    def spy(password_hash: str, password: str) -> bool:
        seen.append(password_hash)
        return real(password_hash, password)

    monkeypatch.setattr(app_module, "check_password_hash", spy)
    response = client.post("/api/auth/login", json={"username": "ghost", "password": PASSWORD})
    assert response.status_code == 401
    assert seen == [app_module.DUMMY_HASH]


def test_token_identity_is_user_id_with_issuer_and_version(app: Flask, client: FlaskClient) -> None:
    token = signup(client)
    with app.app_context():
        user = db.session.query(User).one()
        claims = decode_token(token)
        assert claims["sub"] == str(user.id)
        assert claims["iss"] == "sahirep"
        assert claims["tv"] == user.token_version > 0  # random start, see register()


def test_logout_revokes_all_of_the_users_tokens(client: FlaskClient) -> None:
    first = signup(client)
    second = client.post(
        "/api/auth/login", json={"username": "simar", "password": PASSWORD}
    ).json["access_token"]
    assert client.get("/api/history", headers=bearer(second)).status_code == 200

    # SECURITY_DESIGN 3.4: /logout drops one device; the old everywhere-logout is /logout-all
    assert client.post("/api/auth/logout-all", headers=bearer(first)).status_code == 200

    for token in (first, second):
        assert client.get("/api/history", headers=bearer(token)).status_code == 401
    fresh = client.post(
        "/api/auth/login", json={"username": "simar", "password": PASSWORD}
    ).json["access_token"]
    assert client.get("/api/history", headers=bearer(fresh)).status_code == 200


def test_deleted_user_and_legacy_username_tokens_get_401(app: Flask, client: FlaskClient) -> None:
    token = signup(client)
    with app.app_context():
        legacy = create_access_token(identity="simar")
        db.session.delete(db.session.query(User).one())
        db.session.commit()
    assert client.get("/api/history", headers=bearer(token)).status_code == 401
    assert client.get("/api/history", headers=bearer(legacy)).status_code == 401
    assert client.get("/api/profile", headers=bearer(token)).status_code == 401


def test_errors_are_json_without_internals(app: Flask, client: FlaskClient) -> None:
    @app.get("/api/boom")
    def boom() -> str:
        msg = "secret internal detail"
        raise RuntimeError(msg)

    app.config["PROPAGATE_EXCEPTIONS"] = False
    missing = client.get("/api/nope")
    wrong_method = client.get("/api/auth/login")
    too_big = client.post(
        "/api/auth/login", data=b"x" * (17 * 1024), content_type="application/json"
    )
    crashed = client.get("/api/boom")

    assert [r.status_code for r in (missing, wrong_method, too_big, crashed)] == [404, 405, 413, 500]
    for r in (missing, wrong_method, too_big, crashed):
        assert r.is_json
        assert isinstance(r.json["error"], str)
    assert "secret" not in crashed.get_data(as_text=True)
    assert client.get("/api/history").json == {"error": "authentication required"}


def test_integrity_error_becomes_409_and_rolls_back(app: Flask, client: FlaskClient) -> None:
    @app.get("/api/race")
    def race() -> str:
        raise IntegrityError("insert", {}, Exception("UNIQUE"))

    response = client.get("/api/race")
    assert response.status_code == 409
    assert response.json == {"error": "conflict, please retry"}


def test_sqlite_pragmas_are_set(app: Flask) -> None:
    with app.app_context():
        pragma = lambda name: db.session.connection().exec_driver_sql(f"PRAGMA {name}").scalar()  # noqa: E731
        assert pragma("foreign_keys") == 1
        assert pragma("busy_timeout") == 5000
        assert pragma("journal_mode") == "wal"


def test_foreign_keys_are_enforced(app: Flask) -> None:
    with app.app_context():
        orphan = WorkoutSet(
            user_id=999, exercise_type="squat", rep_count=1, clean_count=1, attempted_count=0,
            formscore=1.0,
        )  # fmt: skip
        db.session.add(orphan)
        with pytest.raises(IntegrityError):
            db.session.commit()
        db.session.rollback()


def test_api_responses_have_security_headers(client: FlaskClient) -> None:
    response = client.get("/api/nope")
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["Cache-Control"] == "no-store"


def test_streak_uses_app_timezone_day_boundaries(
    app: Flask, client: FlaskClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    token = signup(client)
    with app.app_context():
        # 20:00 UTC on Jan 1 is already Jan 2 in Asia/Kolkata (+05:30)
        db.session.add(
            WorkoutSet(
                user_id=db.session.query(User).one().id, exercise_type="squat", rep_count=1,
                clean_count=1, attempted_count=0, formscore=1.0,
                timestamp=datetime(2026, 1, 1, 20, 0, tzinfo=UTC),
            )
        )  # fmt: skip
        db.session.commit()
    monkeypatch.setattr(app_module, "today_local", lambda: date(2026, 1, 3))
    assert client.get("/api/history", headers=bearer(token)).json["streak"] == 1


def test_invalid_timezone_fails_at_startup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("SAHIREP_TZ", "Mars/Olympus")
    with pytest.raises(InvalidTimezoneError):
        create_app(
            database_uri=f"sqlite:///{(tmp_path / 'tz.db').as_posix()}",
            jwt_secret="test-secret-at-least-32-characters-long",
            testing=True,
        )
