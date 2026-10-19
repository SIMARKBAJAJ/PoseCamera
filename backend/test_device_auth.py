"""SECURITY_DESIGN.md section 13: device-bound sessions, OTP, threat engine, hash budget.

Enforcement on, MemorySender, security.now patched (a fake clock). check_password_hash is spied
through the app module (security.py calls it there); the dummy-hash path is made instant so the
simulations do not burn 132 ms of scrypt per attempt.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import random
import sqlite3
import threading
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Any

import pytest
from sqlalchemy import func, select

import app as app_module
import security
from app import User, create_app, db
from security import AuthLock, Device, MemorySender, OtpChallenge, SecurityEvent, keyed_hash
from test_sync import flask_db

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path

    from flask import Flask
    from flask.testing import FlaskClient
    from werkzeug.test import TestResponse

PASSWORD = "strong-password-123"
CLIENT = {"X-SahiRep-Client": "android/1"}
SECRET = "test-secret-at-least-32-characters-long"


class Clock:
    def __init__(self) -> None:
        self.t = datetime(2026, 10, 1, 12, 0, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.t

    def advance(self, **delta: float) -> None:
        self.t += timedelta(**delta)


@dataclass
class Dev:
    device_id: str
    token: str
    key: bytes
    access: str


def make_app(tmp_path: Path, name: str, *, enforce: bool = True, testing: bool = True) -> Flask:
    application = create_app(
        database_uri=f"sqlite:///{(tmp_path / name).as_posix()}", jwt_secret=SECRET,
        testing=testing, otp_sender=MemorySender(),
    )  # fmt: skip
    application.config["DEVICE_ENFORCE"] = enforce
    return application


@pytest.fixture
def clock(monkeypatch: pytest.MonkeyPatch) -> Clock:
    fake = Clock()
    monkeypatch.setattr(security, "now", fake)
    return fake


@pytest.fixture
def app(tmp_path: Path, clock: Clock) -> Generator[Flask, None, None]:  # noqa: ARG001
    application = make_app(tmp_path, "dev.db")
    yield application
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


@pytest.fixture
def hashes(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    calls: list[str] = []
    real = app_module.check_password_hash

    def spy(password_hash: str, password: str) -> bool:
        calls.append(password_hash)
        return False if password_hash == app_module.DUMMY_HASH else real(password_hash, password)

    monkeypatch.setattr(app_module, "check_password_hash", spy)
    return calls


def outbox(client: FlaskClient) -> list[dict[str, str]]:
    return client.application.extensions.setdefault("otp_outbox", [])


def verify(client: FlaskClient, challenge: str, code: str, device_id: str) -> TestResponse:
    body = {"challenge_id": challenge, "code": code, "device_id": device_id}
    return client.post("/api/auth/otp/verify", json=body)


def as_dev(device_id: str, body: dict[str, Any]) -> Dev:
    return Dev(device_id, body["device_token"], base64.b64decode(body["sig_key"]), body["access_token"])


def enrol(client: FlaskClient, name: str = "victim", device_id: str = "dev-a") -> Dev:
    body = {"username": name, "password": PASSWORD, "email": f"{name}@example.com", "device_id": device_id}
    r = client.post("/api/auth/register", json=body, headers=CLIENT)
    assert r.status_code == 202, r.json
    assert r.json["email_hint"] == f"{name[0]}***@example.com"
    v = verify(client, r.json["challenge_id"], outbox(client)[-1]["code"], device_id)
    assert v.status_code == 200, v.json
    return as_dev(device_id, v.json)


def sign(key: bytes, ts: str, raw: bytes) -> str:
    msg = f"POST\n/api/auth/login\n{ts}\n{hashlib.sha256(raw).hexdigest()}"
    return hmac.new(key, msg.encode(), hashlib.sha256).hexdigest()


def login(  # noqa: PLR0913
    client: FlaskClient, name: str = "victim", password: str = PASSWORD, *, dev: Dev | None = None,
    device_id: str | None = "dev-new", ip: str = "127.0.0.1", token: str | None = None,
    ts: str | None = None, sig: str | None = None, headers: dict[str, str] | None = None,
) -> TestResponse:  # fmt: skip
    body: dict[str, str] = {"username": name, "password": password}
    did = dev.device_id if dev else device_id
    if did:
        body["device_id"] = did
    if dev:
        body["device_token"] = token or dev.token
    raw = json.dumps(body).encode()
    sent = dict(CLIENT if headers is None else headers)
    if dev:
        stamp = ts if ts is not None else str(int(security.now().timestamp()))
        sent |= {"X-SahiRep-Ts": stamp, "X-SahiRep-Sig": sig if sig is not None else sign(dev.key, stamp, raw)}
    return client.post("/api/auth/login", data=raw, content_type="application/json", headers=sent,
                       environ_base={"REMOTE_ADDR": ip})  # fmt: skip


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def events(app: Flask, kind: str) -> list[SecurityEvent]:
    with app.app_context():
        rows = list(db.session.scalars(select(SecurityEvent).where(SecurityEvent.kind == kind)))
        db.session.expunge_all()
        return rows


def account_lock(app: Flask, name: str) -> AuthLock | None:
    with app.app_context():
        row = db.session.get(AuthLock, ("account", keyed_hash(f"acct:{name}")))
        if row:
            db.session.expunge(row)
        return row


def hammer(client: FlaskClient, clock: Clock, name: str = "victim", limit: int = 60) -> list[int]:
    """Wrong passwords from rotating IPs, honouring Retry-After, until the account locks.
    Returns the Retry-After values seen; the last one is the lock's."""
    waits = []
    for i in range(limit):
        r = login(client, name, "wrong-password-0", ip=f"10.{i % 250}.0.1")
        if r.status_code == 429:  # noqa: PLR2004
            waits.append(int(r.headers["Retry-After"]))
            if waits[-1] >= 600:  # noqa: PLR2004
                return waits
            clock.advance(seconds=waits[-1])
        else:
            assert r.status_code == 401
    pytest.fail(f"no lock after {limit} attempts: {waits}")


# ---- 1. new-device flow ----


def test_new_device_needs_otp_then_signed_login_rotates(app: Flask, client: FlaskClient) -> None:
    enrol(client)
    r = login(client, device_id="dev-b")
    assert r.status_code == 202
    assert r.json["status"] == "otp_required"
    assert (r.json["expires_in"], r.json["resend_after"]) == (600, 60)
    assert len(outbox(client)) == 2  # register's verify_email + this login
    v = verify(client, r.json["challenge_id"], outbox(client)[-1]["code"], "dev-b")
    assert v.status_code == 200
    assert set(v.json) == {"access_token", "device_token", "sig_key"}
    dev = as_dev("dev-b", v.json)
    assert client.get("/api/history", headers=bearer(dev.access)).status_code == 200

    signed = login(client, dev=dev)
    assert signed.status_code == 200
    assert set(signed.json) == {"access_token", "device_token"}  # sig_key only at enrolment
    assert signed.json["device_token"] != dev.token
    with app.app_context():
        row = db.session.scalar(select(Device).where(Device.device_id == "dev-b"))
        assert row.rotation == 1
        assert row.prev_token_hash == keyed_hash(f"tok:{dev.token}")
    assert len(outbox(client)) == 2  # no OTP for a trusted device


# ---- 2. signature ----


def test_bad_signatures_are_refused_without_hashing(
    app: Flask, client: FlaskClient, clock: Clock, hashes: list[str]
) -> None:
    dev = enrol(client)
    hashes.clear()
    old = str(int(clock().timestamp()) - 301)
    for kwargs in ({"sig": ""}, {"sig": "00" * 32}, {"ts": old}, {"token": "not-the-token"}):
        r = login(client, dev=dev, **kwargs)
        assert (r.status_code, r.json) == (401, {"error": "invalid username or password"}), kwargs
    no_headers = login(client, dev=dev, sig="", ts="")
    assert no_headers.status_code == 401
    assert hashes == []
    assert len(events(app, "bad_signature")) == 5
    wrong_password = login(client, password="wrong-password-0", dev=dev)
    assert wrong_password.status_code == 401
    assert len(hashes) == 1


# ---- 3. replay ----


def test_replayed_token_revokes_device_and_second_replay_locks(app: Flask, client: FlaskClient) -> None:
    dev = enrol(client)
    first = login(client, dev=dev)
    assert first.status_code == 200
    jwt_of_device = first.json["access_token"]
    assert client.get("/api/history", headers=bearer(jwt_of_device)).status_code == 200

    replay = login(client, dev=dev)  # dev.token is now prev_token_hash
    assert replay.status_code == 202
    assert replay.json["status"] == "otp_required"
    assert client.get("/api/history", headers=bearer(jwt_of_device)).status_code == 401
    assert client.get("/api/history", headers=bearer(dev.access)).status_code == 401
    assert len(events(app, "token_replay")) == 1
    with app.app_context():
        assert db.session.scalar(select(Device.revoked_reason)) == "replay"
    assert account_lock(app, "victim") is None

    again = login(client, dev=dev)
    assert again.status_code == 429
    assert int(again.headers["Retry-After"]) > 0
    assert account_lock(app, "victim") is not None
    assert login(client, device_id="dev-c").status_code == 429  # untrusted devices locked out


def test_token_reused_after_logout_counts_as_replay(app: Flask, client: FlaskClient) -> None:
    dev = enrol(client)
    assert client.post("/api/auth/logout", headers=bearer(dev.access)).status_code == 200
    assert client.get("/api/history", headers=bearer(dev.access)).status_code == 401
    r = login(client, dev=dev)
    assert r.status_code == 202
    assert len(events(app, "token_replay")) == 1


def test_logout_only_drops_this_device_and_logout_all_drops_every_one(client: FlaskClient) -> None:
    a = enrol(client)
    r = login(client, device_id="dev-b")
    b = as_dev("dev-b", verify(client, r.json["challenge_id"], outbox(client)[-1]["code"], "dev-b").json)
    assert client.post("/api/auth/logout", headers=bearer(a.access)).status_code == 200
    assert client.get("/api/history", headers=bearer(a.access)).status_code == 401
    assert client.get("/api/history", headers=bearer(b.access)).status_code == 200
    assert client.post("/api/auth/logout-all", headers=bearer(b.access)).status_code == 200
    assert client.get("/api/history", headers=bearer(b.access)).status_code == 401
    assert login(client, dev=b).status_code == 202  # revoked: OTP again


# ---- 4. OTP rules ----


def challenge(client: FlaskClient, device_id: str = "dev-b") -> tuple[str, str]:
    r = login(client, device_id=device_id)
    assert r.status_code == 202
    return r.json["challenge_id"], outbox(client)[-1]["code"]


def wrong(code: str) -> str:
    return f"{(int(code) + 1) % 10**6:06d}"


def test_otp_is_single_use_and_expires(client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    cid, code = challenge(client)
    assert verify(client, cid, code, "dev-b").status_code == 200
    assert verify(client, cid, code, "dev-b").status_code == 400
    cid, code = challenge(client, "dev-c")
    clock.advance(seconds=601)
    assert verify(client, cid, code, "dev-c").status_code == 400


def test_five_wrong_codes_kill_the_challenge_and_device_must_match(client: FlaskClient) -> None:
    enrol(client)
    cid, code = challenge(client)
    assert verify(client, cid, code, "someone-else").status_code == 400
    for _ in range(4):
        assert verify(client, cid, wrong(code), "dev-b").status_code == 400
    assert verify(client, cid, code, "dev-b").status_code == 400  # 6th try, right code, dead


def test_resend_cooldown_keeps_attempts_and_hourly_cap(app: Flask, client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    cid, code = challenge(client)
    resend = {"challenge_id": cid, "device_id": "dev-b"}
    early = client.post("/api/auth/otp/resend", json=resend)
    assert early.status_code == 429
    assert early.headers["Retry-After"]
    for _ in range(3):
        assert verify(client, cid, wrong(code), "dev-b").status_code == 400
    clock.advance(seconds=61)
    again = client.post("/api/auth/otp/resend", json=resend)
    assert again.status_code == 202
    new_code = outbox(client)[-1]["code"]
    for _ in range(2):
        assert verify(client, cid, wrong(new_code), "dev-b").status_code == 400
    assert verify(client, cid, new_code, "dev-b").status_code == 400  # attempts were not reset
    with app.app_context():
        assert db.session.get(OtpChallenge, cid).attempts == 5

    clock.advance(hours=2)  # fresh hour: 5 OTPs allowed, the 6th refused
    for i in range(5):
        assert login(client, device_id=f"dev-{i}").status_code == 202
    sixth = login(client, device_id="dev-9")
    assert sixth.status_code == 429


def test_resend_is_capped_at_three(client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    cid, _ = challenge(client)
    resend = {"challenge_id": cid, "device_id": "dev-b"}
    codes = []
    for _ in range(4):
        clock.advance(seconds=61)
        codes.append(client.post("/api/auth/otp/resend", json=resend).status_code)
    assert codes == [202, 202, 202, 429]


def test_code_hash_is_keyed(app: Flask, client: FlaskClient) -> None:
    enrol(client)
    cid, code = challenge(client)
    with app.app_context():
        stored = db.session.get(OtpChallenge, cid).code_hash
    for plain in (code, hashlib.sha256(code.encode()).hexdigest(),
                  hashlib.sha256(f"{cid}:{code}".encode()).hexdigest()):  # fmt: skip
        assert stored != plain


# ---- 5. brute force ----


def test_brute_force_gets_progressive_delays_then_lock_but_trusted_device_works(
    app: Flask, client: FlaskClient, clock: Clock, hashes: list[str]
) -> None:
    dev = enrol(client)
    waits = hammer(client, clock)
    delays = waits[:-1]
    assert delays
    assert delays == sorted(delays)  # progressive
    assert delays[-1] > delays[0]
    assert 600 <= waits[-1] <= 900  # 15 min lock
    lock = account_lock(app, "victim")
    assert lock is not None
    assert lock.level == 0
    failures = len(events(app, "login_fail"))
    assert failures <= 20
    hashes.clear()
    assert login(client, device_id="dev-x").status_code == 429  # still locked, no hash
    assert hashes == []
    trusted = login(client, dev=dev, ip="10.200.0.1")
    assert trusted.status_code == 200


# ---- 6. distributed stuffing simulation ----


def test_distributed_stuffing_trips_attack_mode_blocks_ips_and_bounds_hashes(
    app: Flask, client: FlaskClient, clock: Clock, hashes: list[str]
) -> None:
    dev = enrol(client)
    rng = random.Random(50_000)
    ips = [f"100.{64 + k % 60}.{k // 60}.{k % 200 + 1}" for k in range(1800)]  # 60 /24s
    names = ["victim"] + [f"user{n}" for n in range(1999)]  # mostly non-existent
    hashes.clear()
    failed: dict[str, set[str]] = {}
    statuses: dict[int, int] = {}
    for _ in range(3000):
        ip, name = rng.choice(ips), rng.choice(names)
        r = login(client, name, "leaked-password-1", device_id=f"bot-{rng.randrange(10**6)}", ip=ip)
        statuses[r.status_code] = statuses.get(r.status_code, 0) + 1
        if r.status_code == 401:  # noqa: PLR2004
            failed.setdefault(ip, set()).add(name)
        clock.advance(seconds=0.05)

    assert events(app, "attack_mode")
    with app.app_context():
        hot = [ip for ip, users in failed.items() if len(users) >= 3]
        assert hot
        for ip in hot:
            assert db.session.get(AuthLock, ("ip", keyed_hash(f"ip:{ip}"))) is not None, ip
    # Bounded = one hash per answered attempt and none for any refusal. A sequential client cannot
    # show the real cap (concurrent hashes per process, halved in attack mode): asserted below.
    # The count itself is NOT bounded much by design (section 11): ~9 in 10 attempts here hash.
    assert len(hashes) == statuses[401]
    assert statuses.get(429, 0) > 0
    trusted = login(client, dev=dev, ip=ips[0])
    assert trusted.status_code == 200
    budget = security.HashBudget(4)
    with budget.permit(trusted=False, attack=True) as one, budget.permit(trusted=False, attack=True) as two:
        with budget.permit(trusted=False, attack=True) as three:
            assert (one, two, three) == (True, True, False)
        with budget.permit(trusted=True, attack=True) as reserved:
            assert reserved


# ---- 7. hash budget ----


def test_hash_budget_fails_fast_for_untrusted_and_keeps_a_trusted_slot(
    tmp_path: Path, clock: Clock, monkeypatch: pytest.MonkeyPatch  # noqa: ARG001
) -> None:
    monkeypatch.setenv("SAHIREP_HASH_SLOTS", "1")
    application = make_app(tmp_path, "budget.db")
    client = application.test_client()
    dev = enrol(client)
    entered, release = threading.Event(), threading.Event()
    real = app_module.check_password_hash

    def blocking(password_hash: str, password: str) -> bool:
        if threading.current_thread() is not threading.main_thread():
            entered.set()
            release.wait(10)
        return real(password_hash, password)

    monkeypatch.setattr(app_module, "check_password_hash", blocking)
    results: list[int] = []
    worker = threading.Thread(target=lambda: results.append(
        login(application.test_client(), "ghost", device_id="dev-z").status_code))  # fmt: skip
    worker.start()
    try:
        assert entered.wait(10)
        busy = login(client, device_id="dev-y")
        assert (busy.status_code, busy.json) == (429, {"error": "server busy, retry shortly"})
        assert busy.headers["Retry-After"] == "2"
        assert login(client, dev=dev).status_code == 200  # reserved trusted slot
    finally:
        release.set()
        worker.join(10)
    assert results == [401]
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


# ---- 8. slow-roll ----


def test_slow_roll_on_one_account_locks_via_the_7_day_counter(app: Flask, client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    for i in range(40):
        clock.advance(hours=4)  # 40 x 4 h = 6.7 days; every 15-min window holds one failure
        r = login(client, password="wrong-password-0", ip=f"10.{i}.0.1")
        if account_lock(app, "victim") is not None:
            break
        assert r.status_code == 401
    else:
        pytest.fail("slow roll never locked")
    assert i >= 30  # noqa: PLR2004 - only the long window got it there
    scores = [e.score for e in events(app, "login_fail")]
    assert scores[0] == 15
    assert scores[-1] > 60  # noqa: PLR2004


def test_slow_roll_across_a_subnet_adds_20(app: Flask, client: FlaskClient, clock: Clock) -> None:  # noqa: ARG001
    for i in range(51):
        login(client, f"spray{i}", "wrong-password-0", ip=f"10.9.9.{i + 1}", device_id=f"d{i}")
        clock.advance(hours=3)  # 1 per hour window: only the 7-day rule sees it
    scores = [e.score for e in events(app, "login_fail")]
    assert scores[0] == 15
    assert scores[50] == 35


# ---- 9. lock expiry ----


def test_lock_expires_doubles_on_repeat_resets_after_a_quiet_day_and_cli_unlocks(
    app: Flask, client: FlaskClient, clock: Clock
) -> None:
    enrol(client)
    hammer(client, clock)
    first = account_lock(app, "victim")
    assert first.level == 0
    clock.t = security.as_utc(first.until) + timedelta(seconds=1)
    assert login(client, device_id="dev-b").status_code == 202  # works again

    hammer(client, clock)
    second = account_lock(app, "victim")
    assert second.level == 1
    assert security.as_utc(second.until) - clock() == timedelta(minutes=30)

    clock.t = security.as_utc(second.until) + timedelta(hours=25)
    hammer(client, clock)
    assert account_lock(app, "victim").level == 0

    out = app.test_cli_runner().invoke(args=["auth-unlock", "--username", "victim"])
    assert "cleared 1 account lock" in out.output
    assert account_lock(app, "victim") is None
    clock.advance(seconds=301)  # unlock clears the lock; the progressive delay (max 5 min) remains
    assert login(client, device_id="dev-c").status_code == 202


# ---- 10. uniform responses ----


def shape(r: TestResponse) -> tuple[int, Any, list[tuple[str, str]]]:
    return r.status_code, r.json, sorted(r.headers.items())


def test_real_and_fake_usernames_look_identical(app: Flask, client: FlaskClient, hashes: list[str]) -> None:
    enrol(client)
    hashes.clear()
    real = login(client, "victim", "wrong-password-0", ip="10.1.0.1", device_id="d1")
    assert len(hashes) == 1
    fake = login(client, "ghost", "wrong-password-0", ip="10.2.0.1", device_id="d2")
    assert len(hashes) == 2
    assert shape(real) == shape(fake)
    a, b = events(app, "login_fail")
    cols = ("kind", "user_id", "score", "action")
    assert [getattr(a, c) for c in cols] == [getattr(b, c) for c in cols]
    keys = ("account_key", "ip_key", "subnet_key", "device_key")
    assert [bool(getattr(a, c)) for c in keys] == [bool(getattr(b, c)) for c in keys] == [True] * 4

    runner = app.test_cli_runner()  # locks: same answer for both
    for name in ("victim", "ghost"):
        assert runner.invoke(args=["auth-block", "--username", name]).exit_code == 0
    assert shape(login(client, "victim")) == shape(login(client, "ghost"))
    for name in ("victim", "ghost"):
        runner.invoke(args=["auth-unlock", "--username", name])

    budget = app.extensions["security"].budget  # busy: same answer for both
    budget.slots = 1
    with budget.permit(trusted=False) as held:
        assert held
        busy_real, busy_fake = login(client, "victim"), login(client, "ghost")
    assert busy_real.status_code == 429
    assert shape(busy_real) == shape(busy_fake)


def test_every_otp_failure_shares_one_body(client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    cid, code = challenge(client)
    answers = [
        verify(client, "no-such-challenge", code, "dev-b"),
        verify(client, cid, wrong(code), "dev-b"),
        verify(client, cid, code, "other-device"),
        verify(client, cid, "12", "dev-b"),
    ]
    assert verify(client, cid, code, "dev-b").status_code == 200
    answers.append(verify(client, cid, code, "dev-b"))  # used
    cid2, code2 = challenge(client, "dev-c")
    clock.advance(minutes=11)
    answers.append(verify(client, cid2, code2, "dev-c"))  # expired
    assert {(r.status_code, json.dumps(r.json)) for r in answers} == {
        (400, json.dumps({"error": "invalid or expired code"}))}


# ---- 11. legacy, trust-on-first-use, enforcement ----


def test_legacy_login_works_only_while_enforcement_is_off(tmp_path: Path, clock: Clock) -> None:  # noqa: ARG001
    application = make_app(tmp_path, "legacy.db", enforce=False)
    client = application.test_client()
    body = {"username": "oldie", "password": PASSWORD}
    assert client.post("/api/auth/register", json=body).status_code == 201
    old = client.post("/api/auth/login", json=body)
    assert old.status_code == 200
    assert set(old.json) == {"access_token"}
    legacy_jwt = old.json["access_token"]
    assert client.get("/api/history", headers=bearer(legacy_jwt)).status_code == 200

    first = login(client, "oldie", device_id="phone-1", headers={})  # trust-on-first-use
    assert first.status_code == 200
    assert {"device_token", "sig_key"} <= set(first.json)
    assert first.json["email_missing"] is True
    second = login(client, "oldie", device_id="phone-2", headers={})  # no email to send an OTP to
    assert set(second.json) == {"access_token"}

    application.config["DEVICE_ENFORCE"] = True
    assert client.post("/api/auth/login", json=body).status_code == 400
    assert client.get("/api/history", headers=bearer(legacy_jwt)).status_code == 401
    tofu = as_dev("phone-1", first.json)
    assert client.get("/api/history", headers=bearer(tofu.access)).status_code == 200
    add = client.post("/api/auth/email", json={"email": "Oldie@Example.com", "password": PASSWORD},
                      headers=bearer(tofu.access))  # fmt: skip
    assert add.status_code == 202
    v = verify(client, add.json["challenge_id"], outbox(client)[-1]["code"], "phone-1")
    assert v.status_code == 200
    with application.app_context():
        user = db.session.scalar(select(User))
        assert user.email == "oldie@example.com"
        assert user.email_verified_at is not None
        db.session.remove()
        db.engine.dispose()


def test_register_requires_email_and_device_under_enforcement(client: FlaskClient) -> None:
    body = {"username": "newbie", "password": PASSWORD}
    assert client.post("/api/auth/register", json=body, headers=CLIENT).status_code == 400
    bad_email = body | {"email": "not-an-email", "device_id": "d"}
    assert client.post("/api/auth/register", json=bad_email, headers=CLIENT).status_code == 400


# ---- 12. client gate ----


@pytest.mark.parametrize("headers", [{}, {"X-SahiRep-Client": "android/0"}, {"X-SahiRep-Client": "ios/9"}])
def test_missing_or_old_client_is_refused_without_hashing(
    client: FlaskClient, hashes: list[str], headers: dict[str, str]
) -> None:
    r = login(client, "ghost", headers=headers)
    assert (r.status_code, r.json) == (400, {"error": "unsupported client, please update"})
    assert hashes == []


# ---- 13. admin ----


def test_admin_route_and_cli(app: Flask, client: FlaskClient, clock: Clock) -> None:
    enrol(client)
    hammer(client, clock)
    assert client.get("/api/admin/threats").status_code == 404
    app.config["ADMIN_TOKEN"] = "a" * 40
    assert client.get("/api/admin/threats", headers={"X-Admin-Token": "b" * 40}).status_code == 401
    r = client.get("/api/admin/threats", headers={"X-Admin-Token": "a" * 40})
    assert r.status_code == 200
    body = r.json
    assert body["counts"]["login_fail"] >= 1
    assert body["locks"]
    assert all(len(lock["key"]) == 8 for lock in body["locks"])
    assert all(len(key) == 8 for key, _ in body["top_ips"] + body["top_subnets"])
    assert events(app, "admin_view")

    runner = app.test_cli_runner()
    out = runner.invoke(args=["threat-summary", "--hours", "48"])
    assert out.exit_code == 0
    assert json.loads(out.output)["hours"] == 48
    ip_prefix = body["top_ips"][0][0]
    assert runner.invoke(args=["auth-block", "--ip-key", ip_prefix]).exit_code == 0
    assert "cleared 1 ip lock" in runner.invoke(args=["auth-unlock", "--ip-key", ip_prefix]).output
    assert runner.invoke(args=["auth-unlock"]).exit_code != 0
    assert runner.invoke(args=["attack-mode", "on"]).exit_code == 0
    with app.app_context():
        assert app.extensions["security"].engine.attack_mode(clock())
    runner.invoke(args=["attack-mode", "off"])
    with app.app_context():
        assert not app.extensions["security"].engine.attack_mode(clock())
    runner.invoke(args=["attack-mode", "auto"])
    clock.advance(days=40)
    assert "purged" in runner.invoke(args=["purge-security-events", "--days", "30"]).output
    with app.app_context():
        assert db.session.scalar(select(func.count()).select_from(SecurityEvent)) == 0


# ---- 14. account deletion ----


def test_delete_me_with_devices_and_challenges_keeps_events_unlinked(app: Flask, client: FlaskClient) -> None:
    dev = enrol(client)
    challenge(client)  # a pending OTP challenge
    export = client.get("/api/me/export", headers=bearer(dev.access)).json
    assert [set(d) for d in export["devices"]] == [{"label", "created_at", "last_seen_at", "revoked_at"}]
    assert "token" not in json.dumps(export["devices"])
    r = client.delete("/api/me", headers=bearer(dev.access), json={"password": PASSWORD})
    assert r.status_code == 204
    with app.app_context():
        count = lambda m: db.session.scalar(select(func.count()).select_from(m))  # noqa: E731
        assert count(Device) == count(OtpChallenge) == count(User) == 0
        assert count(SecurityEvent) > 0
        assert db.session.scalar(select(func.count()).where(SecurityEvent.user_id.is_not(None))) == 0


# ---- 15. migration ----


def test_migration_0009_up_down_up_keeps_data(tmp_path: Path) -> None:
    path = tmp_path / "mig9.db"
    flask_db(path, "upgrade", "0008")
    con = sqlite3.connect(path)
    try:
        con.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'u', 'x'), (2, 'v', 'x')")
        con.execute("INSERT INTO workout_sets (user_id, exercise_type, rep_count, clean_count, attempted_count,"
                    " formscore, timestamp) VALUES (1, 'squat', 5, 4, 1, 80, '2026-01-05 10:00:00.000000')")  # fmt: skip
        con.execute("INSERT INTO weight_logs (user_id, date, kg) VALUES (2, '2026-01-02', 80)")
        con.commit()
    finally:
        con.close()

    def counts(con: sqlite3.Connection) -> list[int]:
        return [con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]  # noqa: S608
                for t in ("users", "workout_sets", "weight_logs")]  # fmt: skip

    flask_db(path, "upgrade")
    con = sqlite3.connect(path)
    try:
        assert con.execute("SELECT version_num FROM alembic_version").fetchone() == ("0009",)
        assert counts(con) == [2, 1, 1]
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("UPDATE users SET email = 'u@example.com' WHERE id = 1")
        con.execute("INSERT INTO devices (user_id, device_id, token_hash, rotated_at, created_at, last_seen_at)"
                    " VALUES (1, 'd', 'h', '2026-01-01', '2026-01-01', '2026-01-01')")  # fmt: skip
        with pytest.raises(sqlite3.IntegrityError):  # one row per (user, device)
            con.execute("INSERT INTO devices (user_id, device_id, token_hash, rotated_at, created_at,"
                        " last_seen_at) VALUES (1, 'd', 'h', '2026-01-01', '2026-01-01', '2026-01-01')")  # fmt: skip
        with pytest.raises(sqlite3.IntegrityError):  # FK
            con.execute("INSERT INTO otp_challenges (id, user_id, device_id, purpose, code_hash, created_at,"
                        " last_sent_at, expires_at) VALUES ('c', 99, 'd', 'login', 'h', 'x', 'x', 'x')")  # fmt: skip
        with pytest.raises(sqlite3.IntegrityError):  # CHECK
            con.execute("INSERT INTO auth_locks (scope, key, until) VALUES ('planet', 'k', 'x')")
        con.execute("INSERT INTO security_events (created_at, kind) VALUES ('2026-01-01', 'login_fail')")
        con.commit()
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        con.close()

    flask_db(path, "downgrade", "0008")
    con = sqlite3.connect(path)
    try:
        assert counts(con) == [2, 1, 1]
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
        assert "email" not in str(con.execute("PRAGMA table_info(users)").fetchall())
        tables = {t for (t,) in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert not tables & {"devices", "otp_challenges", "security_events", "auth_locks"}
    finally:
        con.close()
    flask_db(path, "upgrade")
    con = sqlite3.connect(path)
    try:
        assert counts(con) == [2, 1, 1]
        assert con.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        con.close()


# ---- 16. limits ----


def test_otp_verify_is_rate_limited_with_json_429(tmp_path: Path) -> None:
    application = make_app(tmp_path, "lim.db", testing=False)
    client = application.test_client()
    body = {"challenge_id": "nope", "code": "000000", "device_id": "d"}
    codes = [client.post("/api/auth/otp/verify", json=body).status_code for _ in range(11)]
    assert codes == [400] * 10 + [429]
    last = client.post("/api/auth/otp/verify", json=body)
    assert last.is_json
    assert "error" in last.json
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


# ---- email binding: a stolen JWT cannot redirect OTPs ----


def add_email(client: FlaskClient, dev: Dev, email: str, password: str | None = PASSWORD) -> TestResponse:
    body = {"email": email} | ({"password": password} if password is not None else {})
    return client.post("/api/auth/email", json=body, headers=bearer(dev.access))


def email_of(app: Flask, name: str) -> tuple[str | None, datetime | None]:
    with app.app_context():
        user = db.session.scalar(select(User).where(User.username == name))
        return user.email, user.email_verified_at


def test_stolen_jwt_cannot_redirect_otps_with_or_without_the_password(app: Flask, client: FlaskClient) -> None:
    dev = enrol(client)  # verified victim@example.com
    sent_before = len(outbox(client))
    assert add_email(client, dev, "attacker@evil.com", password=None).status_code == 400
    assert add_email(client, dev, "attacker@evil.com", password="wrong-password-0").status_code == 401
    assert add_email(client, dev, "attacker@evil.com").status_code == 409  # verified: never replaced
    assert len(outbox(client)) == sent_before
    assert email_of(app, "victim")[0] == "victim@example.com"
    assert [e.action for e in events(app, "email_add")] == ["refused"]
    assert any(e.action == "reauth" for e in events(app, "login_fail"))
    r = login(client, device_id="dev-b")
    assert r.status_code == 202
    assert r.json["email_hint"] == "v***@example.com"
    assert outbox(client)[-1]["to"] == "victim@example.com"


def test_email_stays_pending_until_its_own_otp_is_verified(tmp_path: Path, clock: Clock) -> None:  # noqa: ARG001
    application = make_app(tmp_path, "pending.db", enforce=False)
    client = application.test_client()
    assert client.post("/api/auth/register", json={"username": "oldie", "password": PASSWORD}).status_code == 201
    tofu = as_dev("phone-1", login(client, "oldie", device_id="phone-1", headers={}).json)

    first = add_email(client, tofu, "mine@example.com")
    assert first.status_code == 202
    assert first.json["email_hint"] == "m***@example.com"
    assert outbox(client)[-1]["to"] == "mine@example.com"
    assert email_of(application, "oldie") == (None, None)  # pending only
    # an unverified address never receives login codes (here: no OTP at all, legacy JWT)
    sent = len(outbox(client))
    assert set(login(client, "oldie", device_id="phone-2", headers={}).json) == {"access_token"}
    assert len(outbox(client)) == sent

    second = add_email(client, tofu, "attacker@evil.com")  # a second pending address
    assert second.status_code == 202
    first_code = next(m["code"] for m in outbox(client) if m["to"] == "mine@example.com")
    v = verify(client, first.json["challenge_id"], first_code, "phone-1")
    assert v.status_code == 200
    assert email_of(application, "oldie")[0] == "mine@example.com"
    tofu = as_dev("phone-1", v.json)
    late = verify(client, second.json["challenge_id"], outbox(client)[-1]["code"], "phone-1")
    assert late.status_code == 400  # the verified address is not replaced by the racing request
    assert email_of(application, "oldie")[0] == "mine@example.com"
    assert add_email(client, tofu, "other@example.com").status_code == 409
    with application.app_context():
        db.session.remove()
        db.engine.dispose()


def test_register_keeps_email_pending_and_needs_a_device_for_it(app: Flask, client: FlaskClient) -> None:
    body = {"username": "newbie", "password": PASSWORD, "email": "n@example.com", "device_id": "d-1"}
    r = client.post("/api/auth/register", json=body, headers=CLIENT)
    assert r.status_code == 202
    assert email_of(app, "newbie") == (None, None)
    assert verify(client, r.json["challenge_id"], outbox(client)[-1]["code"], "d-1").status_code == 200
    email, verified_at = email_of(app, "newbie")
    assert email == "n@example.com"
    assert verified_at is not None
    app.config["DEVICE_ENFORCE"] = False
    no_device = {"username": "other", "password": PASSWORD, "email": "o@example.com"}
    assert client.post("/api/auth/register", json=no_device).status_code == 400


def test_trust_on_first_use_is_off_once_enforcement_is_on(tmp_path: Path, clock: Clock) -> None:  # noqa: ARG001
    application = make_app(tmp_path, "tofu.db", enforce=False)
    client = application.test_client()
    assert client.post("/api/auth/register", json={"username": "oldie", "password": PASSWORD}).status_code == 201
    application.config["DEVICE_ENFORCE"] = True
    r = login(client, "oldie", device_id="phone-1")  # zero devices, right password
    assert r.status_code == 202  # OTP, not an enrolment
    assert "device_token" not in r.json
    assert r.json["email_hint"] is None
    assert outbox(client) == []  # and no address to send it to
    with application.app_context():
        assert db.session.scalar(select(func.count()).select_from(Device)) == 0
        db.session.remove()
        db.engine.dispose()


# ---- malformed signature headers ----


@pytest.mark.parametrize(
    ("ts", "sig"),
    [(None, "ÿ" * 64), (None, "é"), (None, "zz" * 32), (None, "AB" * 31), ("1²", None),
     ("-5", None), ("", "")],
)  # fmt: skip
def test_malformed_signature_headers_give_the_same_401_for_real_and_fake_devices(
    app: Flask, client: FlaskClient, hashes: list[str], ts: str | None, sig: str | None
) -> None:
    dev = enrol(client)
    fake = Dev("dev-ghost", "t" * 43, b"k" * 32, "")
    hashes.clear()
    real_r = login(client, dev=dev, ts=ts, sig=sig)
    fake_r = login(client, "ghost", dev=fake, ts=ts, sig=sig)
    assert (real_r.status_code, real_r.json) == (401, {"error": "invalid username or password"})
    assert shape(real_r) == shape(fake_r)
    assert hashes == []
    assert len(events(app, "bad_signature")) == 2


# ---- parallel OTP guesses ----


def race(app: Flask, n: int, body: Any) -> list[int]:  # noqa: ANN401
    barrier = threading.Barrier(n)
    out: list[int] = []

    def guess(i: int) -> None:
        c = app.test_client()
        barrier.wait(10)
        out.append(c.post("/api/auth/otp/verify", json=body(i)).status_code)

    threads = [threading.Thread(target=guess, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    return out


def test_parallel_wrong_guesses_cannot_exceed_five_attempts(app: Flask, client: FlaskClient) -> None:
    enrol(client)
    cid, code = challenge(client)
    codes = race(app, 12, lambda i: {"challenge_id": cid, "code": f"{(int(code) + 1 + i) % 10**6:06d}",
                                     "device_id": "dev-b"})  # fmt: skip
    assert codes == [400] * 12
    with app.app_context():
        assert db.session.get(OtpChallenge, cid).attempts == 5
    assert len(events(app, "otp_fail")) == 5  # only 5 guesses were ever compared
    assert verify(client, cid, code, "dev-b").status_code == 400


def test_parallel_correct_verifies_consume_the_challenge_once(app: Flask, client: FlaskClient) -> None:
    enrol(client)
    cid, code = challenge(client)
    codes = race(app, 4, lambda _i: {"challenge_id": cid, "code": code, "device_id": "dev-b"})
    assert sorted(codes) == [200, 400, 400, 400]
    assert len(events(app, "otp_ok")) == 2  # enrol's verify_email + exactly one of the racers


# ---- secrets never reach logs or the audit log ----


def test_logs_and_events_hold_no_codes_tokens_passwords_or_raw_ips(
    app: Flask, client: FlaskClient, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level("DEBUG")
    raw_ip = "203.0.113.77"
    dev = enrol(client)
    r = login(client, device_id="dev-b", ip=raw_ip)
    cid = r.json["challenge_id"]
    verify(client, cid, wrong(outbox(client)[-1]["code"]), "dev-b")
    b = as_dev("dev-b", verify(client, cid, outbox(client)[-1]["code"], "dev-b").json)
    signed = login(client, dev=b, ip=raw_ip).json
    login(client, dev=b, ip=raw_ip)  # replay of the rotated token
    login(client, "victim", "wrong-password-7", ip=raw_ip)
    login(client, dev=dev, sig="00" * 32, ip=raw_ip)
    add_email(client, dev, "x@example.com", password="wrong-password-8")
    secrets_seen = [m["code"] for m in outbox(client)] + [
        dev.token, b.token, signed["device_token"], base64.b64encode(b.key).decode(),
        PASSWORD, "wrong-password-7", "wrong-password-8", raw_ip,
    ]  # fmt: skip
    with app.app_context():
        rows = db.session.scalars(select(SecurityEvent)).all()
        dump = json.dumps([{c.key: str(getattr(e, c.key)) for c in SecurityEvent.__table__.columns}
                           for e in rows])  # fmt: skip
    assert len(rows) > 10
    for value in secrets_seen:
        assert value not in caplog.text
        assert value not in dump
