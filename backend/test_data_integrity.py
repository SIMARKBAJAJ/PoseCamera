"""Regression tests: food-log macro snapshots, nullable fiber, migrations 0004/0005, isolation
fallback, and the data converters' rules. Fixture data is made up, not real food data."""

from __future__ import annotations

import importlib.util
import json
import os
import sqlite3
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pytest
from alembic.script import ScriptDirectory
from sqlalchemy import select

from app import create_app, db
from catalog import Exercise, Machine, MuscleGroup, _upsert
from nutrition import Food, import_foods
from plans import resolve_slot

if TYPE_CHECKING:
    from collections.abc import Generator

    from flask import Flask
    from flask.testing import FlaskClient

BACKEND = Path(__file__).parent
CONVERT = BACKEND.parent / "data" / "convert"
DAY = "2026-01-05"


def food_doc(**over: Any) -> dict[str, Any]:  # noqa: ANN401
    row = {"name": "Snap Food", "slug": "snap", "kcal": 200, "protein_g": 10, "carbs_g": 30,
           "fat_g": 5, "fiber_g": 2}  # fmt: skip
    return {"foods": [{**row, **over}]}


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'integrity.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    with application.app_context():
        assert import_foods(food_doc())["errors"] == []
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


def auth(client: FlaskClient) -> dict[str, str]:
    body = {"username": "simar", "password": "strong-password-123"}
    client.post("/api/auth/register", json=body)
    token = client.post("/api/auth/login", json=body).get_json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


def log(client: FlaskClient, h: dict[str, str], **body: Any) -> Any:  # noqa: ANN401
    return client.post("/api/nutrition/log", json={"meal": "lunch", "date": DAY, **body}, headers=h)


# ---- 1. macro snapshots ----


def test_reimporting_a_food_leaves_past_logs_unchanged(app: Flask, client: FlaskClient) -> None:
    h = auth(client)
    past = log(client, h, food_slug="snap", quantity_g=150).get_json()  # 300 kcal, 15 P, 45 C
    quick = log(client, h, name="quick", kcal=111, protein_g=1, carbs_g=2, fat_g=3).get_json()
    with app.app_context():
        changed = import_foods(food_doc(kcal=999, protein_g=99, carbs_g=88, fat_g=77, fiber_g=66))
        assert changed["updated"]["foods"] == 1
        assert db.session.scalar(select(Food).where(Food.slug == "snap")).kcal == 999

    day = client.get(f"/api/nutrition/day?date={DAY}", headers=h).get_json()
    entries = {e["id"]: e for e in day["meals"]["lunch"]["entries"]}
    assert (entries[past["id"]]["kcal"], entries[past["id"]]["protein_g"]) == (300, 15)
    assert entries[past["id"]]["fiber_g"] == 3
    assert (entries[quick["id"]]["kcal"], day["totals"]["kcal"], day["fiber_g"]) == (111, 411, 3)

    # PATCH recomputes from the snapshot, not the changed food
    patched = client.patch(f"/api/nutrition/log/{past['id']}", json={"quantity_g": 50}, headers=h)
    assert (patched.get_json()["kcal"], patched.get_json()["carbs_g"]) == (100, 15)
    # a log made after the re-import snapshots the new values
    assert log(client, h, food_slug="snap", quantity_g=100).get_json()["kcal"] == 999


# ---- 2. nullable fiber ----


def test_null_fiber_is_stored_shown_and_counted_as_zero(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        assert import_foods(food_doc(fiber_g=None))["errors"] == []
        omitted = food_doc(slug="snap-omitted")
        del omitted["foods"][0]["fiber_g"]
        assert import_foods(omitted)["errors"] == []
        assert import_foods(food_doc(slug="neg", fiber_g=-1))["errors"]  # still validated
    assert client.get("/api/foods/snap").get_json()["fiber_g"] is None
    assert client.get("/api/foods/snap-omitted").get_json()["fiber_g"] is None
    h = auth(client)
    entry = log(client, h, food_slug="snap", quantity_g=100).get_json()
    assert entry["fiber_g"] is None
    assert entry["kcal"] == 200
    assert log(client, h, name="q", kcal=5).get_json()["fiber_g"] is None
    assert client.get(f"/api/nutrition/day?date={DAY}", headers=h).get_json()["fiber_g"] == 0


# ---- migrations 0004 / 0005 on a real alembic run ----


def flask_db(db_file: Path, *args: str) -> None:
    env = {**os.environ, "FLASK_APP": "app:create_app", "SAHIREP_JWT_SECRET": "x" * 40,
           "SAHIREP_DATABASE_URI": f"sqlite:///{db_file.as_posix()}"}  # fmt: skip
    done = subprocess.run(  # noqa: S603
        [sys.executable, "-m", "flask", "db", *args], cwd=BACKEND, env=env,
        capture_output=True, text=True, check=False,
    )  # fmt: skip
    assert done.returncode == 0, done.stderr[-2000:]


def test_migrations_backfill_snapshots_and_make_fiber_nullable(tmp_path: Path) -> None:
    path = tmp_path / "mig.db"
    flask_db(path, "upgrade", "0003")
    con = sqlite3.connect(path)
    con.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'u', 'x')")
    con.execute("INSERT INTO foods (id, name, slug, kcal, protein_g, carbs_g, fat_g, fiber_g, verified)"
                " VALUES (1, 'F', 'f', 200, 10, 30, 5, 2, 0)")  # fmt: skip
    con.execute("INSERT INTO food_logs (user_id, food_id, quantity_g, meal, date)"
                " VALUES (1, 1, 150, 'lunch', '2026-01-05')")  # fmt: skip
    con.execute("INSERT INTO food_logs (user_id, name, kcal, meal, date)"
                " VALUES (1, 'quick', 50, 'snack', '2026-01-05')")  # fmt: skip
    con.commit()
    con.close()

    flask_db(path, "upgrade")  # 0004 (backfill) then 0005 (nullable, FK-safe rebuild)

    con = sqlite3.connect(path)
    try:
        head = ScriptDirectory(str(BACKEND / "migrations")).get_current_head()
        assert con.execute("SELECT version_num FROM alembic_version").fetchone() == (head,)
        cols = "kcal_per100, protein_g_per100, carbs_g_per100, fat_g_per100, fiber_g_per100"
        food_row = con.execute(f"SELECT {cols} FROM food_logs WHERE food_id = 1").fetchone()  # noqa: S608
        quick_row = con.execute(f"SELECT {cols} FROM food_logs WHERE food_id IS NULL").fetchone()  # noqa: S608
        assert food_row == (200, 10, 30, 5, 2)
        assert quick_row == (None,) * 5
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
        nullable = con.execute("SELECT \"notnull\" FROM pragma_table_info('foods') WHERE name='fiber_g'")
        assert nullable.fetchone() == (0,)
        con.execute("UPDATE foods SET fiber_g = NULL")  # now allowed
    finally:
        con.close()


def test_migration_0007_makes_form_fields_nullable_and_keeps_constraints(tmp_path: Path) -> None:
    path = tmp_path / "mig7.db"
    flask_db(path, "upgrade", "0006")
    con = sqlite3.connect(path)
    try:
        con.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'u', 'x')")
        con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, clean_count,"
                    " attempted_count, formscore, timestamp) VALUES (1, 'squat', 5, 4, 1, 80,"
                    " '2026-01-05')")  # fmt: skip
        con.commit()
    finally:
        con.close()
    flask_db(path, "upgrade")
    con = sqlite3.connect(path)
    try:
        info = con.execute("SELECT name, \"notnull\" FROM pragma_table_info('workout_sets')").fetchall()
        assert {n: nn for n, nn in info if n in ("clean_count", "attempted_count", "formscore")} == {
            "clean_count": 0, "attempted_count": 0, "formscore": 0}
        ddl = con.execute("SELECT sql FROM sqlite_master WHERE name='workout_sets'").fetchone()[0]
        for name in ("ck_workout_sets_load_kg", "ck_workout_sets_rpe", "ck_workout_sets_set_type",
                     "fk_workout_sets_exercise_slug"):
            assert name in ddl
        assert con.execute("SELECT clean_count, formscore FROM workout_sets").fetchall() == [(4, 80.0)]
        con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, timestamp)"
                    " VALUES (1, 'other', 3, '2026-01-06')")  # form fields omitted = allowed
        con.commit()
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        con.close()
    flask_db(path, "downgrade", "0006")  # fills the null form fields, then re-adds NOT NULL
    con = sqlite3.connect(path)
    try:
        assert con.execute("SELECT clean_count, attempted_count, formscore FROM workout_sets"
                           " WHERE exercise_type='other'").fetchall() == [(3, 0, 0.0)]
    finally:
        con.close()


# ---- 4b. isolation fallback ----


def add_exercise(slug: str, pattern: str, primary: str, secondary: tuple[str, ...] = (), *,
                 machine_type: str | None = None, difficulty: str = "beginner") -> None:  # fmt: skip
    db.session.add(Exercise(
        name=slug, slug=slug, movement_pattern=pattern, difficulty=difficulty,
        primary_muscles=[_upsert(MuscleGroup, "name", primary)[0]],
        secondary_muscles=[_upsert(MuscleGroup, "name", m)[0] for m in secondary],
        machine=Machine(name=f"{slug}-m", type=machine_type) if machine_type else None,
    ))  # fmt: skip
    db.session.commit()


def slot(pattern: str, muscle: str) -> dict[str, Any]:
    return {"movement_pattern": pattern, "muscles": [muscle], "sets": 3, "rep_min": 8,
            "rep_max": 12, "rest_s": 60, "rir": 2, "difficulty_max": "beginner"}  # fmt: skip


def test_isolation_falls_back_to_compound_on_same_muscle(app: Flask) -> None:
    with app.app_context():
        add_exercise("pull-up", "vertical_pull", "back", ("biceps",), machine_type="bodyweight")
        add_exercise("row-cable", "horizontal_pull", "back", ("biceps",), machine_type="cable")
        add_exercise("ghr", "hinge", "hamstrings", machine_type="bodyweight")
        add_exercise("dip-unknown", "horizontal_push", "chest", ("triceps",))  # equipment unknown
        add_exercise("pike", "vertical_push", "shoulders", ("triceps",), machine_type="bodyweight")
        add_exercise("curl-db", "elbow_flexion", "biceps", machine_type="free-weight")
        ex = list(db.session.scalars(select(Exercise)))

        curl = resolve_slot(slot("elbow_flexion", "biceps"), ex, "bodyweight", None)
        assert curl["exercise"]["slug"] == "pull-up"  # not row-cable: it fails equipment
        assert (curl["substituted_from"], curl["movement_pattern"]) == ("elbow_flexion", "vertical_pull")
        legs = resolve_slot(slot("knee_flexion", "hamstrings"), ex, "home", None)
        assert (legs["exercise"]["slug"], legs["substituted_from"]) == ("ghr", "knee_flexion")
        # triceps: horizontal_push only has an unknown-equipment exercise, so vertical_push is used
        tri = resolve_slot(slot("elbow_extension", "triceps"), ex, "bodyweight", None)
        assert (tri["exercise"]["slug"], tri["substituted_from"]) == ("pike", "elbow_extension")

        # gym is untouched; a direct match is never marked
        assert "substituted_from" not in resolve_slot(slot("elbow_flexion", "biceps"), ex, "gym", None)
        assert "substituted_from" not in resolve_slot(slot("elbow_flexion", "biceps"), ex, "home", None)
        # compound slots never substitute
        assert resolve_slot(slot("vertical_pull", "triceps"), ex, "bodyweight", None)["exercise"] is None


def test_fallback_never_fabricates_or_crosses_equipment(app: Flask) -> None:
    with app.app_context():
        add_exercise("row-machine", "horizontal_pull", "back", ("biceps",), machine_type="machine")
        add_exercise("pull-unknown", "vertical_pull", "back", ("biceps",))  # equipment unknown
        add_exercise("pull-hard", "vertical_pull", "back", ("biceps",), machine_type="bodyweight",
                     difficulty="advanced")  # fmt: skip
        ex = list(db.session.scalars(select(Exercise)))
        curl = slot("elbow_flexion", "biceps")
        for equipment in ("bodyweight", "home"):
            got = resolve_slot(curl, ex, equipment, None)
            assert got == {**curl, "exercise": None, "unmet": "movement_pattern", "kcal": None}


# ---- 2/4a. converters ----


def load_module(name: str) -> Any:  # noqa: ANN401
    spec = importlib.util.spec_from_file_location(f"conv_{name}", CONVERT / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize(("name", "category", "primary", "expected"), [
    ("Pullups", "strength", ["back"], "vertical_pull"),
    ("Mixed Grip Chin", "strength", ["back"], "vertical_pull"),
    ("Chin To Chest Stretch", "stretching", ["neck"], None),
    ("Gorilla Chin/Crunch", "strength", ["abdominals"], "core_flexion"),
    ("Inverted Row", "strength", ["back"], "horizontal_pull"),
    ("Bench Dips", "strength", ["triceps"], "elbow_extension"),
    ("Dips - Chest Version", "strength", ["chest"], None),  # unsure -> null
    ("Jerk Dip Squat", "olympic weightlifting", ["quadriceps"], None),
    ("Handstand Push-Ups", "strength", ["shoulders"], "vertical_push"),
    ("Natural Glute Ham Raise", "strength", ["hamstrings"], "knee_flexion"),
    ("Nordic Hamstring Curl", "strength", ["hamstrings"], "knee_flexion"),
    ("Slider Leg Curl", "strength", ["hamstrings"], "knee_flexion"),
    ("Hyperextensions", "strength", ["back"], "hip_extension"),
    ("Side Lateral Raise", "strength", ["shoulders"], "lateral_raise"),
    ("Side Laterals to Front Raise", "strength", ["shoulders"], None),  # combo movement -> null
    ("Front Dumbbell Raise", "strength", ["shoulders"], "front_raise"),
    ("Reverse Flyes", "strength", ["shoulders"], "rear_delt"),
    ("Dumbbell Flyes", "strength", ["chest"], "chest_fly"),
    ("Standing Calf Raises", "strength", ["calves"], "calf_raise"),
    ("Dumbbell Shrug", "strength", ["back"], "shrug"),
    ("Upright Barbell Row", "strength", ["shoulders"], "upright_row"),
    ("Seated Palm-Up Barbell Wrist Curl", "strength", ["forearms"], "wrist_curl"),
    ("Plank", "strength", ["abdominals"], "core_stability"),
    ("Russian Twist", "strength", ["abdominals"], "core_rotation"),
    ("Hanging Leg Raise", "strength", ["abdominals"], "core_flexion"),
    ("Bosu Ball Cable Crunch With Side Bends", "strength", ["abdominals"], None),
    ("Isometric Neck Exercise - Sides", "strength", ["neck"], "neck"),
    ("Plank", "stretching", ["abdominals"], None),
])  # fmt: skip
def test_exercise_pattern_rules(name: str, category: str, primary: list[str], expected: str | None) -> None:
    mod = load_module("exercises")
    assert mod.pattern({"name": name, "category": category}, primary) == expected
    assert mod.met("Body Only", expected) == (None if expected is None else 3.5)  # MET rule unchanged
    assert mod.met("Barbell", "squat") == 6.0


OLD_RULE_COUNT = 10  # the original compound/arm rules; later RULES only claim names those left null
VOCAB = {"squat", "lunge", "hinge", "knee_flexion", "horizontal_push", "vertical_push", "horizontal_pull",
         "vertical_pull", "elbow_flexion", "elbow_extension", "lateral_raise", "front_raise", "rear_delt",
         "upright_row", "shrug", "chest_fly", "calf_raise", "wrist_curl", "core_stability", "core_rotation",
         "core_flexion", "hip_extension", "neck"}  # fmt: skip


def test_new_patterns_never_change_the_original_assignments() -> None:
    mod = load_module("exercises")
    assert {r[0] for r in mod.RULES} <= VOCAB
    raw = json.loads((CONVERT.parent / "raw" / "exercises" / "exercises.json").read_text(encoding="utf-8"))

    def primary(e: dict[str, Any]) -> list[str]:
        return list(dict.fromkeys(mod.MUSCLE.get(m, m) for m in e["primaryMuscles"]))

    new = {e["id"]: mod.pattern(e, primary(e)) for e in raw}
    full_rules = mod.RULES
    mod.RULES = full_rules[:OLD_RULE_COUNT]
    old = {e["id"]: mod.pattern(e, primary(e)) for e in raw}
    mod.RULES = full_rules
    assert sum(v is not None for v in old.values()) == 298  # noqa: PLR2004
    assert all(new[k] == v for k, v in old.items() if v is not None)


def test_exercises_output_patterns_stay_in_vocabulary() -> None:
    rows = json.loads((CONVERT.parent / "out" / "exercises.json").read_text(encoding="utf-8"))["exercises"]
    assert len(rows) == 876  # noqa: PLR2004
    assert {r["movement_pattern"] for r in rows} - {None} <= VOCAB


def test_exercises_output_pose_flag_only_on_pose_slugs() -> None:
    rows = json.loads((CONVERT.parent / "out" / "exercises.json").read_text(encoding="utf-8"))["exercises"]
    assert {r["slug"] for r in rows if r["has_pose_tracking"]} == {"bodyweight-squat", "pushups"}


def test_usda_missing_fiber_row_is_null_not_zero(tmp_path: Path) -> None:
    def write(name: str, lines: list[str]) -> None:
        (tmp_path / name).write_text("\n".join(lines) + "\n", encoding="utf-8")

    write("food_category.csv", ["id,description", "1,Cat"])
    write("measure_unit.csv", ["id,name", "1,cup"])
    write("food.csv", ["fdc_id,data_type,description,food_category_id",
                       "10,sr_legacy_food,With fibre,1", "11,sr_legacy_food,No fibre row,1"])  # fmt: skip
    rows = [f"{fid},{nid},{amount}" for fid in (10, 11)
            for nid, amount in ((1008, 100), (1003, 1), (1004, 2), (1005, 3))]  # fmt: skip
    rows.append("10,1079,0")  # a measured zero stays 0
    write("food_nutrient.csv", ["fdc_id,nutrient_id,amount", *rows])
    write("food_portion.csv", ["fdc_id,gram_weight,amount,measure_unit_id,portion_description,modifier"])

    stats: Counter[str] = Counter()
    foods = load_module("usda_foods").load(str(tmp_path), "sr_legacy_food", "sr", stats)
    by_name = {o["name"]: o for o in foods}
    assert by_name["With fibre"]["fiber_g"] == 0
    assert by_name["No fibre row"]["fiber_g"] is None
    assert stats["fiber_null_sr"] == 1


def test_full_downgrade_to_0001_and_back_with_related_rows(tmp_path: Path) -> None:
    path = tmp_path / "roundtrip.db"
    flask_db(path, "upgrade")
    con = sqlite3.connect(path)
    try:
        con.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'u', 'x')")
        con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, clean_count,"
                    " attempted_count, formscore, timestamp) VALUES (1, 'squat', 5, 4, 1, 80,"
                    " '2026-01-05')")  # fmt: skip
        con.execute("INSERT INTO food_logs (user_id, name, kcal, meal, date)"
                    " VALUES (1, 'quick', 50, 'snack', '2026-01-05')")  # fmt: skip
        con.commit()
    finally:
        con.close()
    flask_db(path, "downgrade", "0001")
    flask_db(path, "upgrade")
    con = sqlite3.connect(path)
    try:
        head = ScriptDirectory(str(BACKEND / "migrations")).get_current_head()
        assert con.execute("SELECT version_num FROM alembic_version").fetchone() == (head,)
        assert con.execute("SELECT COUNT(*) FROM users").fetchone() == (1,)
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        con.close()
