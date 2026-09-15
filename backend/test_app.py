from __future__ import annotations

import sqlite3
from contextlib import closing
from datetime import date, timedelta
from typing import TYPE_CHECKING

import pytest
from pydantic import BaseModel, ConfigDict
from werkzeug.security import check_password_hash

from app import calculate_streak, create_app, db

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient

USERNAME = "simar"
PASSWORD = "strong-password-123"


class TokenResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    access_token: str


class SetResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: int
    exercise_type: str
    rep_count: int
    clean_count: int
    attempted_count: int
    formscore: float
    timestamp: str


class HistoryResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    streak: int
    sets: list[SetResponse]


@pytest.fixture
def database_path(tmp_path: Path) -> Path:
    return tmp_path / "sahirep-test.db"


@pytest.fixture
def app(database_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{database_path.as_posix()}",
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


def register(client: FlaskClient, username: str = USERNAME, password: str = PASSWORD) -> None:
    response = client.post(
        "/api/auth/register",
        json={"username": username, "password": password},
    )
    assert response.status_code == 201


def login(client: FlaskClient, username: str = USERNAME, password: str = PASSWORD) -> str:
    response = client.post(
        "/api/auth/login",
        json={"username": username, "password": password},
    )
    assert response.status_code == 200
    return TokenResponse.model_validate_json(response.get_data()).access_token


def authorization(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def valid_set_payload() -> dict[str, str | int | float]:
    return {
        "exercise_type": "squat",
        "rep_count": 10,
        "clean_count": 8,
        "attempted_count": 2,
        "formscore": 80.0,
    }


def test_register_hashes_password(client: FlaskClient, database_path: Path) -> None:
    # Given a new account payload
    # When the account is registered
    register(client)

    # Then SQLite stores a verifiable hash, never the plaintext password
    with closing(sqlite3.connect(database_path)) as connection:
        row = connection.execute(
            "SELECT password_hash FROM users WHERE username = ?",
            (USERNAME,),
        ).fetchone()
    assert row is not None
    password_hash = str(row[0])
    assert password_hash != PASSWORD
    assert check_password_hash(password_hash, PASSWORD)


def test_duplicate_registration_is_rejected(client: FlaskClient) -> None:
    # Given an existing account
    register(client)

    # When the same normalized username is registered again
    response = client.post(
        "/api/auth/register",
        json={"username": "SIMAR", "password": PASSWORD},
    )

    # Then the API reports a conflict
    assert response.status_code == 409


def test_login_returns_jwt_and_rejects_wrong_password(client: FlaskClient) -> None:
    # Given an existing account
    register(client)

    # When valid and invalid credentials are submitted
    token = login(client)
    rejected = client.post(
        "/api/auth/login",
        json={"username": USERNAME, "password": "wrong-password"},
    )

    # Then valid credentials receive a token and invalid credentials do not
    assert token
    assert rejected.status_code == 401


def test_submit_set_requires_jwt(client: FlaskClient) -> None:
    # Given a valid completed-set payload without an access token
    # When the set is submitted
    response = client.post("/api/sets", json=valid_set_payload())

    # Then the protected endpoint rejects it
    assert response.status_code == 401


def test_malformed_json_returns_client_error(client: FlaskClient) -> None:
    # Given malformed JSON bytes from a command-line client
    # When the register endpoint parses the request
    response = client.post(
        "/api/auth/register",
        data=b"{username:not-json}",
        content_type="application/json",
    )

    # Then validation reports a client error instead of crashing the server
    assert response.status_code == 400


def test_submit_set_appears_in_owners_history(client: FlaskClient) -> None:
    # Given an authenticated user and a valid completed set
    register(client)
    token = login(client)

    # When the set is submitted and history is fetched
    submitted = client.post(
        "/api/sets",
        headers=authorization(token),
        json=valid_set_payload(),
    )
    history_response = client.get("/api/history", headers=authorization(token))

    # Then the exact aggregate reaches persistent history with an active streak
    assert submitted.status_code == 201
    assert history_response.status_code == 200
    history = HistoryResponse.model_validate_json(history_response.get_data())
    assert history.streak == 1
    assert len(history.sets) == 1
    assert history.sets[0].exercise_type == "squat"
    assert history.sets[0].rep_count == 10
    assert history.sets[0].clean_count == 8
    assert history.sets[0].attempted_count == 2
    assert history.sets[0].formscore == 80.0


def test_history_is_isolated_per_user(client: FlaskClient) -> None:
    # Given Alice has submitted a set and Bob has submitted none
    register(client, "alice")
    alice_token = login(client, "alice")
    _ = client.post(
        "/api/sets",
        headers=authorization(alice_token),
        json=valid_set_payload(),
    )
    register(client, "bob")
    bob_token = login(client, "bob")

    # When Bob fetches history
    response = client.get("/api/history", headers=authorization(bob_token))

    # Then Alice's set is not exposed
    history = HistoryResponse.model_validate_json(response.get_data())
    assert history.streak == 0
    assert history.sets == []


def test_streak_counts_consecutive_days_and_expires_after_a_gap() -> None:
    # Given consecutive workout days and a stale workout day
    today = date(2026, 9, 14)
    consecutive = [today, today - timedelta(days=1), today - timedelta(days=2)]
    stale = [today - timedelta(days=2)]

    # When each current streak is calculated
    active_streak = calculate_streak(consecutive, today)
    expired_streak = calculate_streak(stale, today)

    # Then consecutive days count and a two-day gap resets the streak
    assert active_streak == 3
    assert expired_streak == 0


@pytest.mark.parametrize(
    "payload",
    [
        {**valid_set_payload(), "exercise_type": "deadlift"},
        {**valid_set_payload(), "rep_count": 11},
        {**valid_set_payload(), "pose_landmarks": [1, 2, 3]},
        {**valid_set_payload(), "video": "never accepted"},
    ],
)
def test_submit_rejects_invalid_or_pose_data(
    client: FlaskClient,
    payload: dict[str, str | int | float | list[int]],
) -> None:
    # Given an authenticated user and invalid, pose, or video data
    register(client)
    token = login(client)

    # When the payload is submitted
    response = client.post("/api/sets", headers=authorization(token), json=payload)

    # Then nothing outside the completed-set contract is accepted
    assert response.status_code == 400
