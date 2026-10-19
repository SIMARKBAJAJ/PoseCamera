from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pytest
from sqlalchemy import func, select

from app import create_app, db
from catalog import Exercise, Machine, MuscleGroup, _upsert, import_catalog
from plans import (
    COMPOUND_REST_S,
    ISOLATION,
    ISOLATION_REP_RANGE,
    ISOLATION_REST_S,
    LEVELS,
    MAX_SETS_PER_SLOT,
    MIN_FREQUENCY,
    PATTERN_MUSCLES,
    REP_RANGE,
    RIR,
    SPLITS,
    WEEKLY_SETS,
    PlanDay,
    PlanRow,
    PlanSlot,
    PlanTemplate,
    double_progression,
    generate_plan,
    import_plans,
    resolve_plan,
    resolve_slot,
)

if TYPE_CHECKING:
    from collections.abc import Generator

    from flask import Flask
    from flask.testing import FlaskClient

GOALS = ("strength", "hypertrophy", "general")
PROFILE = {
    "sex": "male", "birth_year": 1996, "height_cm": 180, "weight_kg": 80,
    "activity_level": "moderate", "goal": "gain",
}  # fmt: skip


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'plans-test.db').as_posix()}",
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


def auth(client: FlaskClient, username: str = "simar") -> dict[str, str]:
    body = {"username": username, "password": "strong-password-123"}
    client.post("/api/auth/register", json=body)
    token = client.post("/api/auth/login", json=body).get_json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


# TEST FIXTURE ONLY: placeholder exercises, not real catalog data.
def add_exercise(  # noqa: PLR0913
    slug: str, pattern: str, muscle: str, *, difficulty: str = "beginner",
    machine_type: str | None = None, met: float | None = None,
) -> None:  # fmt: skip
    db.session.add(
        Exercise(
            name=slug, slug=slug, movement_pattern=pattern, difficulty=difficulty, met=met,
            primary_muscles=[_upsert(MuscleGroup, "name", muscle)[0]],
            machine=Machine(name=f"{slug}-machine", type=machine_type) if machine_type else None,
        )
    )  # fmt: skip
    db.session.commit()


def slot(pattern: str, muscles: list[str], difficulty: str = "beginner") -> dict[str, Any]:
    return {"movement_pattern": pattern, "muscles": muscles, "sets": 3, "rep_min": 6,
            "rep_max": 12, "rest_s": 120, "rir": 2, "difficulty_max": difficulty}  # fmt: skip


def plan_row(slug: str, **extra: Any) -> dict[str, Any]:  # noqa: ANN401
    return {"slug": slug, "name": slug, "level": "beginner", "goal": "general",
            "days_per_week": 2, "split": "full_body",
            "days": [{"name": "A", "slots": [slot("squat", ["quadriceps"])]}], **extra}  # fmt: skip


# ---- generator ----


@pytest.mark.parametrize("days", range(2, 7))
@pytest.mark.parametrize("goal", GOALS)
@pytest.mark.parametrize("level", LEVELS)
def test_generator_meets_constants(level: str, goal: str, days: int) -> None:
    plan = generate_plan(level, goal, days)
    PlanRow.model_validate(plan)  # importable as-is
    assert len(plan["days"]) == days
    assert plan["verified"] is False
    assert plan["evidence_notes"]
    if level == "beginner":
        assert plan["split"] == "full_body"
    weekly: Counter[str] = Counter()
    sessions: defaultdict[str, set[int]] = defaultdict(set)
    for index, day in enumerate(plan["days"]):
        for s in day["slots"]:
            weekly[s["muscles"][0]] += s["sets"]
            sessions[s["muscles"][0]].add(index)
            isolation = s["movement_pattern"] in ISOLATION
            assert 1 <= s["sets"] <= MAX_SETS_PER_SLOT
            assert s["rir"] == RIR[level]
            assert s["rest_s"] == (ISOLATION_REST_S if isolation else COMPOUND_REST_S[goal])
            rep_range = ISOLATION_REP_RANGE if isolation else REP_RANGE[goal]
            assert (s["rep_min"], s["rep_max"]) == rep_range
            assert s["difficulty_max"] == level
    used = {p for blueprint in SPLITS.values() for _, patterns in blueprint for p in patterns}
    assert used <= set(PATTERN_MUSCLES)
    assert set(weekly) == {PATTERN_MUSCLES[p][0] for p in used if p in {
        s["movement_pattern"] for d in plan["days"] for s in d["slots"]}}
    low, high = WEEKLY_SETS[level]
    for muscle, sets in weekly.items():
        assert low <= sets <= high, (muscle, sets)
        assert len(sessions[muscle]) >= MIN_FREQUENCY, muscle


NEW_PATTERNS = {"core_flexion", "core_stability", "hip_extension"}  # accessories used by SPLITS
OMITTED = {"lateral_raise", "rear_delt", "calf_raise"}  # no bodyweight exercise: omitted, not unmet
BODYWEIGHT_UNMET_BEFORE = 177  # of 1026 slots (all level/goal/days) before the accessory slots
CATALOG = Path(__file__).parent.parent / "data" / "out" / "exercises.json"


def test_accessory_patterns_appear_in_plans() -> None:
    seen = {
        s["movement_pattern"]
        for level in LEVELS for days in range(2, 7)
        for d in generate_plan(level, "general", days)["days"] for s in d["slots"]
    }  # fmt: skip
    assert seen >= NEW_PATTERNS | OMITTED
    extra = {"wrist_curl", "neck"}
    assert extra <= set(PATTERN_MUSCLES)


@pytest.mark.skipif(not CATALOG.exists(), reason="data/out/exercises.json not present")
def test_generated_plans_resolve_against_real_catalog(app: Flask) -> None:  # noqa: C901
    with app.app_context():
        assert not import_catalog(json.loads(CATALOG.read_text(encoding="utf8")))["errors"]
        unmet: Counter[str] = Counter()
        slots = 0
        for level in LEVELS:
            for goal in GOALS:
                for days in range(2, 7):
                    plan = generate_plan(level, goal, days)
                    PlanRow.model_validate(plan)
                    def patterns(days: list[dict[str, Any]]) -> Counter[str]:
                        return Counter(s["movement_pattern"] for d in days for s in d["slots"])

                    for equipment in ("gym", "home"):  # every planned slot is kept, none omitted
                        assert patterns(resolve_plan(plan, equipment, 80)["days"]) == patterns(
                            plan["days"])
                    for equipment in ("gym", "home", "bodyweight"):
                        for d in resolve_plan(plan, equipment, 80)["days"]:
                            for s in d["slots"]:
                                if equipment == "bodyweight":
                                    assert s["movement_pattern"] not in OMITTED, s
                                    slots += 1
                                    unmet[s["movement_pattern"]] += s["exercise"] is None
                                elif equipment == "home" or s["movement_pattern"] in (
                                    NEW_PATTERNS | OMITTED
                                ):
                                    assert s["exercise"] is not None, (equipment, s)
        assert sum(unmet.values()) <= BODYWEIGHT_UNMET_BEFORE, (slots, unmet)
        assert not any(unmet[p] for p in NEW_PATTERNS)


def test_constants_stay_inside_brief_ranges() -> None:
    assert all(120 <= r <= 180 for r in COMPOUND_REST_S.values())
    assert 60 <= ISOLATION_REST_S <= 90
    assert RIR["beginner"] in (2, 3)
    assert 0 <= RIR["advanced"] <= 2
    assert REP_RANGE["strength"] == (3, 6)
    ranges = (REP_RANGE["hypertrophy"], REP_RANGE["general"], ISOLATION_REP_RANGE)
    assert all(5 <= lo <= hi <= 30 for lo, hi in ranges)
    assert WEEKLY_SETS["beginner"] == (6, 10)
    assert WEEKLY_SETS["advanced"] == (10, 20)


# ---- resolution ----


def test_empty_catalog_reports_unmet_slots(app: Flask) -> None:
    with app.app_context():
        plan = resolve_plan(generate_plan("beginner", "general", 3), "gym", 80)
    for day in plan["days"]:
        assert day["kcal_estimate"] is None
        for s in day["slots"]:
            assert s["exercise"] is None
            assert s["unmet"] == "movement_pattern"


def test_resolution_with_tiny_catalog(app: Flask) -> None:
    with app.app_context():
        add_exercise("push-machine", "horizontal_push", "chest", machine_type="machine", met=5)
        add_exercise("push-floor", "Horizontal_Push", "Chest", met=4)  # equipment unknown
        add_exercise("push-ups-bodyweight", "horizontal_push", "chest",
                     machine_type="bodyweight", met=4)  # fmt: skip
        add_exercise("squat-hard", "squat", "quadriceps", difficulty="advanced")
        add_exercise("row-wrong-muscle", "horizontal_pull", "biceps")
        add_exercise("curl-cable", "elbow_flexion", "biceps", machine_type="cable")
        exercises = list(db.session.scalars(select(Exercise)))

        def pick(s: dict[str, Any], equipment: str, kg: float | None = None) -> dict[str, Any]:
            return resolve_slot(s, exercises, equipment, kg)

        push = slot("horizontal_push", ["chest"])
        assert pick(push, "gym")["exercise"]["slug"] == "push-machine"  # machine preferred
        assert pick(push, "home")["exercise"]["slug"] == "push-ups-bodyweight"
        assert pick(push, "bodyweight")["exercise"]["slug"] == "push-ups-bodyweight"
        unknown = [e for e in exercises if e.slug == "push-floor"]  # machine=None is not "no gear"
        assert resolve_slot(push, unknown, "home", None)["unmet"] == "equipment"
        assert resolve_slot(push, unknown, "bodyweight", None)["unmet"] == "equipment"
        assert resolve_slot(push, unknown, "gym", None)["exercise"]["slug"] == "push-floor"
        # 3 sets * (9 reps * 3 s + 120 s) = 7.35 min; 5 MET * 80 kg * 7.35/60 h = 49.0
        assert pick(push, "gym", 80)["kcal"] == 49.0
        assert pick(push, "gym")["kcal"] is None  # no weight -> no estimate
        assert pick(slot("squat", ["quadriceps"]), "gym")["unmet"] == "difficulty_max"
        hard = pick(slot("squat", ["quadriceps"], "advanced"), "gym")
        assert hard["exercise"]["slug"] == "squat-hard"
        assert hard["kcal"] is None  # no MET on the exercise
        assert pick(slot("horizontal_pull", ["back"]), "gym")["unmet"] == "muscles"
        curl = slot("elbow_flexion", ["biceps"])
        assert pick(curl, "bodyweight") == {**curl, "exercise": None, "unmet": "equipment",
                                             "kcal": None}  # fmt: skip
        assert pick(curl, "gym")["exercise"]["slug"] == "curl-cable"


# ---- importer ----

DOC: dict[str, Any] = {
    "sources": [{"title": "Test Source", "year": 2000}],
    "plans": [
        plan_row("sourced", verified=True, sources=["Test Source"]),
        plan_row("unsourced", verified=True),
        plan_row("bad-reps", days=[{"name": "A", "slots": [slot("squat", ["q"]) | {"rep_min": 13}]}]),  # noqa: E501
        plan_row("bad-source", sources=["Missing"]),
    ],
}  # fmt: skip


def test_import_plans_idempotent_errors_and_verified_rule(app: Flask) -> None:
    with app.app_context():
        first = import_plans(DOC)
        assert first["created"] == {"sources": 1, "plans": 2}
        assert [(e["section"], e["index"]) for e in first["errors"]] == [("plans", 2), ("plans", 3)]
        assert "unknown sources" in first["errors"][1]["error"]
        verified = dict(db.session.execute(select(PlanTemplate.slug, PlanTemplate.verified)).all())
        assert verified == {"sourced": True, "unsourced": False}

        second = import_plans(DOC)
        assert second["created"] == {"sources": 0, "plans": 0}
        assert second["updated"] == {"sources": 1, "plans": 2}
        counts = [db.session.scalar(select(func.count()).select_from(m))
                  for m in (PlanTemplate, PlanDay, PlanSlot)]  # fmt: skip
        assert counts == [2, 2, 2]


def test_public_plan_routes(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        import_plans(DOC)
    assert [p["slug"] for p in client.get("/api/plans?level=beginner&days=2").get_json()["items"]] == [
        "sourced", "unsourced",
    ]  # fmt: skip
    assert client.get("/api/plans?goal=strength").get_json()["items"] == []
    body = client.get("/api/plans/sourced?equipment=home").get_json()
    assert body["equipment"] == "home"
    assert body["sources"][0]["title"] == "Test Source"
    assert body["days"][0]["slots"][0]["unmet"] == "movement_pattern"
    assert client.get("/api/plans/nope").status_code == 404
    assert client.get("/api/plans/sourced?equipment=space").status_code == 400


# ---- user plan routes ----


@pytest.mark.parametrize(
    ("method", "url"),
    [
        ("POST", "/api/plans/generate"),
        ("POST", "/api/me/plan"),
        ("GET", "/api/me/plan/today"),
        ("POST", "/api/me/plan/complete-day"),
    ],
)
def test_plan_routes_require_jwt(client: FlaskClient, method: str, url: str) -> None:
    assert client.open(url, method=method, json={}).status_code == 401


def test_generate_assign_today_complete_flow(app: Flask, client: FlaskClient) -> None:
    h = auth(client)
    assert client.put("/api/profile", json=PROFILE, headers=h).status_code == 200
    with app.app_context():
        add_exercise("push-floor", "horizontal_push", "chest", machine_type="bodyweight", met=5)
    assert client.get("/api/me/plan/today", headers=h).status_code == 404  # nothing assigned

    gen = client.post(
        "/api/plans/generate", json={"days_per_week": 3, "save": True}, headers=h
    ).get_json()
    assert gen["goal"] == "hypertrophy"  # from profile goal "gain"
    assert gen["saved"] is True
    assert gen["days"][0]["kcal_estimate"] == 49.0  # profile weight 80 kg, see tiny-catalog test
    slug = gen["slug"]
    assert client.get(f"/api/plans/{slug}").status_code == 200

    assign = {"slug": slug, "equipment": "bodyweight"}
    assert client.post("/api/me/plan", json={"slug": "nope"}, headers=h).status_code == 404
    assert client.post("/api/me/plan", json=assign, headers=h).status_code == 201

    today = client.get("/api/me/plan/today", headers=h).get_json()
    assert (today["day_index"], today["completed_today"]) == (0, False)
    assert today["day"]["name"] == "Full body A"
    push = next(s for s in today["day"]["slots"] if s["movement_pattern"] == "horizontal_push")
    assert (push["exercise"]["slug"], push["kcal"]) == ("push-floor", 49.0)

    done = client.post("/api/me/plan/complete-day", json={}, headers=h)
    assert done.status_code == 201
    assert done.get_json()["next_day_index"] == 1
    today = client.get("/api/me/plan/today", headers=h).get_json()
    assert (today["day_index"], today["completed_today"]) == (0, True)

    bad = client.post("/api/me/plan/complete-day", json={"day_index": 5}, headers=h)
    assert bad.status_code == 400
    old = {"day_index": 2, "date": "2026-01-01"}
    assert client.post("/api/me/plan/complete-day", json=old, headers=h).status_code == 201
    today = client.get("/api/me/plan/today", headers=h).get_json()
    assert (today["day_index"], today["completed_today"]) == (0, True)  # backfill is not "latest"

    client.post("/api/weight", json={"kg": 90}, headers=h)  # latest weigh-in beats profile
    today = client.get("/api/me/plan/today", headers=h).get_json()
    assert today["day"]["kcal_estimate"] == 55.1  # 5 * 90 * 7.35 / 60 = 55.125

    client.post("/api/me/plan/complete-day", json={}, headers=h)
    assert client.post("/api/me/plan", json=assign, headers=h).status_code == 201
    today = client.get("/api/me/plan/today", headers=h).get_json()
    assert (today["day_index"], today["completed_today"]) == (0, False)  # reassign resets


# ---- progression ----


def test_double_progression() -> None:
    assert double_progression([12, 12, 12], 8, 12, 100) == {"load": 105, "reps": 8}
    assert double_progression([12, 12, 11], 8, 12, 100) == {"load": 100, "reps": 12}
    assert double_progression([9, 8, 8], 8, 12, 100) == {"load": 100, "reps": 9}
    assert double_progression([5, 6], 8, 12, 60) == {"load": 60, "reps": 8}
    assert double_progression([12, 12], 8, 12, 20) == {"load": 21, "reps": 8}
    assert double_progression([15], 8, 12, 5) == {"load": 5.5, "reps": 8}  # min 0.5 kg step


# ---- review fixes ----


def test_generate_save_never_overwrites_existing_slug(app: Flask, client: FlaskClient) -> None:
    h = auth(client)
    curated = plan_row("builtin-beginner-general-3d", name="Curated", verified=True,
                       sources=["Test Source"])  # fmt: skip
    with app.app_context():
        assert import_plans({"sources": DOC["sources"], "plans": [curated]})["errors"] == []
    body = {"level": "beginner", "goal": "general", "days_per_week": 3, "save": True}
    res = client.post("/api/plans/generate", json=body, headers=h)
    assert res.status_code == 200
    assert (res.get_json()["name"], res.get_json()["verified"]) == ("Curated", True)
    with app.app_context():
        kept = db.session.scalar(select(PlanTemplate).where(PlanTemplate.slug == curated["slug"]))
        assert (kept.name, kept.verified, len(kept.days)) == ("Curated", True, 1)


def test_shrunk_plan_and_repeat_completion_never_500(app: Flask, client: FlaskClient) -> None:
    h = auth(client)
    three = [{"name": n, "slots": [slot("squat", ["quadriceps"])]} for n in "ABC"]
    with app.app_context():
        import_plans({"plans": [plan_row("shrinks", days=three)]})
    assert client.post("/api/me/plan", json={"slug": "shrinks"}, headers=h).status_code == 201
    first = client.post("/api/me/plan/complete-day", json={"day_index": 2}, headers=h)
    again = client.post("/api/me/plan/complete-day", json={}, headers=h)
    assert (first.status_code, again.status_code) == (201, 200)  # same day: no second advance
    assert again.get_json()["day_index"] == 2
    with app.app_context():
        import_plans({"plans": [plan_row("shrinks")]})  # now a single day
    today = client.get("/api/me/plan/today", headers=h)
    assert today.status_code == 200
    assert today.get_json()["day_index"] == 0


def test_import_plans_survives_bad_documents(app: Flask) -> None:
    with app.app_context():
        assert import_plans("x")["errors"][0]["section"] is None  # type: ignore[arg-type]
        report = import_plans({"plans": {"a": 1}, "sources": [{"title": "S", "year": 1}]})
        assert [(e["section"], e["index"]) for e in report["errors"]] == [("plans", None)]
        assert report["created"]["sources"] == 1
