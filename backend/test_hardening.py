"""Regression tests for the audit hardening: pagination, bounds, dates, importer, query counts."""

from __future__ import annotations

import json
from datetime import timedelta
from typing import TYPE_CHECKING, Any

import pytest
from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app import create_app, db, today_local
from catalog import Exercise, Machine, MuscleGroup, Source, import_catalog
from nutrition import Food, FoodLog, FoodServing, calorie_target, import_foods
from plans import PlanTemplate, generate_plan, import_plans

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient

PROFILE = {
    "sex": "male", "birth_year": 1996, "height_cm": 180, "weight_kg": 80,
    "activity_level": "moderate", "goal": "maintain",
}  # fmt: skip
QUERY_BUDGET = 6


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'hardening-test.db').as_posix()}",
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
def h(client: FlaskClient) -> dict[str, str]:
    body = {"username": "simar", "password": "strong-password-123"}
    client.post("/api/auth/register", json=body)
    token = client.post("/api/auth/login", json=body).get_json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


def count_queries(app: Flask, client: FlaskClient, url: str) -> tuple[int, Any]:
    seen: list[str] = []

    def hook(*args: Any) -> None:  # noqa: ANN401
        seen.append(args[2])

    with app.app_context():
        engine = db.engine
    event.listen(engine, "before_cursor_execute", hook)
    try:
        response = client.get(url)
    finally:
        event.remove(engine, "before_cursor_execute", hook)
    assert response.status_code == 200
    return len(seen), response.get_json()


def seed_catalog(n: int) -> None:
    """TEST FIXTURE ONLY: placeholder rows."""
    src = Source(title="Test Source")
    muscles = [MuscleGroup(name=n) for n in ("quadriceps", "m1", "m2", "m3")]
    machine = Machine(name="Test Machine", type="free-weight")
    db.session.add_all([src, machine, *muscles])
    for i in range(n):
        db.session.add(Exercise(
            name=f"ex {i:03}", slug=f"ex-{i:03}", difficulty="beginner",
            movement_pattern="squat", machine=machine if i % 2 else None,
            primary_muscles=[muscles[i % 4]], secondary_muscles=[muscles[(i + 1) % 4]],
            sources=[src],
        ))  # fmt: skip
        food = Food(
            name=f"food {i:03}", slug=f"food-{i:03}", kcal=100, protein_g=1, carbs_g=1,
            fat_g=1, fiber_g=1, sources=[src],
            servings=[FoodServing(label="cup", grams=100), FoodServing(label="bowl", grams=200)],
        )  # fmt: skip
        db.session.add(food)
    db.session.commit()


# ---- pagination + query counts ----


def test_list_endpoints_are_paged_slim_and_constant_query(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        seed_catalog(150)
        import_plans({"plans": [generate_plan("beginner", "general", 3)]})

    n, foods = count_queries(app, client, "/api/foods?limit=100")
    assert n <= QUERY_BUDGET
    assert (foods["total"], foods["limit"], foods["offset"], len(foods["items"])) == (150, 100, 0, 100)
    assert "servings" not in foods["items"][0]
    assert "sources" not in foods["items"][0]
    assert {"name", "slug", "kcal", "protein_g", "verified"} <= set(foods["items"][0])

    n, exercises = count_queries(app, client, "/api/exercises?limit=100")
    assert n <= QUERY_BUDGET
    assert (exercises["total"], len(exercises["items"])) == (150, 100)
    assert not {"instructions", "sources", "secondary_muscles"} & set(exercises["items"][0])
    assert exercises["items"][0]["primary_muscles"]

    _, tail = count_queries(app, client, "/api/exercises?limit=100&offset=100")
    assert [e["slug"] for e in tail["items"]] == [f"ex-{i:03}" for i in range(100, 150)]

    _, plans = count_queries(app, client, "/api/plans")
    assert plans["total"] == 1
    assert "days" not in plans["items"][0]
    detail = client.get(f"/api/plans/{plans['items'][0]['slug']}").get_json()
    assert detail["days"]  # detail keeps full data
    assert "servings" in client.get("/api/foods/food-000").get_json()
    assert "instructions" in client.get("/api/exercises/ex-000").get_json()


def test_limit_clamped_and_junk_rejected(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        seed_catalog(3)
    assert client.get("/api/foods?limit=9999").get_json()["limit"] == 100
    for bad in ("limit=0", "limit=-1", "limit=abc", "offset=-1", f"offset={10**30}"):
        assert client.get(f"/api/foods?{bad}").status_code == 400, bad


def test_resolve_plan_queries_do_not_scale_with_catalog(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        seed_catalog(40)
        import_plans({"plans": [generate_plan("beginner", "general", 3)]})
    small, _ = count_queries(app, client, "/api/plans/builtin-beginner-general-3d")
    with app.app_context():
        for i in range(40, 140):
            db.session.add(Exercise(name=f"x{i}", slug=f"x-{i}", difficulty="beginner",
                                    movement_pattern="squat"))  # fmt: skip
        db.session.commit()
    big, body = count_queries(app, client, "/api/plans/builtin-beginner-general-3d")
    assert big == small <= 12  # noqa: PLR2004
    assert body["days"][0]["slots"][0]["exercise"] is not None


def test_history_and_weight_keep_shape_but_accept_paging(
    client: FlaskClient, h: dict[str, str]
) -> None:
    payload = {"exercise_type": "squat", "rep_count": 2, "clean_count": 1, "attempted_count": 1,
               "formscore": 90}  # fmt: skip
    for _ in range(3):
        client.post("/api/sets", json=payload, headers=h)
    body = client.get("/api/history?limit=2", headers=h).get_json()
    assert set(body) == {"streak", "sets"}
    assert (len(body["sets"]), body["streak"]) == (2, 1)
    assert client.get("/api/history?limit=2&offset=2", headers=h).get_json()["sets"].__len__() == 1
    today = today_local()
    for back in (2, 1, 0):
        day = (today - timedelta(days=back)).isoformat()
        client.post("/api/weight", json={"kg": 70 + back, "date": day}, headers=h)
    rows = client.get("/api/weight?limit=2", headers=h).get_json()
    assert isinstance(rows, list)
    assert [r["kg"] for r in rows] == [71, 70]  # newest page, still oldest-first
    assert len(client.get("/api/weight", headers=h).get_json()) == 3  # noqa: PLR2004


# ---- numeric bounds, ids, dates ----


@pytest.mark.parametrize(
    "body",
    [
        {"name": "x", "kcal": 10001},
        {"name": "x", "kcal": 1e308},
        {"name": "x", "kcal": 1, "protein_g": 2001},
        {"name": "x", "kcal": 1, "fat_g": -1},
        {"food_slug": "f", "quantity_g": 5001},
        {"food_slug": "f", "quantity_g": 0.5},
        {"food_slug": "f", "serving_id": 10**30},
    ],
)
def test_log_numeric_bounds(client: FlaskClient, h: dict[str, str], body: dict[str, Any]) -> None:
    r = client.post("/api/nutrition/log", json={"meal": "lunch", **body}, headers=h)
    assert r.status_code == 400


@pytest.mark.parametrize("raw", ['{"name":"x","kcal":NaN,"meal":"lunch"}',
                                 '{"name":"x","kcal":Infinity,"meal":"lunch"}'])
def test_nan_and_infinity_rejected(client: FlaskClient, h: dict[str, str], raw: str) -> None:
    r = client.post("/api/nutrition/log", data=raw, headers=h, content_type="application/json")
    assert r.status_code == 400


@pytest.mark.parametrize(
    ("path", "body", "bad"),
    [
        ("/api/profile", PROFILE | {"weight_kg": 19}, True),
        ("/api/profile", PROFILE | {"weight_kg": 301}, True),
        ("/api/profile", PROFILE | {"height_cm": 99}, True),
        ("/api/profile", PROFILE | {"height_cm": 251}, True),
        ("/api/profile", PROFILE | {"birth_year": 1899}, True),
        ("/api/profile", PROFILE | {"birth_year": today_local().year + 1}, True),
        ("/api/profile", PROFILE | {"weight_kg": 20, "height_cm": 100}, False),
        ("/api/weight", {"kg": 19.9}, True),
        ("/api/weight", {"kg": 300.1}, True),
        ("/api/weight", {"kg": 1e308}, True),
        ("/api/sets", {"exercise_type": "squat", "rep_count": 1001, "clean_count": 1001,
                       "attempted_count": 0, "formscore": 50}, True),
    ],
)  # fmt: skip
def test_payload_bounds(
    client: FlaskClient, h: dict[str, str], path: str, body: dict[str, Any], bad: bool  # noqa: FBT001
) -> None:
    method = client.put if path == "/api/profile" else client.post
    assert (method(path, json=body, headers=h).status_code == 400) is bad


def test_huge_path_ids_are_404_not_500(client: FlaskClient, h: dict[str, str]) -> None:
    big = "99999999999999999999"
    assert client.delete(f"/api/nutrition/log/{big}", headers=h).status_code == 404
    assert client.patch(f"/api/nutrition/log/{big}", json={}, headers=h).status_code == 404
    assert client.delete(f"/api/weight/{big}", headers=h).status_code == 404
    assert client.get(f"/api/machines/{big}").status_code == 404
    assert client.get(f"/api/plans?days={big}").status_code == 400


def test_dates_limited_to_a_year_around_today(client: FlaskClient, h: dict[str, str]) -> None:
    far = (today_local() + timedelta(days=800)).isoformat()
    old = (today_local() - timedelta(days=800)).isoformat()
    ok = (today_local() - timedelta(days=300)).isoformat()
    quick = {"meal": "lunch", "name": "x", "kcal": 1}
    for bad in (far, old):
        assert client.post("/api/nutrition/log", json=quick | {"date": bad}, headers=h).status_code == 400
        assert client.get(f"/api/nutrition/log?date={bad}", headers=h).status_code == 400
        assert client.get(f"/api/nutrition/day?date={bad}", headers=h).status_code == 400
        assert client.post("/api/weight", json={"kg": 70, "date": bad}, headers=h).status_code == 400
        assert client.post("/api/me/plan", json={"slug": "p", "start_date": bad}, headers=h).status_code == 400
        assert client.post("/api/me/plan/complete-day", json={"date": bad}, headers=h).status_code == 400
    assert client.post("/api/nutrition/log", json=quick | {"date": ok}, headers=h).status_code == 201
    assert client.get(f"/api/nutrition/log?date={ok}", headers=h).status_code == 200


def test_day_totals_stay_finite_and_targets_non_negative(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    client.put("/api/profile", json=PROFILE, headers=h)
    with app.app_context():
        for _ in range(2):  # legacy rows written before the bounds existed
            db.session.add(FoodLog(user_id=1, meal="lunch", date=today_local(), name="x",
                                   kcal=1.5e308, protein_g=0, carbs_g=0, fat_g=0))  # fmt: skip
        db.session.commit()
    raw = client.get("/api/nutrition/day", headers=h).get_data(as_text=True)

    def no_constants(name: str) -> None:
        raise AssertionError(name)

    body = json.loads(raw, parse_constant=no_constants)  # Infinity/NaN would raise
    assert body["totals"]["kcal"] == 0
    assert calorie_target(-5000, "maintain") == 0
    assert calorie_target(-5000, "gain") == 0


def test_food_entry_rejects_macros(app: Flask, client: FlaskClient, h: dict[str, str]) -> None:
    with app.app_context():
        seed_catalog(1)
    base = {"meal": "lunch", "food_slug": "food-000", "quantity_g": 100}
    assert client.post("/api/nutrition/log", json=base, headers=h).status_code == 201
    for extra in ({"kcal": 5}, {"protein_g": 0}, {"carbs_g": 3}, {"fat_g": 1}):
        assert client.post("/api/nutrition/log", json=base | extra, headers=h).status_code == 400


# ---- importer ----


def test_catalog_import_rejects_non_list_sections(app: Flask) -> None:
    with app.app_context():
        report = import_catalog({"muscles": "chest", "sources": {"a": 1}, "exercises": "x"})
        assert [(e["section"], e["index"]) for e in report["errors"]] == [
            ("muscles", None), ("sources", None), ("exercises", None),
        ]  # fmt: skip
        assert db.session.scalars(select(MuscleGroup)).all() == []


def test_catalog_import_length_and_url_rules(app: Flask) -> None:
    with app.app_context():
        report = import_catalog({
            "muscles": ["m" * 65],
            "sources": [
                {"title": "t" * 257},
                {"title": "js", "url": "javascript:alert(1)"},
                {"title": "ftp", "url": "ftp://example.com/x"},
                {"title": "nohost", "url": "http://"},
                {"title": "ok", "url": "https://example.com/paper"},
            ],
        })  # fmt: skip
        assert [(e["section"], e["index"]) for e in report["errors"]] == [
            ("muscles", 0), ("sources", 0), ("sources", 1), ("sources", 2), ("sources", 3),
        ]  # fmt: skip
        assert db.session.scalars(select(Source.title)).all() == ["ok"]
        foods = import_foods({"foods": [{
            "name": "n" * 129, "slug": "a", "kcal": 1, "protein_g": 1, "carbs_g": 1,
            "fat_g": 1, "fiber_g": 1,
        }]})  # fmt: skip
        assert len(foods["errors"]) == 1
        plans = import_plans({"plans": {"a": 1}})
        assert plans["errors"][0]["error"] == "must be a list"


def test_muscle_created_count_only_after_savepoint_succeeds(app: Flask) -> None:
    doc = {"exercises": [{"name": "A", "slug": "a", "difficulty": "beginner",
                          "primary_muscles": ["chest"]}]}  # fmt: skip

    def fail_on_exercise(session: Session, *_: Any) -> None:  # noqa: ANN401
        if any(isinstance(o, Exercise) for o in session.new):
            msg = "injected flush failure"
            raise SQLAlchemyError(msg)

    with app.app_context():
        event.listen(Session, "before_flush", fail_on_exercise)
        try:
            failed = import_catalog(doc)
        finally:
            event.remove(Session, "before_flush", fail_on_exercise)
        assert failed["errors"] and failed["created"]["muscles"] == 0
        assert failed["created"]["exercises"] == 0
        assert db.session.scalars(select(MuscleGroup)).all() == []
        ok = import_catalog(doc)
        assert (ok["created"]["muscles"], ok["created"]["exercises"], ok["errors"]) == (1, 1, [])


@pytest.mark.parametrize("command", ["import-foods", "import-plans", "import-catalog"])
def test_import_cli_bad_files_are_click_errors(app: Flask, tmp_path: Path, command: str) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    binary = tmp_path / "bin.json"
    binary.write_bytes(b"\xff\xfe\x00")
    for path in (bad, binary):
        result = app.test_cli_runner().invoke(args=[command, str(path)])
        assert result.exit_code == 1
        assert "cannot read" in result.output
        assert isinstance(result.exception, SystemExit)  # ClickException, not a traceback


def test_plan_template_untouched_by_list_endpoint(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        import_plans({"plans": [generate_plan("beginner", "general", 2)]})
    assert client.get("/api/plans").get_json()["items"][0]["slug"] == "builtin-beginner-general-2d"
    with app.app_context():
        assert db.session.scalar(select(PlanTemplate)) is not None
