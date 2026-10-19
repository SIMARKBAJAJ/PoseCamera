from __future__ import annotations

from typing import TYPE_CHECKING, Any

import pytest

from app import create_app, db
from catalog import Exercise, MuscleGroup, import_catalog

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient


# TEST FIXTURE ONLY: placeholder rows to exercise the importer, not real catalog data.
FIXTURE: dict[str, Any] = {
    "muscles": ["test-muscle-a"],
    "sources": [{"title": "Test Source", "year": 2000}],
    "machines": [{"name": "Test Machine", "type": "cable", "verified": True, "sources": ["Test Source"]}],
    "exercises": [
        {
            "name": "Test One",
            "slug": "test-one",
            "primary_muscles": ["test-muscle-a"],
            "secondary_muscles": ["test-muscle-b"],
            "machine": "Test Machine",
            "difficulty": "beginner",
            "met": 3.5,
            "verified": True,
            "sources": ["Test Source"],
        },
        {
            "name": "Test Two",
            "slug": "test-two",
            "primary_muscles": ["test-muscle-b"],
            "difficulty": "advanced",
            "verified": True,
        },
        {
            "name": "Test Three",
            "slug": "test-three",
            "primary_muscles": ["test-muscle-a"],
            "difficulty": "intermediate",
            "has_pose_tracking": True,
        },
    ],
}


@pytest.fixture
def app(tmp_path: Path) -> Generator[Flask, None, None]:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / 'catalog-test.db').as_posix()}",
        jwt_secret="test-secret-at-least-32-characters-long",
        testing=True,
    )
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    with app.app_context():
        assert import_catalog(FIXTURE)["errors"] == []
    return app.test_client()


def slugs(client: FlaskClient, query: str = "") -> list[str]:
    response = client.get(f"/api/exercises{query}")
    assert response.status_code == 200
    return [e["slug"] for e in response.get_json()["items"]]


def test_import_is_idempotent(app: Flask) -> None:
    # Given a loaded fixture
    with app.app_context():
        first = import_catalog(FIXTURE)
        # When the same file is imported again
        second = import_catalog(FIXTURE)

        # Then nothing is duplicated
        assert first["created"] == {"muscles": 2, "sources": 1, "machines": 1, "exercises": 3}
        assert second["created"] == dict.fromkeys(first["created"], 0)
        assert second["updated"]["exercises"] == 3
        assert db.session.query(Exercise).count() == 3


def test_import_reports_row_errors_without_aborting(app: Flask) -> None:
    # Given bad rows: bad difficulty, unknown machine, unknown source, extra field
    bad = [
        {"name": "A", "slug": "a", "primary_muscles": ["m"], "difficulty": "easy"},
        {"name": "B", "slug": "b", "primary_muscles": ["m"], "difficulty": "beginner", "machine": "nope"},
        {"name": "C", "slug": "c", "primary_muscles": ["m"], "difficulty": "beginner", "sources": ["nope"]},
        {"name": "D", "slug": "d", "primary_muscles": ["m"], "difficulty": "beginner", "x": 1},
    ]
    good = {"name": "E", "slug": "e", "primary_muscles": ["m"], "difficulty": "beginner"}

    # When imported
    with app.app_context():
        report = import_catalog({"exercises": [*bad, good]})

        # Then each bad row is reported by index and the good row still loads
        assert [e["index"] for e in report["errors"]] == [0, 1, 2, 3]
        assert report["created"]["exercises"] == 1
        assert db.session.query(Exercise).one().slug == "e"


def test_unsourced_rows_are_never_verified(client: FlaskClient) -> None:
    # Given fixture rows that claim verified (Test Two has no sources)
    # When fetched
    two = client.get("/api/exercises/test-two").get_json()
    one = client.get("/api/exercises/test-one").get_json()

    # Then only the sourced one stays verified
    assert two["verified"] is False
    assert one["verified"] is True


def test_list_and_detail(client: FlaskClient) -> None:
    # Given the fixture
    # When listing and fetching one
    detail = client.get("/api/exercises/test-one").get_json()

    # Then fields and citations come back
    assert slugs(client) == ["test-one", "test-three", "test-two"]
    assert detail["machine"] == "Test Machine"
    assert detail["primary_muscles"] == ["test-muscle-a"]
    assert detail["secondary_muscles"] == ["test-muscle-b"]
    assert detail["sources"][0]["title"] == "Test Source"
    assert client.get("/api/exercises/missing").status_code == 404


@pytest.mark.parametrize(
    ("query", "expected"),
    [
        ("?muscle=test-muscle-a", ["test-one", "test-three"]),
        ("?muscle=TEST-MUSCLE-B", ["test-one", "test-two"]),
        ("?machine=test machine", ["test-one"]),
        ("?difficulty=advanced", ["test-two"]),
        ("?q=THREE", ["test-three"]),
        ("?q=test&difficulty=beginner&muscle=test-muscle-a", ["test-one"]),
        ("?q=%25", []),
    ],
)
def test_exercise_filters(client: FlaskClient, query: str, expected: list[str]) -> None:
    # Given the fixture, when filtering, then only matches return
    assert slugs(client, query) == expected


def test_machines_and_muscles(client: FlaskClient) -> None:
    # Given the fixture
    # When reading machines and muscles
    machines = client.get("/api/machines").get_json()
    one = client.get(f"/api/machines/{machines[0]['id']}").get_json()

    # Then they are listed and detail 404s for unknown ids
    assert [m["name"] for m in machines] == ["Test Machine"]
    assert one["verified"] is True
    assert one["sources"][0]["title"] == "Test Source"
    assert client.get("/api/machines/999").status_code == 404
    assert client.get("/api/muscles").get_json() == ["test-muscle-a", "test-muscle-b"]


def test_import_catalog_cli(app: Flask, tmp_path: Path) -> None:
    # Given a catalog file
    path = tmp_path / "c.json"
    path.write_text('{"muscles": ["m1"]}', encoding="utf-8")

    # When the CLI command runs
    result = app.test_cli_runner().invoke(args=["import-catalog", str(path)])

    # Then it reports counts
    assert result.exit_code == 0
    assert '"muscles": 1' in result.output


def test_duplicate_list_entries_are_deduped(app: Flask) -> None:
    # Given a row with repeated muscles and sources, plus a good row after it
    rows = [
        {"name": "D", "slug": "d", "primary_muscles": ["c", "c"], "secondary_muscles": ["s", "s"],
         "difficulty": "beginner", "sources": ["Test Source", "Test Source"]},
        {"name": "E", "slug": "e", "primary_muscles": ["c"], "difficulty": "beginner"},
    ]
    with app.app_context():
        # When imported
        report = import_catalog({"sources": FIXTURE["sources"], "exercises": rows})

        # Then nothing errors and both rows load
        assert report["errors"] == []
        assert report["created"]["exercises"] == 2


def test_db_error_is_per_row(app: Flask, monkeypatch: pytest.MonkeyPatch) -> None:
    # Given a row whose apply hits a DB error
    import catalog
    from sqlalchemy.exc import IntegrityError

    real = catalog._upsert  # noqa: SLF001

    def boom(model: Any, key: str, value: str, **fields: Any) -> Any:  # noqa: ANN401
        if value == "bad":
            raise IntegrityError("x", {}, Exception("y"))
        return real(model, key, value, **fields)

    monkeypatch.setattr(catalog, "_upsert", boom)
    rows = [{"name": n, "slug": n, "primary_muscles": ["m"], "difficulty": "beginner"} for n in ("bad", "ok")]
    with app.app_context():
        # When imported, then the bad row is reported and the other loads
        report = import_catalog({"exercises": rows})
        assert [e["index"] for e in report["errors"]] == [0]
        assert db.session.query(Exercise).one().slug == "ok"


def test_verified_without_sources_warns(app: Flask) -> None:
    with app.app_context():
        # Given verified=true and no sources, when imported
        report = import_catalog({"exercises": [FIXTURE["exercises"][1]]})

        # Then it loads unverified and the report says why
        assert report["warnings"][0]["warning"] == "verified ignored: no sources"
        assert db.session.query(Exercise).one().verified is False


def test_non_dict_document_returns_error(app: Flask) -> None:
    with app.app_context():
        assert import_catalog([1])["errors"][0]["error"] == "document must be a JSON object"  # type: ignore[arg-type]


def test_cli_bad_input_exits_nonzero(app: Flask, tmp_path: Path) -> None:
    runner = app.test_cli_runner()
    for text in ("{oops", "[1]"):
        path = tmp_path / "bad.json"
        path.write_text(text, encoding="utf-8")
        result = runner.invoke(args=["import-catalog", str(path)])
        assert result.exit_code != 0
        assert "Error" in result.output
    path.write_bytes(bytes([0xff, 0xfe, 0x00]))
    assert runner.invoke(args=["import-catalog", str(path)]).exit_code != 0


def test_not_found_is_json(client: FlaskClient) -> None:
    assert client.get("/api/exercises/missing").get_json() == {"error": "not found"}
    assert client.get("/api/machines/999").get_json() == {"error": "not found"}


def test_muscle_updated_only_on_change(app: Flask) -> None:
    with app.app_context():
        import_catalog({"muscles": ["m"]})
        assert import_catalog({"muscles": ["m"]})["updated"]["muscles"] == 0
        assert db.session.query(MuscleGroup).count() == 1
