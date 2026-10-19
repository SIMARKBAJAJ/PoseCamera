from __future__ import annotations

from datetime import UTC, date, datetime
from typing import TYPE_CHECKING

import pytest
from sqlalchemy import event, func, select, update

from app import User, WorkoutSet, create_app, db
from catalog import Exercise, MuscleGroup
from nutrition import FoodLog, UserProfile, WeightLog
from plans import PlanCompletion, PlanTemplate, UserPlan

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient

PASSWORD = "strong-password-123"


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'me.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    with application.app_context():
        chest, quads = MuscleGroup(name="chest"), MuscleGroup(name="quadriceps")
        db.session.add_all(
            [
                Exercise(name="Bench", slug="bench", difficulty="beginner", primary_muscles=[chest]),
                Exercise(name="Squat", slug="bodyweight-squat", difficulty="beginner",
                         primary_muscles=[quads], has_pose_tracking=True),
                Exercise(name="Pushups", slug="pushups", difficulty="beginner",
                         primary_muscles=[chest], has_pose_tracking=True),
            ]
        )
        db.session.commit()
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


def signup(client: FlaskClient, name: str = "simar", **extra: object) -> dict[str, str]:
    body = {"username": name, "password": PASSWORD, **extra}
    assert client.post("/api/auth/register", json=body).status_code == 201
    token = client.post("/api/auth/login", json={"username": name, "password": PASSWORD}).json[
        "access_token"
    ]
    return {"Authorization": f"Bearer {token}"}


def lift(client: FlaskClient, h: dict[str, str], slug: str = "bench", load: float = 100, reps: int = 5,
         **extra: object):
    return client.post(
        "/api/sets",
        headers=h,
        json={"exercise_type": "other", "exercise_slug": slug, "load_kg": load, "rep_count": reps,
              "clean_count": reps, "attempted_count": 0, "formscore": 0, **extra},
    )


def test_old_client_contract_unchanged(client: FlaskClient) -> None:
    h = signup(client)
    old = {"exercise_type": "squat", "rep_count": 5, "clean_count": 4, "attempted_count": 1,
           "formscore": 80}
    r = client.post("/api/sets", headers=h, json=old)
    assert r.status_code == 201
    assert r.json["exercise_slug"] is None
    assert r.json["load_kg"] is None
    hist = client.get("/api/history", headers=h).json
    assert hist["sets"][0]["exercise_type"] == "squat"
    assert hist["sets"][0]["rpe"] is None


def test_set_validation(client: FlaskClient) -> None:
    h = signup(client)
    assert lift(client, h, rpe=8.5, set_type="drop").status_code == 201
    assert lift(client, h, slug="nope").status_code == 400  # unknown slug
    assert lift(client, h, slug="pushups").status_code == 400  # pose-tracked is not 'other'
    for bad in ({"load_kg": -1}, {"load_kg": 1001}, {"rpe": 0.5}, {"rpe": 11}, {"set_type": "x"}):
        assert lift(client, h, **bad).status_code == 400
    base = {"rep_count": 1, "clean_count": 1, "attempted_count": 0, "formscore": 1}
    no_slug = {"exercise_type": "other", **base}
    assert client.post("/api/sets", headers=h, json=no_slug).status_code == 400
    clash = {"exercise_type": "squat", "exercise_slug": "bench", **base}
    assert client.post("/api/sets", headers=h, json=clash).status_code == 400
    ok = {"exercise_type": "squat", "exercise_slug": "bodyweight-squat", **base}
    assert client.post("/api/sets", headers=h, json=ok).status_code == 201


def test_other_sets_need_no_form_fields_but_pose_sets_do(client: FlaskClient) -> None:
    h = signup(client)
    bare = {"exercise_type": "other", "exercise_slug": "bench", "rep_count": 5, "load_kg": 60}
    r = client.post("/api/sets", headers=h, json=bare)
    assert r.status_code == 201
    assert (r.json["clean_count"], r.json["attempted_count"], r.json["formscore"]) == (None,) * 3
    hist = client.get("/api/history", headers=h).json
    assert hist["streak"] == 1  # a form-less set still counts as a workout day
    assert {"clean_count", "attempted_count", "formscore"} <= hist["sets"][0].keys()
    for missing in ("clean_count", "attempted_count", "formscore"):
        full = {"exercise_type": "squat", "rep_count": 5, "clean_count": 4, "attempted_count": 1,
                "formscore": 80}
        del full[missing]
        assert client.post("/api/sets", headers=h, json=full).status_code == 400
    wrong = {**bare, "clean_count": 1, "attempted_count": 1}
    assert client.post("/api/sets", headers=h, json=wrong).status_code == 400  # sums stay checked


def test_prs_epley_and_heaviest(client: FlaskClient) -> None:
    h = signup(client)
    lift(client, h, load=100, reps=5)  # 116.7
    lift(client, h, load=90, reps=10)  # 120.0 -> best e1rm
    lift(client, h, load=120, reps=1)  # heaviest (e1rm 124.0 actually best)
    lift(client, h, load=200, reps=20)  # reps > 12: heaviest only
    items = client.get("/api/me/prs", headers=h).json["items"]
    assert len(items) == 1
    assert items[0]["heaviest_kg"] == 200
    assert items[0]["best_e1rm"]["load_kg"] == 120
    assert items[0]["best_e1rm"]["e1rm_kg"] == 124.0


def test_prs_and_volume_query_count_is_constant(app: Flask, client: FlaskClient) -> None:
    h = signup(client)

    def measure(url: str) -> int:
        n = []
        def hook(*_a: object) -> None:
            n.append(1)
        with app.app_context():
            event.listen(db.engine, "before_cursor_execute", hook)
        try:
            assert client.get(url, headers=h).status_code == 200
        finally:
            with app.app_context():
                event.remove(db.engine, "before_cursor_execute", hook)
        return len(n)

    lift(client, h)
    first = (measure("/api/me/prs"), measure("/api/me/volume"))
    for i in range(20):
        lift(client, h, load=50 + i)
    client.post("/api/sets", headers=h, json={"exercise_type": "squat", "exercise_slug": "bodyweight-squat",
                "load_kg": 20, "rep_count": 3, "clean_count": 3, "attempted_count": 0, "formscore": 9})
    second = (measure("/api/me/prs"), measure("/api/me/volume"))
    assert first == second


def test_volume_excludes_warmups_and_buckets_by_muscle(client: FlaskClient) -> None:
    h = signup(client)
    lift(client, h)
    lift(client, h)
    lift(client, h, set_type="warmup")
    items = client.get("/api/me/volume?weeks=1", headers=h).json["items"]
    assert [(i["muscle"], i["hard_sets"]) for i in items] == [("chest", 2)]
    assert items[0]["week"][4:6] == "-W"
    for bad in ("0", "27", "x"):
        assert client.get(f"/api/me/volume?weeks={bad}", headers=h).status_code == 400


def _populate(app: Flask, uid: int, tag: int) -> None:
    db.session.add(WorkoutSet(user_id=uid, exercise_type="squat", rep_count=1, clean_count=1,
                              attempted_count=0, formscore=1))
    db.session.add(FoodLog(user_id=uid, name="x", kcal=1, meal="lunch", date=date(2026, 1, tag)))
    db.session.add(WeightLog(user_id=uid, date=date(2026, 1, tag), kg=70))
    db.session.add(UserProfile(user_id=uid, sex="male", birth_year=1990, height_cm=170,
                               weight_kg=70, activity_level="light", goal="maintain"))
    plan = PlanTemplate(slug=f"p{tag}", name="p", level="beginner", goal="strength",
                        days_per_week=3, split="full_body")
    db.session.add(plan)
    db.session.flush()
    up = UserPlan(user_id=uid, plan_id=plan.id, start_date=date(2026, 1, 1), equipment="gym")
    db.session.add(up)
    db.session.flush()
    db.session.add(PlanCompletion(user_plan_id=up.id, day_index=0, completed_on=date(2026, 1, tag)))
    db.session.commit()


def _rows_for(uid: int) -> dict[str, int]:
    def n(model: type, col: object) -> int:
        return db.session.scalar(select(func.count()).select_from(model).where(col == uid))

    up_ids = select(UserPlan.id).where(UserPlan.user_id == uid)
    return {
        "sets": n(WorkoutSet, WorkoutSet.user_id), "food": n(FoodLog, FoodLog.user_id),
        "weight": n(WeightLog, WeightLog.user_id), "profile": n(UserProfile, UserProfile.user_id),
        "plans": n(UserPlan, UserPlan.user_id), "users": n(User, User.id),
        "completions": db.session.scalar(
            select(func.count()).select_from(PlanCompletion).where(PlanCompletion.user_plan_id.in_(up_ids))
        ),
    }


def test_delete_wrong_password_deletes_nothing(app: Flask, client: FlaskClient) -> None:
    h = signup(client)
    with app.app_context():
        uid = db.session.scalar(select(User.id))
        _populate(app, uid, 1)
    r = client.delete("/api/me", headers=h, json={"password": "wrong-password-1"})
    assert r.status_code == 401
    with app.app_context():
        assert all(v == 1 for v in _rows_for(uid).values())
    assert client.delete("/api/me", headers=h, json={}).status_code == 400


def test_delete_removes_everything_and_spares_others(app: Flask, client: FlaskClient) -> None:
    h1, h2 = signup(client, "alice"), signup(client, "bobby")
    with app.app_context():
        a, b = (db.session.scalar(select(User.id).where(User.username == u)) for u in ("alice", "bobby"))
        _populate(app, a, 1)
        _populate(app, b, 2)
    r = client.delete("/api/me", headers=h1, json={"password": PASSWORD})
    assert r.status_code == 204
    assert client.get("/api/history", headers=h1).status_code == 401  # token dies
    with app.app_context():
        assert all(v == 0 for v in _rows_for(a).values())
        assert all(v == 1 for v in _rows_for(b).values())
    assert client.get("/api/history", headers=h2).status_code == 200


def test_export_has_every_table_and_no_hash(app: Flask, client: FlaskClient) -> None:
    h = signup(client, consent=True)
    with app.app_context():
        _populate(app, db.session.scalar(select(User.id)), 3)
    r = client.get("/api/me/export", headers=h)
    assert r.status_code == 200
    assert r.headers["Content-Disposition"].startswith("attachment")
    body = r.json
    for key in ("profile", "workout_sets", "food_logs", "weight_logs", "user_plans", "plan_completions"):
        assert len(body[key]) == 1, key
    assert body["user"]["username"] == "simar"
    assert body["user"]["consent_at"]
    assert "password" not in r.get_data(as_text=True)


def test_consent_is_optional_and_recorded(app: Flask, client: FlaskClient) -> None:
    signup(client, "noconsent")
    signup(client, "yesconsent", consent=True)
    with app.app_context():
        got = {u.username: u.consent_at for u in db.session.scalars(select(User))}
    assert got["noconsent"] is None
    assert isinstance(got["yesconsent"], datetime)
    assert datetime.now(UTC).year >= 2026  # sanity: stored value is a real timestamp


def test_deleted_users_token_dies_when_sqlite_reuses_the_id(app: Flask, client: FlaskClient) -> None:
    a_headers = signup(client, "alice")
    with app.app_context():
        a_id = db.session.scalar(select(User.id))
    r = client.delete("/api/me", headers=a_headers, json={"password": PASSWORD})
    assert r.status_code == 204
    assert r.data == b""
    assert not r.content_type  # no html content type on an empty 204
    b_headers = signup(client, "bobby")
    with app.app_context():
        assert db.session.scalar(select(User.id)) == a_id  # id reused
    assert client.get("/api/history", headers=a_headers).status_code == 401
    assert client.get("/api/history", headers=b_headers).status_code == 200


def test_other_set_with_pose_slug_rejected_even_if_flag_unset(app: Flask, client: FlaskClient) -> None:
    with app.app_context():  # the live DB has has_pose_tracking=0 for these two
        db.session.execute(update(Exercise).values(has_pose_tracking=False))
        db.session.commit()
    h = signup(client)
    assert lift(client, h, slug="pushups").status_code == 400
    assert lift(client, h, slug="bodyweight-squat").status_code == 400
    assert lift(client, h, slug="bench").status_code == 201
    legacy = {"exercise_type": "push-up", "rep_count": 5, "clean_count": 4, "attempted_count": 1,
              "formscore": 80}
    assert client.post("/api/sets", headers=h, json=legacy).status_code == 201
    assert client.post("/api/sets", headers=h, json={**legacy, "exercise_slug": "pushups"}).status_code == 201
