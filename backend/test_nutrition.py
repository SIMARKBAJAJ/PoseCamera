from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING, Any

import pytest

from app import create_app, db
from nutrition import (
    Food,
    bmr_mifflin_st_jeor,
    calorie_target,
    exercise_kcal,
    import_foods,
    macro_targets,
    protein_target_g,
    tdee,
)

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient

DAY = "2026-01-05"
PROFILE = {
    "sex": "male",
    "birth_year": 1996,
    "height_cm": 180,
    "weight_kg": 80,
    "activity_level": "moderate",
    "goal": "maintain",
}

# TEST FIXTURE ONLY: made-up round numbers to exercise the importer, not real food data.
FIXTURE: dict[str, Any] = {
    "sources": [{"title": "Test Source", "year": 2000}],
    "foods": [
        {
            "name": "Test Food A", "name_local": "टेस्ट", "slug": "test-a", "category": "grain",
            "region": "north-indian", "kcal": 200, "protein_g": 10, "carbs_g": 30, "fat_g": 5,
            "fiber_g": 2, "verified": True, "sources": ["Test Source"],
            "servings": [{"label": "1 piece", "grams": 50}],
        },
        {
            "name": "Test Food B", "slug": "test-b", "category": "dairy", "region": "global",
            "kcal": 100, "protein_g": 4, "carbs_g": 6, "fat_g": 7, "fiber_g": 0, "verified": True,
        },
    ],
}  # fmt: skip


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'nutrition-test.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    with application.app_context():
        assert import_foods(FIXTURE)["errors"] == []
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


def auth(client: FlaskClient, username: str = "simar") -> dict[str, str]:
    body = {"username": username, "password": "strong-password-123"}
    client.post("/api/auth/register", json=body)
    token = client.post("/api/auth/login", json=body).get_json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


# ---- formulas (hand-computed) ----


def test_mifflin_male_and_female() -> None:
    # 10*80 + 6.25*180 - 5*30 + 5 = 800 + 1125 - 150 + 5
    assert bmr_mifflin_st_jeor("male", 80, 180, 30) == 1780
    # 10*60 + 6.25*165 - 5*25 - 161 = 600 + 1031.25 - 125 - 161
    assert bmr_mifflin_st_jeor("female", 60, 165, 25) == 1345.25


def test_tdee_factors() -> None:
    assert tdee(1780, "sedentary") == pytest.approx(2136)
    assert tdee(1780, "moderate") == pytest.approx(2759)
    assert tdee(1780, "very_active") == pytest.approx(3382)


def test_calorie_target_by_goal() -> None:
    assert calorie_target(2759, "maintain") == 2759
    assert calorie_target(2759, "lose") == 2345  # 2759 * 0.85 = 2345.15
    assert calorie_target(2759, "gain") == 2966  # 2759 * 1.075 = 2965.925


def test_protein_target() -> None:
    assert protein_target_g(80, "lose") == 160
    assert protein_target_g(80, "maintain") == 128
    assert protein_target_g(80, "gain") == 144


def test_macro_targets_fat_floor_and_carb_remainder() -> None:
    # fat = max(0.8*80=64, 0.2*2759/9=61.3) = 64; carbs = (2759 - 4*128 - 9*64) / 4 = 417.75
    m = macro_targets(2759, 80, "maintain")
    assert (m["protein_g"], m["fat_g"]) == (128, 64)
    assert m["carbs_g"] == pytest.approx(417.75)
    # energy-fraction floor wins at high kcal: 0.2*4500/9 = 100 > 64
    assert macro_targets(4500, 80, "maintain")["fat_g"] == pytest.approx(100)
    # carbs never negative
    assert macro_targets(500, 80, "lose")["carbs_g"] == 0


def test_exercise_kcal() -> None:
    assert exercise_kcal(8, 80, 30) == 320  # 8 * 80 kg * 0.5 h


# ---- importer ----


def test_import_is_idempotent(app: Flask) -> None:
    with app.app_context():
        second = import_foods(FIXTURE)
        assert second["created"] == {"sources": 0, "foods": 0}
        assert second["updated"] == {"sources": 1, "foods": 2}
        assert db.session.query(Food).count() == 2
        assert len(db.session.query(Food).filter_by(slug="test-a").one().servings) == 1


def test_import_row_errors_and_verified_rule(app: Flask) -> None:
    base = {"kcal": 1, "protein_g": 1, "carbs_g": 1, "fat_g": 1, "fiber_g": 1}
    rows = [
        {"name": "x", "slug": "Bad Slug", **base},
        {"name": "y", "slug": "y", **base, "sources": ["nope"]},
        {"name": "z", "slug": "z", **base, "kcal": -1},
        {"name": "w", "slug": "w", **base, "extra": 1},
        {"name": "ok", "slug": "ok", **base, "verified": True},  # no source -> stays unverified
    ]
    with app.app_context():
        report = import_foods({"foods": rows})
        assert [e["index"] for e in report["errors"]] == [0, 1, 2, 3]
        assert report["created"]["foods"] == 1
        assert db.session.query(Food).filter_by(slug="ok").one().verified is False
        assert db.session.query(Food).filter_by(slug="test-a").one().verified is True


# ---- public food endpoints ----


def test_foods_public_filters(client: FlaskClient) -> None:
    assert [f["slug"] for f in client.get("/api/foods").get_json()["items"]] == ["test-a", "test-b"]
    assert [f["slug"] for f in client.get("/api/foods?region=North-Indian").get_json()["items"]] == ["test-a"]
    assert [f["slug"] for f in client.get("/api/foods?category=dairy").get_json()["items"]] == ["test-b"]
    assert [f["slug"] for f in client.get("/api/foods?q=टेस्ट").get_json()["items"]] == ["test-a"]
    assert client.get("/api/foods/test-a").get_json()["servings"][0]["label"] == "1 piece"
    assert client.get("/api/foods/nope").status_code == 404


# ---- auth ----


@pytest.mark.parametrize(
    ("method", "url"),
    [
        ("put", "/api/profile"),
        ("get", "/api/profile"),
        ("get", "/api/nutrition/targets"),
        ("get", "/api/nutrition/day"),
        ("post", "/api/nutrition/log"),
        ("get", "/api/nutrition/log"),
        ("patch", "/api/nutrition/log/1"),
        ("delete", "/api/nutrition/log/1"),
        ("post", "/api/weight"),
        ("get", "/api/weight"),
        ("delete", "/api/weight/1"),
    ],
)
def test_requires_jwt(client: FlaskClient, method: str, url: str) -> None:
    assert getattr(client, method)(url).status_code == 401


# ---- profile and targets ----


def test_profile_and_targets(client: FlaskClient) -> None:
    h = auth(client)
    assert client.get("/api/profile", headers=h).status_code == 404
    assert client.get("/api/nutrition/targets", headers=h).status_code == 404
    assert client.put("/api/profile", json={**PROFILE, "goal": "bulk"}, headers=h).status_code == 400
    assert client.put("/api/profile", json=PROFILE, headers=h).status_code == 200
    assert client.put("/api/profile", json={**PROFILE, "goal": "lose"}, headers=h).status_code == 200
    assert client.get("/api/profile", headers=h).get_json()["goal"] == "lose"

    t = client.get("/api/nutrition/targets", headers=h).get_json()
    assert t["protein_g"] == 160
    assert t["fat_g"] > 0
    assert t["kcal"] > 0


# ---- food log CRUD ----


def log(client: FlaskClient, h: dict[str, str], **body: Any) -> Any:  # noqa: ANN401
    return client.post("/api/nutrition/log", json={"meal": "lunch", "date": DAY, **body}, headers=h)


def test_log_crud_and_recompute(client: FlaskClient) -> None:
    h = auth(client)
    # 150 g of test-a (200 kcal / 100 g) = 300 kcal, 15 P, 45 C, 7.5 F
    created = log(client, h, food_slug="test-a", quantity_g=150)
    assert created.status_code == 201
    entry = created.get_json()
    assert (entry["kcal"], entry["protein_g"], entry["carbs_g"], entry["fat_g"]) == (300, 15, 45, 7.5)

    # serving shortcut: 1 piece = 50 g = 100 kcal
    by_serving = log(client, h, food_slug="test-a", serving_id=1).get_json()
    assert (by_serving["quantity_g"], by_serving["kcal"], by_serving["serving"]) == (50, 100, "1 piece")

    # edit quantity recomputes; edit meal and date move the entry
    patched = client.patch(
        f"/api/nutrition/log/{entry['id']}", json={"quantity_g": 50, "meal": "dinner"}, headers=h
    ).get_json()
    assert (patched["kcal"], patched["meal"]) == (100, "dinner")
    client.patch(f"/api/nutrition/log/{by_serving['id']}", json={"date": "2026-01-06"}, headers=h)
    assert len(client.get(f"/api/nutrition/log?date={DAY}", headers=h).get_json()) == 1

    # food entries cannot carry their own kcal; unknown fields rejected
    assert client.patch(f"/api/nutrition/log/{entry['id']}", json={"kcal": 1}, headers=h).status_code == 400
    assert client.patch(f"/api/nutrition/log/{entry['id']}", json={"x": 1}, headers=h).status_code == 400

    assert client.delete(f"/api/nutrition/log/{entry['id']}", headers=h).status_code == 204
    assert client.get(f"/api/nutrition/log?date={DAY}", headers=h).get_json() == []


def test_log_validation(client: FlaskClient) -> None:
    h = auth(client)
    assert log(client, h, food_slug="test-a").status_code == 400  # no quantity
    assert log(client, h, food_slug="nope", quantity_g=10).status_code == 404
    assert log(client, h, food_slug="test-a", name="x", kcal=1).status_code == 400  # both
    assert log(client, h, name="x").status_code == 400  # quick add without kcal
    assert log(client, h, food_slug="test-b", serving_id=1).status_code == 400  # wrong food
    assert log(client, h, food_slug="test-a", quantity_g=10, meal="brunch").status_code == 400


def test_log_ownership(client: FlaskClient) -> None:
    mine, theirs = auth(client, "alice"), auth(client, "bobby")
    entry_id = log(client, mine, food_slug="test-a", quantity_g=100).get_json()["id"]
    url = f"/api/nutrition/log/{entry_id}"
    assert client.patch(url, json={"quantity_g": 1}, headers=theirs).status_code == 404
    assert client.delete(url, headers=theirs).status_code == 404
    assert client.get(f"/api/nutrition/log?date={DAY}", headers=theirs).get_json() == []
    assert client.delete(url, headers=mine).status_code == 204


# ---- day summary ----


def test_day_totals_and_remaining(client: FlaskClient) -> None:
    h = auth(client)
    client.put("/api/profile", json=PROFILE, headers=h)
    log(client, h, food_slug="test-a", quantity_g=150, meal="breakfast")  # 300 kcal, 15 P
    log(client, h, food_slug="test-b", quantity_g=200, meal="lunch")  # 200 kcal, 8 P
    quick = log(client, h, name="Mystery thali", kcal=500, protein_g=20, meal="dinner")
    assert quick.get_json()["name"] == "Mystery thali"
    log(client, h, food_slug="test-a", quantity_g=100, date="2026-01-06")  # other day, excluded

    day = client.get(f"/api/nutrition/day?date={DAY}", headers=h).get_json()
    assert day["totals"] == {"kcal": 1000, "protein_g": 43, "carbs_g": 57, "fat_g": 21.5}
    assert day["meals"]["breakfast"]["totals"]["kcal"] == 300
    assert day["meals"]["snack"] == {"entries": [], "totals": dict.fromkeys(day["totals"], 0)}
    assert [e["name"] for e in day["meals"]["dinner"]["entries"]] == ["Mystery thali"]
    # targets use age = 2026 - 1996 = 30 -> BMR 1780, TDEE 2759, protein 128
    assert day["targets"]["kcal"] == 2759
    assert day["remaining"] == {"kcal": 1759, "protein_g": 85}

    # editing the quantity changes the day totals
    first = client.get(f"/api/nutrition/log?date={DAY}", headers=h).get_json()[0]
    client.patch(f"/api/nutrition/log/{first['id']}", json={"quantity_g": 100}, headers=h)
    assert client.get(f"/api/nutrition/day?date={DAY}", headers=h).get_json()["totals"]["kcal"] == 900


def test_day_without_profile_has_no_targets(client: FlaskClient) -> None:
    h = auth(client)
    day = client.get(f"/api/nutrition/day?date={DAY}", headers=h).get_json()
    assert day["targets"] is None
    assert day["remaining"] is None
    assert client.get("/api/nutrition/day?date=nope", headers=h).status_code == 400


# ---- weight ----


def test_weight_log(client: FlaskClient) -> None:
    mine, theirs = auth(client, "alice"), auth(client, "bobby")
    first = client.post("/api/weight", json={"kg": 80.5, "date": DAY}, headers=mine).get_json()
    client.post("/api/weight", json={"kg": 80.0, "date": DAY}, headers=mine)  # same day replaces
    client.post("/api/weight", json={"kg": 79.0, "date": "2026-01-06"}, headers=mine)
    rows = client.get("/api/weight", headers=mine).get_json()
    assert [r["kg"] for r in rows] == [80.0, 79.0]
    assert client.get("/api/weight", headers=theirs).get_json() == []
    assert client.delete(f"/api/weight/{first['id']}", headers=theirs).status_code == 404
    assert client.delete(f"/api/weight/{first['id']}", headers=mine).status_code == 204
    assert client.post("/api/weight", json={"kg": -1}, headers=mine).status_code == 400


# ---- review fixes ----


def test_today_local_follows_sahirep_tz(monkeypatch: pytest.MonkeyPatch) -> None:
    from datetime import datetime  # noqa: PLC0415
    from zoneinfo import ZoneInfo  # noqa: PLC0415

    from nutrition import today_local  # noqa: PLC0415

    monkeypatch.setenv("SAHIREP_TZ", "Pacific/Kiritimati")  # UTC+14
    ahead = today_local()
    monkeypatch.setenv("SAHIREP_TZ", "Etc/GMT+12")  # UTC-12
    behind = today_local()
    assert ahead > behind
    assert behind == datetime.now(ZoneInfo("Etc/GMT+12")).date()
    monkeypatch.delenv("SAHIREP_TZ")
    assert today_local() == datetime.now(ZoneInfo("Asia/Kolkata")).date()


def test_calorie_floor_applies_to_lose_only() -> None:
    assert calorie_target(1300, "lose", "female") == 1200
    assert calorie_target(1300, "lose", "male") == 1500
    assert calorie_target(1300, "maintain", "female") == 1300


def test_birth_year_cannot_be_in_future(client: FlaskClient) -> None:
    h = auth(client)
    assert client.put("/api/profile", json={**PROFILE, "birth_year": 2100}, headers=h).status_code == 400
    assert client.put("/api/profile", json={**PROFILE, "birth_year": 1899}, headers=h).status_code == 400


def test_weight_unique_per_user_day(app: Flask) -> None:
    from sqlalchemy.exc import IntegrityError  # noqa: PLC0415

    from nutrition import WeightLog  # noqa: PLC0415

    with app.app_context():
        db.session.add_all([WeightLog(user_id=1, date=date(2026, 1, 5), kg=80) for _ in range(2)])
        with pytest.raises(IntegrityError):
            db.session.commit()
        db.session.rollback()


def test_deleted_user_gets_401(app: Flask, client: FlaskClient) -> None:
    from app import User  # noqa: PLC0415

    h = auth(client)
    with app.app_context():
        db.session.delete(db.session.query(User).one())
        db.session.commit()
    assert client.get("/api/profile", headers=h).status_code == 401


def test_patch_quantity_clears_serving_and_quick_add_rejects_amounts(client: FlaskClient) -> None:
    h = auth(client)
    entry = log(client, h, food_slug="test-a", serving_id=1).get_json()
    assert entry["serving"] == "1 piece"
    patched = client.patch(f"/api/nutrition/log/{entry['id']}", json={"quantity_g": 75}, headers=h)
    assert (patched.get_json()["serving"], patched.get_json()["quantity_g"]) == (None, 75)
    assert log(client, h, name="x", kcal=1, quantity_g=5).status_code == 400
    assert log(client, h, name="x", kcal=1, serving_id=1).status_code == 400


def test_importer_survives_bad_documents(app: Flask) -> None:
    with app.app_context():
        report = import_foods([])  # type: ignore[arg-type]
        assert report["errors"][0]["error"] == "document must be a JSON object"
        report = import_foods({"foods": "nope", "sources": [1]})
        assert [(e["section"], e["index"]) for e in report["errors"]] == [("sources", 0), ("foods", None)]
        row = {**FIXTURE["foods"][0], "slug": "dup-src", "sources": ["Test Source", "Test Source"]}
        assert import_foods({"foods": [row]})["errors"] == []
        assert len(db.session.query(Food).filter_by(slug="dup-src").one().sources) == 1
