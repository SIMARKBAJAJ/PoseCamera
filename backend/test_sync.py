"""Offline-first sync: push/pull, idempotency, LWW, tombstones, catalog export, migration 0008."""

from __future__ import annotations

import gzip
import json
import os
import sqlite3
import subprocess
import sys
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pytest
from sqlalchemy import event, func, select

from app import (
    SyncChange,
    SyncOp,
    WorkoutSet,
    create_app,
    database_uri_from_env,
    db,
    engine_options,
    today_local,
)
from catalog import CatalogMeta, Exercise, import_catalog
from nutrition import FoodLog, WeightLog, import_foods
from plans import PlanCompletion, UserPlan, generate_plan, import_plans

if TYPE_CHECKING:
    from collections.abc import Generator

    from flask import Flask
    from flask.testing import FlaskClient

BACKEND = Path(__file__).parent
PROFILE = {
    "sex": "male", "birth_year": 1996, "height_cm": 180, "weight_kg": 80,
    "activity_level": "moderate", "goal": "maintain",
}  # fmt: skip
PLAN_SLUG = "builtin-beginner-general-3d"
TODAY = today_local()


def now() -> datetime:
    return datetime.now(UTC)


def iso(stamp: datetime) -> str:
    return stamp.isoformat()


def uid() -> str:
    return str(uuid.uuid4())


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'sync-test.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    with application.app_context():
        db.session.add_all([
            Exercise(name="Bench", slug="bench-press", difficulty="beginner"),
            Exercise(name="Squat", slug="bodyweight-squat", difficulty="beginner"),
        ])
        db.session.commit()
        food = {"name": "Snap Food", "slug": "snap", "kcal": 200, "protein_g": 10, "carbs_g": 30,
                "fat_g": 5, "fiber_g": 2, "servings": [{"label": "cup", "grams": 100}]}  # fmt: skip
        assert import_foods({"foods": [food]})["errors"] == []
        assert import_plans({"plans": [generate_plan("beginner", "general", 3)]})["errors"] == []
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


def signup(client: FlaskClient, name: str = "simar") -> dict[str, str]:
    body = {"username": name, "password": "strong-password-123"}
    client.post("/api/auth/register", json=body)
    token = client.post("/api/auth/login", json=body).get_json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def h(client: FlaskClient) -> dict[str, str]:
    return signup(client)


def op(entity: str, cid: str, data: dict[str, Any] | None, when: datetime | None = None,
       action: str = "upsert", op_id: str | None = None) -> dict[str, Any]:  # fmt: skip
    return {"op_id": op_id or uid(), "entity": entity, "client_id": cid, "action": action,
            "data": data, "client_updated_at": iso(when or now())}  # fmt: skip


def push(client: FlaskClient, h: dict[str, str], *ops: dict[str, Any]) -> list[dict[str, Any]]:
    r = client.post("/api/sync/push", headers=h, json={"device_id": "dev-1", "ops": list(ops)})
    assert r.status_code == 200, r.get_data(as_text=True)
    return r.get_json()["results"]


def pull(client: FlaskClient, h: dict[str, str], cursor: int = 0, limit: int = 500) -> dict[str, Any]:
    r = client.get(f"/api/sync/pull?cursor={cursor}&limit={limit}", headers=h)
    assert r.status_code == 200, r.get_data(as_text=True)
    return r.get_json()


def statuses(results: list[dict[str, Any]]) -> list[str]:
    return [r["status"] for r in results]


def a_set(when: datetime | None = None, **over: Any) -> dict[str, Any]:  # noqa: ANN401
    return {"exercise_type": "other", "exercise_slug": "bench-press", "rep_count": 5,
            "load_kg": 60, "timestamp": iso(when or now()), **over}  # fmt: skip


FOOD = {"meal": "lunch", "date": TODAY.isoformat(), "food_slug": "snap", "quantity_g": 150}


def a_log(**over: Any) -> dict[str, Any]:  # noqa: ANN401
    return {"meal": "lunch", "date": TODAY.isoformat(), "name": "tea", "kcal": 30, **over}


def count(model: type) -> int:
    return db.session.scalar(select(func.count()).select_from(model))


# ---- push / pull ----


def test_round_trip_every_entity_and_a_fresh_device_gets_everything(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    ids = {k: uid() for k in ("set", "squat", "food", "quick", "weight", "comp")}
    results = push(
        client, h,
        op("profile", "profile", PROFILE),
        op("user_plans", "plan", {"slug": PLAN_SLUG, "start_date": TODAY.isoformat(),
                                  "equipment": "gym"}),
        op("plan_completions", ids["comp"], {"day_index": 0, "date": TODAY.isoformat()}),
        op("weight_logs", ids["weight"], {"kg": 70.5, "date": TODAY.isoformat()}),
        op("food_logs", ids["food"], FOOD),
        op("food_logs", ids["quick"], a_log()),
        op("workout_sets", ids["set"], a_set()),
        op("workout_sets", ids["squat"], a_set(exercise_type="squat", exercise_slug="bodyweight-squat",
           rep_count=5, clean_count=4, attempted_count=1, formscore=88.5, load_kg=None)),
    )  # fmt: skip
    assert statuses(results) == ["applied"] * 8, results

    got = pull(client, h)
    assert [c["cursor"] for c in got["changes"]] == sorted(c["cursor"] for c in got["changes"])
    assert [c["entity"] for c in got["changes"]] == [
        "profile", "user_plans", "plan_completions", "weight_logs", "food_logs", "food_logs",
        "workout_sets", "workout_sets",
    ]
    by = {c["client_id"]: c for c in got["changes"]}
    assert (got["has_more"], got["next_cursor"]) == (False, got["changes"][-1]["cursor"])
    assert by["profile"]["data"] == PROFILE | {"diet_pref": None}
    assert by["plan"]["data"] == {"slug": PLAN_SLUG, "start_date": TODAY.isoformat(), "equipment": "gym"}
    assert by[ids["comp"]]["data"] == {"day_index": 0, "date": TODAY.isoformat()}
    assert by[ids["weight"]]["data"] == {"kg": 70.5, "date": TODAY.isoformat()}
    food = by[ids["food"]]["data"]
    assert (food["food_slug"], food["quantity_g"], food["computed"]["kcal"]) == ("snap", 150, 300)
    assert by[ids["quick"]]["data"]["name"] == "tea"
    assert by[ids["set"]]["data"]["load_kg"] == 60
    assert by[ids["set"]]["data"]["timestamp"].endswith("Z")
    assert by[ids["squat"]]["data"]["formscore"] == 88.5
    assert all(c["op"] == "upsert" and c["updated_at"] for c in got["changes"])
    # a fresh device (cursor 0) sees the same; a caught-up device sees nothing new
    assert pull(client, h)["changes"] == got["changes"]
    assert pull(client, h, got["next_cursor"])["changes"] == []


def test_replay_is_idempotent_and_returns_the_stored_result(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    ops = [op("workout_sets", uid(), a_set()), op("workout_sets", uid(), a_set(rep_count=0)),
           op("food_logs", uid(), a_log())]  # fmt: skip
    first = push(client, h, *ops)
    assert statuses(first) == ["applied", "rejected", "applied"]
    with app.app_context():
        before = (count(WorkoutSet), count(FoodLog), count(SyncChange), count(SyncOp))
    again = push(client, h, *ops)
    assert again == first  # including the stored rejection text
    with app.app_context():
        assert (count(WorkoutSet), count(FoodLog), count(SyncChange), count(SyncOp)) == before
    twice_in_one = push(client, h, ops[0], ops[0])
    assert twice_in_one[0] == twice_in_one[1] == first[0]


def test_conflict_is_last_writer_wins_by_client_time(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    cid, first = uid(), now() - timedelta(hours=1)
    assert statuses(push(client, h, op("food_logs", cid, a_log(kcal=30), first))) == ["applied"]
    older = push(client, h, op("food_logs", cid, a_log(kcal=99), now() - timedelta(hours=2)))
    assert older[0]["status"] == "conflict"
    equal = push(client, h, op("food_logs", cid, a_log(kcal=98), first))
    assert equal[0]["status"] == "conflict"
    newer = push(client, h, op("food_logs", cid, a_log(kcal=40), now() - timedelta(minutes=5)))
    assert newer[0]["status"] == "applied"
    with app.app_context():
        assert count(FoodLog) == 1
        assert db.session.scalar(select(FoodLog.kcal)) == 40
    last = pull(client, h)["changes"][-1]
    assert (last["client_id"], last["data"]["kcal"]) == (cid, 40)
    # a delete older than the server's version loses too
    stale_delete = push(client, h, op("food_logs", cid, None, now() - timedelta(hours=3), "delete"))
    assert stale_delete[0]["status"] == "conflict"


def test_client_time_is_clamped_to_server_now_plus_five_minutes(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    far = now() + timedelta(days=1)
    assert statuses(push(client, h, op("weight_logs", uid(), {"kg": 70, "date": TODAY.isoformat()}, far))) == ["applied"]
    with app.app_context():
        stamp = db.session.scalar(select(WeightLog.updated_at)).replace(tzinfo=UTC)
    assert stamp <= datetime.now(UTC) + timedelta(minutes=5, seconds=1)
    assert stamp >= now() + timedelta(minutes=4)


def test_weight_for_a_day_held_by_another_client_id_is_decided_by_time(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    a, b, c = uid(), uid(), uid()
    day = {"kg": 70, "date": TODAY.isoformat()}
    push(client, h, op("weight_logs", a, day, now() - timedelta(hours=2)))
    assert push(client, h, op("weight_logs", b, day | {"kg": 71}, now() - timedelta(hours=3)))[0]["status"] == "conflict"
    assert push(client, h, op("weight_logs", c, day | {"kg": 72}, now() - timedelta(hours=1)))[0]["status"] == "applied"
    with app.app_context():
        assert db.session.scalars(select(WeightLog.client_id)).all() == [c]
    ops = {(x["client_id"], x["op"]) for x in pull(client, h)["changes"]}
    assert (a, "delete") in ops
    assert (c, "upsert") in ops


def test_workout_sets_are_append_only_but_can_be_deleted(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    cid = uid()
    assert statuses(push(client, h, op("workout_sets", cid, a_set(rep_count=5)))) == ["applied"]
    again = push(client, h, op("workout_sets", cid, a_set(rep_count=9), now() + timedelta(minutes=1)))
    assert again[0]["status"] == "duplicate"
    with app.app_context():
        assert db.session.scalar(select(WorkoutSet.rep_count)) == 5
    # an older delete still works: sets have no last-writer-wins
    gone = push(client, h, op("workout_sets", cid, None, now() - timedelta(days=1), "delete"))
    assert gone[0]["status"] == "applied"
    with app.app_context():
        assert count(WorkoutSet) == 0


def test_delete_leaves_a_tombstone_and_blocks_stale_resurrection(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    cid = uid()
    push(client, h, op("food_logs", cid, a_log(), now() - timedelta(hours=1)))
    assert push(client, h, op("food_logs", cid, None, now() - timedelta(minutes=30), "delete"))[0]["status"] == "applied"
    last = pull(client, h)["changes"][-1]
    assert (last["client_id"], last["op"], last["data"]) == (cid, "delete", None)
    assert push(client, h, op("food_logs", cid, None, now(), "delete"))[0]["status"] == "duplicate"
    stale = push(client, h, op("food_logs", cid, a_log(), now() - timedelta(minutes=45)))
    assert stale[0]["status"] == "conflict"
    with app.app_context():
        assert count(FoodLog) == 0
    fresh = push(client, h, op("food_logs", cid, a_log(), now() + timedelta(minutes=1)))
    assert fresh[0]["status"] == "applied"  # a genuinely newer edit may resurrect


def test_pull_pages_by_cursor(app: Flask, client: FlaskClient, h: dict[str, str]) -> None:
    push(client, h, *[op("weight_logs", uid(), {"kg": 60 + i, "date": (TODAY - timedelta(days=i)).isoformat()})
                      for i in range(5)])  # fmt: skip
    seen: list[int] = []
    cursor, pages = 0, 0
    while True:
        page = pull(client, h, cursor, limit=2)
        seen += [c["cursor"] for c in page["changes"]]
        pages += 1
        assert len(page["changes"]) <= 2
        if not page["has_more"]:
            break
        cursor = page["next_cursor"]
    assert (len(seen), pages) == (5, 3)
    assert seen == sorted(set(seen))
    assert pull(client, h, page["next_cursor"])["next_cursor"] == page["next_cursor"]  # empty: unchanged
    for bad in ("cursor=-1", "cursor=x", "limit=0", "limit=1001", f"cursor={2**63}"):
        assert client.get(f"/api/sync/pull?{bad}", headers=h).status_code == 400, bad


def test_users_never_see_each_others_changes_or_ops(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    other = signup(client, "bob")
    cid, shared_op = uid(), uid()
    push(client, h, op("workout_sets", cid, a_set(), op_id=shared_op))
    assert pull(client, other)["changes"] == []
    # same op_id from another user is that user's own op, not a replay of the first
    mine = push(client, other, op("food_logs", uid(), a_log(), op_id=shared_op))
    assert mine[0]["status"] == "applied"
    # bob cannot delete alice's row by guessing its client_id
    assert push(client, other, op("workout_sets", cid, None, action="delete"))[0]["status"] == "duplicate"
    assert [c["entity"] for c in pull(client, other)["changes"]] == ["food_logs"]
    assert [c["entity"] for c in pull(client, h)["changes"]] == ["workout_sets"]
    with app.app_context():
        assert count(WorkoutSet) == 1


@pytest.mark.parametrize(
    ("entity", "data", "needle"),
    [
        ("workout_sets", a_set(rep_count=0), "rep_count"),
        ("workout_sets", a_set(exercise_slug="nope"), "unknown exercise_slug"),
        ("workout_sets", a_set(exercise_slug="bodyweight-squat"), "pose-tracked"),
        ("workout_sets", a_set(load_kg=5000), "load_kg"),
        ("workout_sets", a_set(now() - timedelta(days=800)), "year old"),
        ("workout_sets", a_set(exercise_type="squat", exercise_slug="bodyweight-squat",
                               clean_count=1, attempted_count=1, formscore=1), "must equal"),
        ("workout_sets", a_set() | {"timestamp": "2026-01-01T00:00:00"}, "timestamp"),
        ("workout_sets", a_set() | {"extra": 1}, "extra"),
        ("food_logs", a_log(date=None), "date is required"),
        ("food_logs", a_log(date=(TODAY + timedelta(days=900)).isoformat()), "within one year"),
        ("food_logs", a_log(food_slug="snap", quantity_g=10), "exactly one"),
        ("food_logs", {"meal": "lunch", "date": TODAY.isoformat(), "food_slug": "nope", "quantity_g": 5},
         "unknown food_slug"),
        ("food_logs", {"meal": "lunch", "date": TODAY.isoformat(), "food_slug": "snap", "serving_id": 9999},
         "serving does not belong"),
        ("food_logs", a_log(kcal=None), "kcal"),
        ("weight_logs", {"kg": 5, "date": TODAY.isoformat()}, "kg"),
        ("weight_logs", {"kg": 70}, "date is required"),
        ("profile", PROFILE | {"height_cm": 10}, "height_cm"),
        ("user_plans", {"slug": "nope", "start_date": TODAY.isoformat(), "equipment": "gym"}, "unknown plan"),
        ("plan_completions", {"day_index": 0, "date": TODAY.isoformat()}, "no plan assigned"),
    ],
)
def test_pushed_data_reuses_the_rest_validation(
    client: FlaskClient, h: dict[str, str], entity: str, data: dict[str, Any], needle: str
) -> None:
    cid = {"profile": "profile", "user_plans": "plan"}.get(entity, uid())
    result = push(client, h, op(entity, cid, data))[0]
    assert result["status"] == "rejected", result
    assert needle in result["error"]


def test_one_bad_op_does_not_lose_the_others(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    bad_id = uid()
    ops = [
        op("weight_logs", uid(), {"kg": 70, "date": TODAY.isoformat()}),
        op("workout_sets", uid(), a_set(rep_count=0)),
        op("bogus", uid(), {}, op_id=bad_id),
        op("workout_sets", "not-a-uuid", a_set()),
        op("profile", "wrong", PROFILE),
        op("profile", "profile", None, action="delete"),
        op("food_logs", uid(), a_log()),
    ]
    results = push(client, h, *ops)
    assert statuses(results) == ["applied", "rejected", "rejected", "rejected", "rejected", "rejected", "applied"]
    assert results[2]["op_id"] == bad_id
    assert [r["op_id"] for r in results] == [o["op_id"] for o in ops]
    with app.app_context():
        assert (count(WeightLog), count(FoodLog), count(WorkoutSet)) == (1, 1, 0)
    r = client.post("/api/sync/push", headers=h, json={"device_id": "d", "ops": ["junk", 5]})
    assert r.status_code == 400  # ops must be objects


def test_batch_limits_and_envelope(client: FlaskClient, h: dict[str, str]) -> None:
    many = [op("food_logs", uid(), a_log()) for _ in range(200)]
    body = {"device_id": "d", "ops": many}
    assert len(client.post("/api/sync/push", headers=h, json=body).get_data()) < 1024 * 1024
    assert statuses(push(client, h, *many)) == ["duplicate"] * 0 + ["applied"] * 200
    too_many = client.post("/api/sync/push", headers=h, json={"device_id": "d", "ops": many + many[:1]})
    assert too_many.status_code == 400
    assert client.post("/api/sync/push", headers=h, json={"ops": []}).status_code == 400  # device_id
    assert client.post("/api/sync/push", json=body).status_code == 401


def test_push_body_limit_is_1mb_for_this_route_only(client: FlaskClient, h: dict[str, str]) -> None:
    huge = client.post("/api/sync/push", headers=h, data=b"x" * (1024 * 1024 + 1),
                       content_type="application/json")  # fmt: skip
    assert huge.status_code == 413
    assert "error" in huge.get_json()
    fat = {"device_id": "d", "ops": [op("food_logs", uid(), a_log(name="x" * 128)) for _ in range(150)]}
    assert len(json.dumps(fat)) > 16 * 1024
    r = client.post("/api/sync/push", headers=h, json=fat)
    assert r.status_code == 200  # a 16 KB+ body is fine here
    other = client.post("/api/sets", headers=h, data=b"x" * (17 * 1024), content_type="application/json")
    assert other.status_code == 413  # the global limit still guards every other route


# ---- edits made through the old REST routes reach pull ----


def test_rest_edits_show_up_in_pull_including_deletes(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    day = TODAY.isoformat()
    assert client.post("/api/sets", headers=h, json={
        "exercise_type": "other", "exercise_slug": "bench-press", "rep_count": 5, "load_kg": 40,
    }).status_code == 201  # fmt: skip
    assert client.put("/api/profile", headers=h, json=PROFILE).status_code == 200
    log = client.post("/api/nutrition/log", headers=h, json={
        "meal": "lunch", "food_slug": "snap", "quantity_g": 100}).get_json()  # fmt: skip
    assert client.post("/api/weight", headers=h, json={"kg": 70, "date": day}).status_code == 201
    assert client.post("/api/me/plan", headers=h, json={"slug": PLAN_SLUG}).status_code == 201
    assert client.post("/api/me/plan/complete-day", headers=h, json={}).status_code == 201
    first = pull(client, h)
    assert sorted({c["entity"] for c in first["changes"]}) == [
        "food_logs", "plan_completions", "profile", "user_plans", "weight_logs", "workout_sets"]
    cursor = first["next_cursor"]

    assert client.patch(f"/api/nutrition/log/{log['id']}", headers=h, json={"quantity_g": 200}).status_code == 200
    assert client.post("/api/weight", headers=h, json={"kg": 71, "date": day}).status_code == 201
    edits = pull(client, h, cursor)["changes"]
    by_entity = {c["entity"]: c for c in edits}
    assert by_entity["food_logs"]["data"]["quantity_g"] == 200
    assert by_entity["food_logs"]["data"]["computed"]["kcal"] == 400
    assert by_entity["weight_logs"]["data"]["kg"] == 71
    cursor = pull(client, h, cursor)["next_cursor"]

    client.delete(f"/api/nutrition/log/{log['id']}", headers=h)
    with app.app_context():
        weight_id = db.session.scalar(select(WeightLog.id))
    client.delete(f"/api/weight/{weight_id}", headers=h)
    # re-assigning the plan restarts the rotation: its completion is deleted as well
    client.post("/api/me/plan", headers=h, json={"slug": PLAN_SLUG, "equipment": "home"})
    tombs = pull(client, h, cursor)["changes"]
    assert sorted((c["entity"], c["op"]) for c in tombs) == [
        ("food_logs", "delete"), ("plan_completions", "delete"), ("user_plans", "upsert"),
        ("weight_logs", "delete")]
    assert all(c["data"] is None for c in tombs if c["op"] == "delete")
    # the phone's own pushed row is later edited by REST: the phone's client_id is what it sees
    cid = uid()
    push(client, h, op("weight_logs", cid, {"kg": 60, "date": (TODAY - timedelta(days=3)).isoformat()}))
    with app.app_context():
        row_id = db.session.scalar(select(WeightLog.id).where(WeightLog.client_id == cid))
    client.post("/api/weight", headers=h, json={"kg": 61, "date": (TODAY - timedelta(days=3)).isoformat()})
    assert row_id
    last = pull(client, h)["changes"][-1]
    assert (last["client_id"], last["data"]["kg"]) == (cid, 61)


def test_deleting_the_account_removes_sync_state(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    push(client, h, op("food_logs", uid(), a_log()))
    r = client.delete("/api/me", headers=h, json={"password": "strong-password-123"})
    assert r.status_code == 204
    with app.app_context():
        assert (count(SyncChange), count(SyncOp), count(FoodLog)) == (0, 0, 0)


def test_pull_of_500_changes_uses_a_constant_number_of_queries(
    app: Flask, client: FlaskClient, h: dict[str, str]
) -> None:
    for _ in range(2):
        push(client, h, *[op("workout_sets", uid(), a_set()) for _ in range(150)])
    push(client, h, *[op("food_logs", uid(), FOOD) for _ in range(100)])
    push(client, h, *[op("weight_logs", uid(), {"kg": 70, "date": (TODAY - timedelta(days=i)).isoformat()})
                      for i in range(100)])  # fmt: skip
    seen: list[str] = []

    def hook(*args: Any) -> None:  # noqa: ANN401
        seen.append(args[2])

    with app.app_context():
        engine = db.engine
    event.listen(engine, "before_cursor_execute", hook)
    try:
        got = pull(client, h, limit=500)
    finally:
        event.remove(engine, "before_cursor_execute", hook)
    assert (len(got["changes"]), got["has_more"]) == (500, False)
    assert len(seen) <= 9, seen  # auth + changes + one per entity (+ food eager load), not per row


# ---- catalog ----


def test_catalog_version_bumps_on_every_import_run(app: Flask, client: FlaskClient) -> None:
    def versions() -> dict[str, int]:
        return client.get("/api/catalog/version").get_json()

    with app.app_context():
        # the fixture imported foods + plans once each
        assert versions() == {"foods": 1, "exercises": 0, "machines": 0, "muscles": 0, "plans": 1}
        import_foods({"foods": [{"name": "F2", "slug": "f2", "kcal": 1, "protein_g": 1, "carbs_g": 1, "fat_g": 1}]})
        import_plans({"plans": []})  # nothing touched: no bump
        import_catalog({
            "muscles": ["chest"],
            "machines": [{"name": "Rig", "type": "cable"}],
            "exercises": [{"name": "Fly", "slug": "fly", "primary_muscles": ["chest"], "difficulty": "beginner"}],
        })  # fmt: skip
    assert versions() == {"foods": 2, "exercises": 1, "machines": 1, "muscles": 1, "plans": 1}
    with app.app_context():
        import_catalog({"sources": [{"title": "Paper"}]})  # sources are embedded in 4 entities
        assert db.session.get(CatalogMeta, "muscles").version == 1
    assert versions() == {"foods": 3, "exercises": 2, "machines": 2, "muscles": 1, "plans": 2}


def test_catalog_export_has_full_records_etag_304_and_gzip(app: Flask, client: FlaskClient) -> None:
    r = client.get("/api/catalog/export?entity=foods")
    assert r.status_code == 200
    body = r.get_json()
    assert (body["entity"], body["version"], body["total"], len(body["items"])) == ("foods", 1, 1, 1)
    item = body["items"][0]
    assert item["servings"][0]["label"] == "cup"
    assert {"sources", "fiber_g", "slug"} <= set(item)
    etag = r.headers["ETag"]
    assert r.headers["Cache-Control"] == "no-cache"

    cached = client.get("/api/catalog/export?entity=foods", headers={"If-None-Match": etag})
    assert (cached.status_code, cached.get_data()) == (304, b"")
    assert cached.headers["ETag"] == etag
    assert client.get("/api/catalog/export?entity=foods&limit=5", headers={"If-None-Match": etag}).status_code == 200

    zipped = client.get("/api/catalog/export?entity=foods", headers={"Accept-Encoding": "gzip"})
    assert zipped.headers["Content-Encoding"] == "gzip"
    assert gzip.decompress(zipped.get_data()) == r.get_data()

    with app.app_context():
        import_foods({"foods": [{"name": "F2", "slug": "f2", "kcal": 1, "protein_g": 1, "carbs_g": 1, "fat_g": 1}]})
    changed = client.get("/api/catalog/export?entity=foods", headers={"If-None-Match": etag})
    assert changed.status_code == 200
    assert changed.headers["ETag"] != etag
    page = client.get("/api/catalog/export?entity=foods&limit=1&offset=1").get_json()
    assert (page["total"], [i["slug"] for i in page["items"]]) == (2, ["snap"])

    for entity in ("exercises", "machines", "muscles", "plans"):
        assert client.get(f"/api/catalog/export?entity={entity}").status_code == 200
    plan = client.get("/api/catalog/export?entity=plans").get_json()["items"][0]
    assert plan["days"][0]["slots"]
    assert client.get("/api/catalog/export?entity=nope").status_code == 400
    assert client.get("/api/catalog/export").status_code == 400


def test_exercise_export_is_full_record(app: Flask, client: FlaskClient) -> None:
    with app.app_context():
        assert import_catalog({
            "sources": [{"title": "S"}], "muscles": ["chest", "triceps"],
            "machines": [{"name": "Rig", "type": "cable", "sources": ["S"]}],
            "exercises": [{"name": "Fly", "slug": "fly", "primary_muscles": ["chest"],
                           "secondary_muscles": ["triceps"], "machine": "Rig", "difficulty": "beginner",
                           "instructions": "go", "sources": ["S"]}],
        })["errors"] == []  # fmt: skip
    fly = next(i for i in client.get("/api/catalog/export?entity=exercises").get_json()["items"] if i["slug"] == "fly")
    assert fly["secondary_muscles"] == ["triceps"]
    assert (fly["machine"], fly["instructions"], fly["sources"][0]["title"]) == ("Rig", "go", "S")
    assert client.get("/api/catalog/export?entity=muscles").get_json()["items"] == [
        {"name": "chest"}, {"name": "triceps"}]
    assert client.get("/api/catalog/export?entity=machines").get_json()["items"][0]["sources"]


def test_purge_sync_ops_removes_only_old_records(app: Flask, client: FlaskClient, h: dict[str, str]) -> None:
    push(client, h, op("food_logs", uid(), a_log()))
    with app.app_context():
        user_id = db.session.scalar(select(SyncOp.user_id))
        db.session.add(SyncOp(user_id=user_id, op_id="ancient", result={"status": "applied"},
                              created_at=datetime.now(UTC) - timedelta(days=31)))  # fmt: skip
        db.session.commit()
        out = app.test_cli_runner().invoke(args=["purge-sync-ops"])
        assert "purged 1 sync ops" in out.output
        assert count(SyncOp) == 1


# ---- config ----


def test_database_url_and_engine_options(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SAHIREP_DATABASE_URI", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    assert database_uri_from_env() == "sqlite:///sahirep.db"
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@h:5432/d")
    assert database_uri_from_env() == "postgresql+psycopg://u:p@h:5432/d"
    monkeypatch.setenv("DATABASE_URL", "postgres://u:p@h/d")
    assert database_uri_from_env() == "postgresql+psycopg://u:p@h/d"
    monkeypatch.setenv("SAHIREP_DATABASE_URI", "sqlite:///x.db")  # the specific name wins
    assert database_uri_from_env() == "sqlite:///x.db"
    assert engine_options("sqlite:///x.db") == {"pool_pre_ping": True}
    monkeypatch.setenv("SAHIREP_DB_POOL_SIZE", "3")
    pg = engine_options("postgresql+psycopg://u@h/d")
    assert (pg["pool_pre_ping"], pg["pool_size"], pg["max_overflow"]) == (True, 3, 10)


# ---- migration 0008 on a real alembic run ----


def flask_db(db_file: Path, *args: str) -> None:
    env = {**os.environ, "FLASK_APP": "app:create_app", "SAHIREP_JWT_SECRET": "x" * 40,
           "SAHIREP_DATABASE_URI": f"sqlite:///{db_file.as_posix()}"}  # fmt: skip
    done = subprocess.run(  # noqa: S603
        [sys.executable, "-m", "flask", "db", *args], cwd=BACKEND, env=env,
        capture_output=True, text=True, check=False,
    )  # fmt: skip
    assert done.returncode == 0, done.stderr[-2000:]


def test_migration_0008_backfills_every_table_and_seeds_the_feed(tmp_path: Path) -> None:
    path = tmp_path / "mig8.db"
    flask_db(path, "upgrade", "0007")
    con = sqlite3.connect(path)
    try:
        for stmt in (
            "INSERT INTO users (id, username, password_hash) VALUES (1, 'u', 'x'), (2, 'v', 'x')",
            "INSERT INTO user_profiles (user_id, sex, birth_year, height_cm, weight_kg, activity_level, goal)"
            " VALUES (1, 'male', 1990, 170, 70, 'light', 'maintain')",
            "INSERT INTO plans (id, slug, name, level, goal, days_per_week, split, verified, evidence_notes)"
            " VALUES (1, 'p', 'p', 'beginner', 'strength', 3, 'full_body', 0, '[]')",
            "INSERT INTO user_plans (id, user_id, plan_id, start_date, equipment)"
            " VALUES (1, 1, 1, '2026-01-01', 'gym'), (2, 2, 1, '2026-01-01', 'gym')",
            "INSERT INTO plan_completions (user_plan_id, day_index, completed_on)"
            " VALUES (1, 0, '2026-01-02'), (1, 1, '2026-01-03'), (2, 0, '2026-01-02')",
            "INSERT INTO weight_logs (user_id, date, kg) VALUES (1, '2026-01-02', 70), (2, '2026-01-02', 80)",
            "INSERT INTO food_logs (user_id, name, kcal, meal, date) VALUES (1, 'q', 5, 'snack', '2026-01-02')",
            "INSERT INTO workout_sets (user_id, exercise_type, rep_count, clean_count, attempted_count,"
            " formscore, timestamp) VALUES (1, 'squat', 5, 4, 1, 80, '2026-01-05 10:00:00.000000')",
        ):
            con.execute(stmt)
        con.commit()
    finally:
        con.close()

    flask_db(path, "upgrade", "0008")  # this test is about 0008; later heads are tested elsewhere
    con = sqlite3.connect(path)
    try:
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
        assert con.execute("SELECT version_num FROM alembic_version").fetchone() == ("0008",)
        for table in ("user_profiles", "user_plans", "plan_completions", "weight_logs", "food_logs", "workout_sets"):
            rows = con.execute(f"SELECT client_id, updated_at FROM {table}").fetchall()  # noqa: S608
            assert rows
            assert all(c and u for c, u in rows), table
        ids = [c for (c,) in con.execute("SELECT client_id FROM plan_completions")]
        assert len(set(ids)) == 3
        assert con.execute("SELECT client_id FROM user_profiles").fetchone() == ("profile",)
        assert {c for (c,) in con.execute("SELECT client_id FROM user_plans")} == {"plan"}
        assert con.execute("SELECT updated_at = timestamp FROM workout_sets").fetchone() == (1,)
        feed = con.execute("SELECT user_id, entity FROM sync_changes ORDER BY id").fetchall()
        assert feed == [(1, "profile"), (1, "user_plans"), (2, "user_plans"), (1, "plan_completions"),
                        (1, "plan_completions"), (2, "plan_completions"), (1, "weight_logs"),
                        (2, "weight_logs"), (1, "food_logs"), (1, "workout_sets")]  # fmt: skip
        assert dict(con.execute("SELECT entity, version FROM catalog_meta").fetchall()) == dict.fromkeys(
            ("foods", "exercises", "machines", "muscles", "plans"), 1)
        con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, client_id, updated_at, timestamp)"
                    " VALUES (1, 'squat', 1, 'dup', '2026-01-01', '2026-01-01')")  # fmt: skip
        with pytest.raises(sqlite3.IntegrityError):  # unique per user
            con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, client_id, updated_at,"
                        " timestamp) VALUES (1, 'squat', 1, 'dup', '2026-01-01', '2026-01-01')")  # fmt: skip
    finally:
        con.close()

    flask_db(path, "downgrade", "0007")  # rows survive the way back
    con = sqlite3.connect(path)
    try:
        assert con.execute("SELECT COUNT(*) FROM plan_completions").fetchone() == (3,)
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
        assert "client_id" not in str(con.execute("PRAGMA table_info(user_plans)").fetchall())
    finally:
        con.close()
    flask_db(path, "upgrade")


def test_model_and_migration_agree(tmp_path: Path) -> None:
    path = tmp_path / "drift.db"
    flask_db(path, "upgrade")
    flask_db(path, "check")  # alembic: no schema drift between the models and the migrations


def test_user_plan_and_completion_models_still_work_through_orm(app: Flask) -> None:
    with app.app_context():
        assert count(UserPlan) == 0
        assert count(PlanCompletion) == 0
