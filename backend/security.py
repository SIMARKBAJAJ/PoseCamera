"""Device-bound sessions and login hardening (SECURITY_DESIGN.md, Phase 1).

Login runs the section 6.2 order: request shape, request signature, locks and threat score, hash
budget, then exactly one password hash. A new device needs password + email OTP.

SAHIREP_DEVICE_ENFORCE (default off, cutover) gates the client header check, the device_id
requirement, refusal of legacy logins/JWTs, required email on register, and every threat-engine
refusal (locks, delays, attack mode). Signatures, replay detection, the hash budget, OTP rules and
the audit log always apply. While it is off, an account with no device rows enrols its first
device without OTP (trust-on-first-use), and an account without email falls back to a legacy JWT.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import logging
import math
import os
import re
import secrets
import threading
import time
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Annotated, Any, Final, NamedTuple, Protocol

import click
from flask import Response, abort, current_app, jsonify, request
from flask_jwt_extended import create_access_token, current_user, get_jwt, jwt_required
from pydantic import StringConstraints
from sqlalchemy import (
    JSON,
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    and_,
    delete,
    distinct,
    func,
    or_,
    select,
    update,
)
from sqlalchemy.orm import Mapped, mapped_column

import app as core
from app import (
    AUTH_LIMIT,
    ApiPayload,
    DeviceId,
    Email,
    Label,
    Password,
    User,
    as_utc,
    db,
    limiter,
    user_key,
)

if TYPE_CHECKING:
    from collections.abc import Iterator

    from flask import Flask

    from app import LoginPayload, RegisterPayload

log = logging.getLogger(__name__)

STAMP = DateTime(timezone=True)
SIG_WINDOW: Final = 300  # seconds of clock skew allowed on X-SahiRep-Ts
OTP_TTL: Final = timedelta(minutes=10)
OTP_RESEND_AFTER: Final = 60
OTP_MAX_ATTEMPTS: Final = 5
OTP_MAX_RESENDS: Final = 3
OTP_PER_HOUR: Final = 5
CLIENT_RE: Final = re.compile(r"^android/([0-9]{1,9})$")
TS_RE: Final = re.compile(r"[0-9]{1,12}")
SIG_RE: Final = re.compile(r"[0-9a-f]{64}")
# Threat-engine weights and thresholds (section 5). Rules of thumb tuned by test_device_auth.py.
DELAY_AT: Final = 30
STEP_UP_AT: Final = 60
LOCK_AT: Final = 100
W_UNKNOWN_DEVICE: Final = 15
W_ACCOUNT_FAIL_15M: Final = 10
W_ACCOUNT_FAIL_24H: Final = (10, 2)  # beyond 10 in 24 h: +2 each
# design said +2 beyond 30; that never reaches LOCK_AT with 40 failures a week (test list 13.8)
W_ACCOUNT_FAIL_7D: Final = (20, 5)
W_IP_FAIL_15M: Final = 2  # low: CGNAT
W_BAD_SIGNATURE: Final = 30
W_SUBNET_1H: Final = (10, 30)  # >= 10 distinct failing usernames in 1 h: +30
W_SUBNET_7D: Final = (50, 20)
W_DEVICE_USERS: Final = (3, 25)
W_REPLAY: Final = (60, 100)  # first replay in 24 h, each further one
W_OTP_FAIL: Final = 15
W_ATTACK: Final = 20
IP_BLOCK_USERS: Final = 3  # distinct failing usernames from one IP in 24 h -> IP block
REGISTER_REFUSE: Final = 5  # registrations from one IP in 24 h
ACCOUNT_LOCK: Final = timedelta(minutes=15)
IP_BLOCK: Final = timedelta(hours=1)
LOCK_CAP: Final = timedelta(hours=24)
LOCK_FORGET: Final = timedelta(hours=24)  # a lock level falls back after this long without trouble
MANUAL: Final = timedelta(days=36500)
ATTACK_FAILS: Final = 200
ATTACK_ACCOUNTS: Final = 100
ATTACK_MIN_FOR_FAKE_RATIO: Final = 50  # the >50% unknown-username rule needs some volume
ATTACK_HOLD: Final = timedelta(minutes=15)
ATTACK_CACHE: Final = timedelta(seconds=30)
BUSY_SAMPLE: Final = 10  # busy_reject events are kept 1 in 10

INVALID_LOGIN: Final = "invalid username or password"
INVALID_CODE: Final = "invalid or expired code"
TOO_MANY: Final = "too many attempts, retry later"
BUSY: Final = "server busy, retry shortly"
UNSUPPORTED: Final = "unsupported client, please update"

def now() -> datetime:
    """The one clock of this module (tests patch it)."""
    return datetime.now(UTC)


# ---- models (migration 0009) ----


class Device(db.Model):
    __tablename__ = "devices"
    __table_args__ = (
        Index("uq_devices_user_device", "user_id", "device_id", unique=True),
        CheckConstraint(
            "revoked_reason IN ('logout','logout_all','replay','admin')",
            name="ck_devices_revoked_reason",
        ),
        {"sqlite_autoincrement": True},  # ids feed K_dev: never reuse one after a delete
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    prev_token_hash: Mapped[str | None] = mapped_column(String(64))
    rotation: Mapped[int] = mapped_column(default=0, server_default="0", nullable=False)
    sig_epoch: Mapped[int] = mapped_column(default=0, server_default="0", nullable=False)
    rotated_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    created_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    last_seen_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    ip_hash: Mapped[str | None] = mapped_column(String(64))
    label: Mapped[str | None] = mapped_column(String(128))
    revoked_at: Mapped[datetime | None] = mapped_column(STAMP)
    revoked_reason: Mapped[str | None] = mapped_column(String(16))


class OtpChallenge(db.Model):
    __tablename__ = "otp_challenges"
    __table_args__ = (
        CheckConstraint("purpose IN ('login','verify_email')", name="ck_otp_challenges_purpose"),
    )

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True, nullable=False)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False)
    purpose: Mapped[str] = mapped_column(String(16), nullable=False)
    code_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    attempts: Mapped[int] = mapped_column(default=0, server_default="0", nullable=False)
    sent_count: Mapped[int] = mapped_column(default=1, server_default="1", nullable=False)
    created_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    last_sent_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(STAMP)
    ip_hash: Mapped[str | None] = mapped_column(String(64))
    # verify_email only: the address being proven; copied to users.email on a correct code
    email: Mapped[str | None] = mapped_column(String(254))


class SecurityEvent(db.Model):
    """Audit log and threat-engine input. Never holds passwords, codes, tokens, keys or raw IPs."""

    __tablename__ = "security_events"
    __table_args__ = (
        Index("ix_security_events_account", "account_key", "kind", "created_at"),
        Index("ix_security_events_ip", "ip_key", "kind", "created_at"),
        Index("ix_security_events_subnet", "subnet_key", "kind", "created_at"),
        Index("ix_security_events_device", "device_key", "created_at"),
        Index("ix_security_events_kind", "kind", "created_at"),
        {"sqlite_autoincrement": True},
    )

    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True
    )
    created_at: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    kind: Mapped[str] = mapped_column(String(24), nullable=False)
    user_id: Mapped[int | None]  # no FK: events outlive the account (delete_me nulls it)
    account_key: Mapped[str | None] = mapped_column(String(64))
    ip_key: Mapped[str | None] = mapped_column(String(64))
    subnet_key: Mapped[str | None] = mapped_column(String(64))
    device_key: Mapped[str | None] = mapped_column(String(64))
    score: Mapped[int | None]
    action: Mapped[str | None] = mapped_column(String(16))
    detail: Mapped[dict[str, Any] | None] = mapped_column(JSON)


class AuthLock(db.Model):
    __tablename__ = "auth_locks"
    __table_args__ = (
        CheckConstraint(
            "scope IN ('account','ip','subnet','device','global')", name="ck_auth_locks_scope"
        ),
    )

    scope: Mapped[str] = mapped_column(String(8), primary_key=True)
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    until: Mapped[datetime] = mapped_column(STAMP, nullable=False)
    level: Mapped[int] = mapped_column(default=0, server_default="0", nullable=False)
    reason: Mapped[str | None] = mapped_column(String(24))


# ---- secrets and keys ----


class SecurityConfigError(RuntimeError):
    pass


def keyed_hash(value: str) -> str:
    """HMAC-SHA256(pepper, value) in hex: a DB leak alone cannot brute-force IPs or OTP codes."""
    pepper = current_app.extensions["security"].pepper
    return hmac.new(pepper, value.encode(), hashlib.sha256).hexdigest()


def token_hash(token: str) -> str:
    return keyed_hash(f"tok:{token}")


def code_hash(challenge_id: str, code: str) -> str:
    return keyed_hash(f"{challenge_id}:{code}")


def sig_key(device: Device) -> bytes:
    """K_dev, re-derived on demand and never stored. A new sig_epoch gives a new key."""
    pepper = current_app.extensions["security"].pepper
    return hmac.new(pepper, f"sig:{device.id}:{device.sig_epoch}".encode(), hashlib.sha256).digest()


def signature(key: bytes, method: str, path: str, ts: str, body: bytes) -> str:
    message = f"{method}\n{path}\n{ts}\n{hashlib.sha256(body).hexdigest()}"
    return hmac.new(key, message.encode(), hashlib.sha256).hexdigest()


class Keys(NamedTuple):
    account: str
    ip: str
    subnet: str
    device: str | None


def request_keys(username: str, device_id: str | None) -> Keys:
    """Pseudonymous keys. IPv4: ip + /24; IPv6: /64 as the ip, /48 as the subnet."""
    raw = request.remote_addr or ""
    try:
        addr = ipaddress.ip_address(raw)
    except ValueError:
        ip = net = raw
    else:
        v4 = addr.version == 4  # noqa: PLR2004
        ip = str(addr) if v4 else str(ipaddress.ip_network(f"{addr}/64", strict=False))
        net = str(ipaddress.ip_network(f"{addr}/{24 if v4 else 48}", strict=False))
    return Keys(
        keyed_hash(f"acct:{username.lower()}"),
        keyed_hash(f"ip:{ip}"),
        keyed_hash(f"net:{net}"),
        keyed_hash(f"dev:{device_id}") if device_id else None,
    )


def mask_email(email: str | None) -> str | None:
    if not email:
        return None
    local, _, domain = email.partition("@")
    return f"{local[:1]}***@{domain}"


# ---- OTP senders ----


class OtpSender(Protocol):
    def send(self, to: str, code: str, purpose: str) -> None: ...


class DevSender:
    """Phase 1: the code goes to the server log at WARNING. Not for production users."""

    def send(self, to: str, code: str, purpose: str) -> None:
        log.warning("DEV OTP for %s (%s): %s", mask_email(to), purpose, code)


class MemorySender:
    """Tests: codes land in app.extensions['otp_outbox']."""

    def send(self, to: str, code: str, purpose: str) -> None:
        outbox = current_app.extensions.setdefault("otp_outbox", [])
        outbox.append({"to": to, "code": code, "purpose": purpose})


# ---- hash budget (6.3) ----


class HashBudget:
    """Caps concurrent password hashes per process; one extra slot is reserved for trusted devices.

    ponytail: per process; several processes need a Redis concurrency limit (section 6.3).
    """

    def __init__(self, slots: int) -> None:
        self.slots = max(1, slots)
        self.used = {"trusted": 0, "untrusted": 0}
        self.cond = threading.Condition()

    @contextmanager
    def permit(self, *, trusted: bool, attack: bool = False) -> Iterator[bool]:
        limits = {"trusted": 1, "untrusted": max(1, self.slots // 2) if attack else self.slots}
        lanes = ("trusted", "untrusted") if trusted else ("untrusted",)
        deadline = time.monotonic() + (0.5 if trusted else 0)  # untrusted never queues
        lane = None
        with self.cond:
            while True:
                lane = next((x for x in lanes if self.used[x] < limits[x]), None)
                left = deadline - time.monotonic()
                if lane or left <= 0:
                    break
                self.cond.wait(left)
            if lane:
                self.used[lane] += 1
        try:
            yield lane is not None
        finally:
            if lane:
                with self.cond:
                    self.used[lane] -= 1
                    self.cond.notify_all()


# ---- threat engine (section 5, 6.4, 6.5) ----


class Stats(NamedTuple):
    a_fail15: int = 0
    a_fail24: int = 0
    a_fail7d: int = 0
    a_replay24: int = 0
    a_otp_fail1h: int = 0
    i_fail15: int = 0
    i_bad_sig1h: int = 0
    i_users24: int = 0
    n_users1h: int = 0
    n_users7d: int = 0
    d_users24: int = 0
    d_bad_sig1h: int = 0
    last_fail: datetime | None = None


def _beyond(count: int, rule: tuple[int, int]) -> int:
    return max(count - rule[0], 0) * rule[1]


def _at_least(count: int, rule: tuple[int, int]) -> int:
    return rule[1] if count >= rule[0] else 0


class ThreatEngine:
    """Windowed counts over indexed security_events; writes only auth_locks.

    ponytail: SQL storage. Moving to Redis swaps stats/locks/set_lock for INCR+EXPIRE counters,
    HyperLogLog distinct counts and SET EX locks (section 10).
    """

    def __init__(self) -> None:
        self.attack_cache: tuple[datetime, bool] | None = None

    def stats(self, keys: Keys, at: datetime) -> Stats:
        e = SecurityEvent

        def ago(**delta: float) -> Any:  # noqa: ANN401
            return e.created_at >= at - timedelta(**delta)

        def n(cond: Any) -> Any:  # noqa: ANN401
            return func.count().filter(cond)

        def users(cond: Any) -> Any:  # noqa: ANN401
            return func.count(distinct(e.account_key)).filter(cond)

        fail = e.kind == "login_fail"
        last = func.max(e.created_at).filter(fail)
        a = db.session.execute(
            select(
                n(fail & ago(minutes=15)), n(fail & ago(hours=24)), n(fail),
                n((e.kind == "token_replay") & ago(hours=24)),
                n((e.kind == "otp_fail") & ago(hours=1)), last,
            ).where(e.account_key == keys.account, ago(days=7))
        ).one()  # fmt: skip
        i = db.session.execute(
            select(
                n(fail & ago(minutes=15)), n((e.kind == "bad_signature") & ago(hours=1)),
                users(fail), last,
            ).where(e.ip_key == keys.ip, ago(hours=24))
        ).one()  # fmt: skip
        s = db.session.execute(
            select(users(fail & ago(hours=1)), users(fail), last).where(
                e.subnet_key == keys.subnet, ago(days=7)
            )
        ).one()
        d = (0, 0)
        if keys.device:
            d = db.session.execute(
                select(users(e.account_key.is_not(None)), n(e.kind == "bad_signature")).where(
                    e.device_key == keys.device, ago(hours=24)
                )
            ).one()
        lasts = [as_utc(x) for x in (a[5], i[3], s[2]) if x is not None]
        return Stats(*a[:5], *i[:3], *s[:2], *d, last_fail=max(lasts, default=None))

    @staticmethod
    def score(s: Stats, *, trusted: bool, attack: bool) -> int:
        """Trusted devices only see signals about the account's own sessions and this device;
        failures driven by untrusted traffic never force OTP on them (lockout-as-a-weapon)."""
        replay = W_REPLAY[0] * min(s.a_replay24, 1) + W_REPLAY[1] * max(s.a_replay24 - 1, 0)
        pts = replay + W_OTP_FAIL * s.a_otp_fail1h + _at_least(s.d_users24, W_DEVICE_USERS)
        pts += W_BAD_SIGNATURE * min(s.d_bad_sig1h, 1)
        if trusted:
            return pts
        pts += W_UNKNOWN_DEVICE + W_ACCOUNT_FAIL_15M * s.a_fail15
        pts += _beyond(s.a_fail24, W_ACCOUNT_FAIL_24H) + _beyond(s.a_fail7d, W_ACCOUNT_FAIL_7D)
        pts += W_IP_FAIL_15M * s.i_fail15 + W_BAD_SIGNATURE * min(s.i_bad_sig1h, 1)
        pts += _at_least(s.n_users1h, W_SUBNET_1H) + _at_least(s.n_users7d, W_SUBNET_7D)
        return pts + (W_ATTACK if attack else 0)

    @staticmethod
    def retry_after(s: Stats, score: int, at: datetime) -> int | None:
        """Progressive delay for scores >= DELAY_AT: refuse until last_fail + min(2^(n-3), 300) s.
        Never sleeps (a sleeping request holds a waitress thread)."""
        if score < DELAY_AT or s.last_fail is None:
            return None
        wait = min(2 ** max(s.a_fail15 - 3, 0), 300)
        left = (s.last_fail + timedelta(seconds=wait) - at).total_seconds()
        return math.ceil(left) if left > 0 else None

    @staticmethod
    def locks(keys: Keys, at: datetime, *, manual_only: bool = False) -> list[AuthLock]:
        pairs = [("account", keys.account), ("ip", keys.ip), ("subnet", keys.subnet)]
        if keys.device:
            pairs.append(("device", keys.device))
        query = select(AuthLock).where(
            or_(*(and_(AuthLock.scope == sc, AuthLock.key == k) for sc, k in pairs)),
            AuthLock.until > at,
        )
        if manual_only:  # trusted lane: only a hard block (flask auth-block) applies
            query = query.where(AuthLock.reason == "manual")
        return list(db.session.scalars(query))

    @staticmethod
    def set_lock(scope: str, key: str, base: timedelta, reason: str, at: datetime) -> AuthLock:
        """Lock for base * 2^level (capped); the level rises on a repeat within LOCK_FORGET."""
        row = db.session.get(AuthLock, (scope, key))
        if row is None:
            row = AuthLock(scope=scope, key=key, level=0)
            db.session.add(row)
        else:
            recent = as_utc(row.until) + LOCK_FORGET > at
            row.level = row.level + 1 if recent else 0
        row.until = at + min(base * 2**row.level, LOCK_CAP)
        row.reason = reason
        record("lock_set", None, at=at, action=scope, detail={"level": row.level, "reason": reason})
        return row

    def after_fail(self, keys: Keys, at: datetime, *, attack: bool) -> None:
        """Runs after a recorded failure: account lock at LOCK_AT, IP block on many usernames."""
        db.session.flush()
        s = self.stats(keys, at)
        active = {(r.scope, r.key) for r in self.locks(keys, at)}
        if self.score(s, trusted=False, attack=attack) >= LOCK_AT and (
            ("account", keys.account) not in active
        ):
            self.set_lock("account", keys.account, ACCOUNT_LOCK, "score", at)
        if s.i_users24 >= IP_BLOCK_USERS and ("ip", keys.ip) not in active:
            self.set_lock("ip", keys.ip, IP_BLOCK, "many_users", at)

    def attack_mode(self, at: datetime) -> bool:
        """Global attack mode (6.4): manual override row, else a 30 s cached auto check."""
        row = db.session.get(AuthLock, ("global", "attack"))
        active = row is not None and as_utc(row.until) > at
        if active and row and row.reason in ("manual_on", "manual_off"):
            return row.reason == "manual_on"
        if self.attack_cache and at - self.attack_cache[0] < ATTACK_CACHE:
            return self.attack_cache[1]
        e = SecurityEvent
        fail = e.kind == "login_fail"
        recent = e.created_at >= at - timedelta(minutes=5)
        fails, accounts, fake, week = db.session.execute(
            select(
                func.count().filter(recent), func.count(distinct(e.account_key)).filter(recent),
                func.count().filter(recent, e.detail["exists"].as_boolean().is_(False)),
                func.count(),
            ).where(fail, e.created_at >= at - timedelta(days=7))
        ).one()  # fmt: skip
        # ponytail: 7-day MEAN per 5 min stands in for the median; the floor dominates at our size
        triggered = (
            fails > max(ATTACK_FAILS, 10 * week / 2016)
            or accounts >= ATTACK_ACCOUNTS
            or (fails >= ATTACK_MIN_FOR_FAKE_RATIO and fake * 2 > fails)
        )
        if triggered:
            if not active:
                record("attack_mode", None, at=at, action="on", detail={"fails_5m": fails})
            if row is None:
                row = AuthLock(scope="global", key="attack", level=0)
                db.session.add(row)
            row.until, row.reason = at + ATTACK_HOLD, "auto"
            db.session.commit()
        self.attack_cache = (at, triggered or active)
        return triggered or active


# ---- events and responses ----


def record(kind: str, keys: Keys | None, *, at: datetime | None = None, **fields: Any) -> None:  # noqa: ANN401
    db.session.add(
        SecurityEvent(
            created_at=at or now(), kind=kind,
            account_key=keys.account if keys else None, ip_key=keys.ip if keys else None,
            subnet_key=keys.subnet if keys else None, device_key=keys.device if keys else None,
            **fields,
        )
    )  # fmt: skip


def refuse(message: str, status: int, retry_after: int | None = None) -> Response:
    response = jsonify(error=message)
    response.status_code = status
    if retry_after is not None:
        response.headers["Retry-After"] = str(max(1, retry_after))
    return response


def locked(locks: list[AuthLock], at: datetime) -> Response:
    until = max(as_utc(r.until) for r in locks)
    return refuse(TOO_MANY, 429, math.ceil((until - at).total_seconds()))


def state() -> SecurityState:
    return current_app.extensions["security"]


def client_ok() -> bool:
    match = CLIENT_RE.match(request.headers.get("X-SahiRep-Client", ""))
    return bool(match) and int(match[1]) >= current_app.config["MIN_CLIENT_VERSION"]


def session_body(user: User, device: Device, token: str, *, with_key: bool) -> dict[str, Any]:
    claims = {"tv": user.token_version, "did": device.id}
    body: dict[str, Any] = {
        "access_token": create_access_token(identity=str(user.id), additional_claims=claims),
        "device_token": token,
    }
    if with_key:  # sig_key leaves the server only at enrolment
        body["sig_key"] = base64.b64encode(sig_key(device)).decode()
    return body


def legacy_body(user: User) -> dict[str, Any]:
    claims = {"tv": user.token_version}
    return {"access_token": create_access_token(identity=str(user.id), additional_claims=claims)}


# ---- devices ----


def enroll(user: User, device_id: str, label: str | None, keys: Keys, at: datetime) -> tuple[Device, str]:
    """Insert or re-arm the device row: new token, revocation cleared, new signing key."""
    device = db.session.scalar(
        select(Device).where(Device.user_id == user.id, Device.device_id == device_id)
    )
    new = device is None
    if device is None:
        device = Device(user_id=user.id, device_id=device_id, rotation=0, sig_epoch=0, created_at=at)
        db.session.add(device)
    token = secrets.token_urlsafe(32)
    device.token_hash, device.prev_token_hash = token_hash(token), None
    device.sig_epoch += 1
    device.rotated_at = device.last_seen_at = at
    device.revoked_at = device.revoked_reason = None
    device.ip_hash = keys.ip
    device.label = label or device.label
    db.session.flush()
    record("device_new" if new else "device_rotated", keys, at=at, user_id=user.id)
    return device, token


def rotate(device: Device, keys: Keys, at: datetime) -> str:
    token = secrets.token_urlsafe(32)
    device.prev_token_hash, device.token_hash = device.token_hash, token_hash(token)
    device.rotation += 1
    device.rotated_at = device.last_seen_at = at
    device.ip_hash = keys.ip
    record("device_rotated", keys, at=at, user_id=device.user_id)
    return token


def revoke(device: Device, reason: str, at: datetime) -> None:
    if device.revoked_at is None:
        device.revoked_at, device.revoked_reason = at, reason
    record("device_revoked", None, at=at, user_id=device.user_id, action=reason)


def check_device(payload: LoginPayload, at: datetime) -> tuple[Device | None, str | None]:
    """Signature + token. Returns (device, 'current' | 'previous') or (device, None) if invalid.

    One join on the username, the same query whether or not the user exists.
    """
    device = db.session.scalar(
        select(Device)
        .join(User, User.id == Device.user_id)
        .where(User.username == payload.username, Device.device_id == payload.device_id)
    )
    ts = request.headers.get("X-SahiRep-Ts", "")
    sent = request.headers.get("X-SahiRep-Sig", "").lower()
    key = sig_key(device) if device else secrets.token_bytes(32)  # same work either way
    expected = signature(key, request.method, request.path, ts, request.get_data())
    # ASCII-only formats: compare_digest raises on non-ASCII str, and str.isdigit accepts e.g. "١٢"
    fresh = TS_RE.fullmatch(ts) is not None and abs(int(ts) - at.timestamp()) <= SIG_WINDOW
    well_formed = SIG_RE.fullmatch(sent) is not None
    sig_ok = hmac.compare_digest(sent.encode() if well_formed else b"", expected.encode())
    if device is None or not fresh or not sig_ok:
        return device, None
    given = token_hash(payload.device_token or "")
    if hmac.compare_digest(given, device.token_hash):
        return device, "current"
    if device.prev_token_hash and hmac.compare_digest(given, device.prev_token_hash):
        return device, "previous"
    return device, None


# ---- OTP ----


def otps_last_hour(user_id: int, at: datetime) -> int:
    e = SecurityEvent
    return db.session.scalar(
        select(func.count()).where(
            e.kind == "otp_sent", e.user_id == user_id, e.created_at >= at - timedelta(hours=1)
        )
    ) or 0


def verified_email(user: User) -> str | None:
    """The only address a login OTP may go to."""
    return user.email if user.email and user.email_verified_at else None


def otp_address(challenge: OtpChallenge, user: User) -> str | None:
    """verify_email codes go to the pending address on the challenge, every other code only to
    the verified address. users.email is written only when a verify_email code is verified."""
    return challenge.email if challenge.purpose == "verify_email" else verified_email(user)


def deliver(challenge: OtpChallenge, user: User, code: str) -> None:
    to = otp_address(challenge, user)
    if not to:
        log.warning("OTP for user %s not sent: no verified email", user.id)
        return
    try:
        state().sender.send(to, code, challenge.purpose)
    except Exception:  # the challenge stays valid: the user can press resend
        log.exception("OTP delivery failed for user %s", user.id)


def new_code(challenge: OtpChallenge) -> str:
    code = f"{secrets.randbelow(10**6):06d}"
    challenge.code_hash = code_hash(challenge.id, code)
    return code


def otp_body(challenge: OtpChallenge, user: User) -> dict[str, Any]:
    return {
        "status": "otp_required", "challenge_id": challenge.id,
        "expires_in": int(OTP_TTL.total_seconds()), "resend_after": OTP_RESEND_AFTER,
        "email_hint": mask_email(otp_address(challenge, user)),
    }  # fmt: skip


def issue_otp(  # noqa: PLR0913
    user: User, device_id: str, purpose: str, keys: Keys, at: datetime, email: str | None = None
) -> Response:
    """email: the pending address for purpose='verify_email' (kept on the challenge only)."""
    if otps_last_hour(user.id, at) >= OTP_PER_HOUR:
        db.session.commit()
        return refuse("too many codes, retry later", 429, 3600)
    challenge = OtpChallenge(
        id=secrets.token_urlsafe(16), user_id=user.id, device_id=device_id, purpose=purpose,
        attempts=0, sent_count=1, created_at=at, last_sent_at=at, expires_at=at + OTP_TTL,
        ip_hash=keys.ip, email=email if purpose == "verify_email" else None,
    )  # fmt: skip
    code = new_code(challenge)
    db.session.add(challenge)
    record("otp_sent", keys, at=at, user_id=user.id, action=purpose)
    db.session.commit()  # before sending: a failed send must not lose the challenge
    deliver(challenge, user, code)
    response = jsonify(otp_body(challenge, user))
    response.status_code = 202
    return response


class OtpVerifyPayload(ApiPayload):
    challenge_id: Annotated[str, StringConstraints(max_length=64)]
    code: Annotated[str, StringConstraints(max_length=16)]  # no pattern: every miss gets one body
    device_id: DeviceId
    device_label: Label | None = None


class OtpResendPayload(ApiPayload):
    challenge_id: Annotated[str, StringConstraints(max_length=64)]
    device_id: DeviceId


class EmailPayload(ApiPayload):
    email: Email
    password: Password  # re-auth: a stolen JWT alone must not bind an inbox


def challenge_key() -> str:
    body = request.get_json(silent=True)
    cid = body.get("challenge_id") if isinstance(body, dict) else None
    return f"otp:{cid}" if cid else core.get_remote_address()


# ---- login and register flows (called from app.py) ----


def login_flow(payload: LoginPayload) -> Response:  # noqa: C901, PLR0911, PLR0912
    at = now()
    enforce = current_app.config["DEVICE_ENFORCE"]
    # 1. request shape (pydantic already ran)
    if enforce and (not client_ok() or payload.device_id is None):
        return refuse(UNSUPPORTED, 400)
    keys = request_keys(payload.username, payload.device_id)
    # 2. signature: a token with a bad signature or unknown token never reaches the hash
    device, token_state = None, None
    if payload.device_token:
        device, token_state = check_device(payload, at)
        if token_state is None:
            record("bad_signature", keys, at=at)
            db.session.commit()
            return refuse(INVALID_LOGIN, 401)
    trusted = token_state == "current" and device is not None and device.revoked_at is None
    # 3. locks and threat score (cutover: only with enforcement on)
    engine, score, attack = state().engine, None, False
    if enforce:
        attack = engine.attack_mode(at)
        if active := engine.locks(keys, at, manual_only=trusted):
            return locked(active, at)
        stats = engine.stats(keys, at)
        score = engine.score(stats, trusted=trusted, attack=attack)
        if not trusted and (wait := engine.retry_after(stats, score, at)):
            return refuse(TOO_MANY, 429, wait)
    # 4. hash budget, 5. exactly one hash (real or dummy)
    with state().budget.permit(trusted=trusted, attack=attack) as ok:
        if not ok:
            if secrets.randbelow(BUSY_SAMPLE) == 0:
                record("busy_reject", keys, at=at)
                db.session.commit()
            return refuse(BUSY, 429, 2)
        user = db.session.scalar(select(User).where(User.username == payload.username))
        good = core.check_password_hash(
            user.password_hash if user else core.DUMMY_HASH, payload.password
        )
    if user is None or not good:
        record("login_fail", keys, at=at, score=score, detail={"exists": user is not None})
        if enforce:
            engine.after_fail(keys, at, attack=attack)
        db.session.commit()
        return refuse(INVALID_LOGIN, 401)
    # correct password: classify the device
    if device is not None and not trusted:  # replay: rotated-out token or revoked device
        revoke(device, "replay", at)
        record("token_replay", keys, at=at, user_id=user.id)
        if enforce:
            db.session.flush()
            replay_score = engine.score(engine.stats(keys, at), trusted=False, attack=attack)
            if replay_score >= LOCK_AT:
                engine.set_lock("account", keys.account, ACCOUNT_LOCK, "replay", at)
                db.session.commit()
                return locked(engine.locks(keys, at), at)
    elif trusted and device is not None and not (score is not None and score >= STEP_UP_AT):
        token = rotate(device, keys, at)
        record("login_ok", keys, at=at, user_id=user.id, score=score)
        db.session.commit()
        return jsonify(session_body(user, device, token, with_key=False))
    if payload.device_id is None:  # legacy client (only reachable with enforcement off)
        record("login_ok", keys, at=at, user_id=user.id, action="legacy")
        db.session.commit()
        return jsonify(legacy_body(user))
    if not enforce:
        has_devices = db.session.scalar(select(Device.id).where(Device.user_id == user.id).limit(1))
        if has_devices is None:  # trust-on-first-use during cutover (fork 1 A)
            new, token = enroll(user, payload.device_id, payload.device_label, keys, at)
            record("login_ok", keys, at=at, user_id=user.id, action="tofu")
            db.session.commit()
            return jsonify(session_body(user, new, token, with_key=True) | {"email_missing": not verified_email(user)})
        if not verified_email(user):  # no proven inbox yet: legacy JWT until enforcement
            record("login_ok", keys, at=at, user_id=user.id, action="legacy")
            db.session.commit()
            return jsonify(legacy_body(user))
    return issue_otp(user, payload.device_id, "login", keys, at)


def register_flow(payload: RegisterPayload) -> Response:
    at = now()
    enforce = current_app.config["DEVICE_ENFORCE"]
    if enforce and not client_ok():
        return refuse(UNSUPPORTED, 400)
    if enforce and not (payload.email and payload.device_id):
        return refuse("email and device_id are required", 400)
    if payload.email and not payload.device_id:  # an email is bound only through its OTP
        return refuse("email needs device_id to be verified", 400)
    keys = request_keys(payload.username, payload.device_id)
    attack = False
    if enforce:
        attack = state().engine.attack_mode(at)
        if active := state().engine.locks(keys, at):
            return locked(active, at)
        e = SecurityEvent
        regs = db.session.scalar(
            select(func.count()).where(
                e.kind == "register", e.ip_key == keys.ip, e.created_at >= at - timedelta(hours=24)
            )
        )
        if (regs or 0) >= REGISTER_REFUSE:
            return refuse(TOO_MANY, 429, 3600)
    if db.session.scalar(select(User.id).where(User.username == payload.username)) is not None:
        return refuse("username already exists", 409)  # accepted leak (6.6)
    with state().budget.permit(trusted=False, attack=attack) as ok:
        if not ok:
            return refuse(BUSY, 429, 2)
        password_hash = core.generate_password_hash(payload.password)
    user = User()
    user.username = payload.username
    user.password_hash = password_hash
    user.consent_at = at if payload.consent else None
    # random start: SQLite reuses ids after delete, so a deleted user's token must not match
    user.token_version = secrets.randbelow(2**31 - 1) + 1
    db.session.add(user)
    db.session.flush()
    record("register", keys, at=at, user_id=user.id)
    if payload.email and payload.device_id:
        return issue_otp(user, payload.device_id, "verify_email", keys, at, email=payload.email)
    db.session.commit()
    response = jsonify(message="registered")
    response.status_code = 201
    return response


# ---- admin summary ----


def threat_summary(hours: int, at: datetime) -> dict[str, Any]:
    e = SecurityEvent
    since = e.created_at >= at - timedelta(hours=hours)
    kinds = dict(db.session.execute(select(e.kind, func.count()).where(since).group_by(e.kind)).all())

    def top(column: Any) -> list[list[Any]]:  # noqa: ANN401
        rows = db.session.execute(
            select(column, func.count().label("n"))
            .where(since, e.kind == "login_fail", column.is_not(None))
            .group_by(column).order_by(func.count().desc()).limit(10)
        )  # fmt: skip
        return [[key[:8], n] for key, n in rows]

    locks = db.session.scalars(select(AuthLock).where(AuthLock.until > at).order_by(AuthLock.until))
    attempts = kinds.get("login_ok", 0) + kinds.get("login_fail", 0)
    busy = kinds.get("busy_reject", 0) * BUSY_SAMPLE
    return {
        "hours": hours,
        "counts": kinds,
        "attack_mode": [
            as_utc(t).isoformat()
            for t in db.session.scalars(select(e.created_at).where(since, e.kind == "attack_mode"))
        ],
        "locks": [
            {"scope": r.scope, "key": r.key[:8], "until": as_utc(r.until).isoformat(),
             "level": r.level, "reason": r.reason}
            for r in locks
        ],  # fmt: skip
        "top_ips": top(e.ip_key),
        "top_subnets": top(e.subnet_key),
        "replay_user_ids": sorted(
            u for u in db.session.scalars(
                select(e.user_id).where(since, e.kind == "token_replay").distinct()
            ) if u is not None
        ),  # fmt: skip
        "busy_reject_rate": round(busy / (attempts + busy), 4) if attempts + busy else 0.0,
    }


# ---- wiring ----


class SecurityState(NamedTuple):
    pepper: bytes
    sender: OtpSender
    budget: HashBudget
    engine: ThreatEngine


def _pepper(app: Flask) -> bytes:
    raw = os.environ.get("SAHIREP_SECURITY_PEPPER")
    if raw is not None:
        if len(raw) < core.MINIMUM_SECRET_LENGTH:
            msg = "SAHIREP_SECURITY_PEPPER must contain at least 32 characters."
            raise SecurityConfigError(msg)
        return raw.encode()
    secret = str(app.config["JWT_SECRET_KEY"]).encode()
    return hmac.new(secret, b"sahirep-security-pepper", hashlib.sha256).digest()


def _sender(app: Flask, given: OtpSender | None) -> OtpSender:
    if given is not None:
        return given
    name = (os.environ.get("SAHIREP_OTP_SENDER") or "").strip().lower()
    if name == "dev":
        if not app.testing:
            app.logger.warning("SAHIREP_OTP_SENDER=dev: OTP codes are written to the log")
        return DevSender()
    if name:
        msg = "SAHIREP_OTP_SENDER must be 'dev' (smtp arrives in Phase 3)."
        raise SecurityConfigError(msg)
    if app.testing:
        return MemorySender()
    if app.config["DEVICE_ENFORCE"]:
        msg = "SAHIREP_DEVICE_ENFORCE is on but SAHIREP_OTP_SENDER is not set."
        raise SecurityConfigError(msg)
    app.logger.warning("SAHIREP_OTP_SENDER not set: using the dev sender (codes go to the log)")
    return DevSender()


def register_security(app: Flask, otp_sender: OtpSender | None = None) -> None:  # noqa: C901, PLR0915
    token = app.config.get("ADMIN_TOKEN")
    if token and len(token) < core.MINIMUM_SECRET_LENGTH:
        msg = "SAHIREP_ADMIN_TOKEN must contain at least 32 characters."
        raise SecurityConfigError(msg)
    app.extensions["security"] = SecurityState(
        _pepper(app), _sender(app, otp_sender), HashBudget(app.config["HASH_SLOTS"]), ThreatEngine()
    )

    def otp_failed() -> Response:
        return refuse(INVALID_CODE, 400)

    def live_challenge(cid: str, at: datetime) -> OtpChallenge | None:
        ch = db.session.get(OtpChallenge, cid)
        alive = ch and ch.consumed_at is None and ch.attempts < OTP_MAX_ATTEMPTS
        return ch if alive and as_utc(ch.expires_at) > at else None

    @app.post("/api/auth/otp/verify")
    @limiter.limit("10 per minute")
    @limiter.limit("10 per minute", key_func=challenge_key)
    def otp_verify() -> Response:
        payload = OtpVerifyPayload.model_validate_json(request.get_data())
        at = now()
        ch = db.session.get(OtpChallenge, payload.challenge_id)
        # reserve one attempt atomically: parallel guesses cannot exceed OTP_MAX_ATTEMPTS
        reserved = ch is not None and db.session.execute(
            update(OtpChallenge)
            .where(
                OtpChallenge.id == payload.challenge_id, OtpChallenge.consumed_at.is_(None),
                OtpChallenge.attempts < OTP_MAX_ATTEMPTS, OtpChallenge.expires_at > at,
            )
            .values(attempts=OtpChallenge.attempts + 1)
            .execution_options(synchronize_session=False)
        ).rowcount == 1  # fmt: skip
        if ch is None or not reserved:
            db.session.rollback()
            return otp_failed()
        user = db.session.get(User, ch.user_id)
        keys = request_keys(user.username if user else "", payload.device_id)
        good = hmac.compare_digest(ch.device_id, payload.device_id) & hmac.compare_digest(
            code_hash(ch.id, payload.code), ch.code_hash
        )
        if not good or user is None:
            record("otp_fail", keys, at=at, user_id=ch.user_id)
            db.session.commit()
            return otp_failed()
        bound = verified_email(user)
        if ch.purpose == "verify_email" and (not ch.email or (bound and bound != ch.email)):
            # a verified address is never replaced (rule 1d): a racing second request loses
            record("email_add", keys, at=at, user_id=user.id, action="refused")
            db.session.commit()
            return otp_failed()
        consumed = db.session.execute(
            update(OtpChallenge)
            .where(OtpChallenge.id == ch.id, OtpChallenge.consumed_at.is_(None))
            .values(consumed_at=at)
            .execution_options(synchronize_session=False)
        ).rowcount
        if consumed != 1:
            db.session.rollback()
            return otp_failed()
        if ch.purpose == "verify_email":  # the only place users.email is ever written
            user.email, user.email_verified_at = ch.email, at
            record("email_verified", keys, at=at, user_id=user.id)
        device, token = enroll(user, payload.device_id, payload.device_label, keys, at)
        record("otp_ok", keys, at=at, user_id=user.id, action=ch.purpose)
        db.session.commit()
        return jsonify(session_body(user, device, token, with_key=True))

    @app.post("/api/auth/otp/resend")
    @limiter.limit("10 per minute")
    @limiter.limit("10 per minute", key_func=challenge_key)
    def otp_resend() -> Response:
        payload = OtpResendPayload.model_validate_json(request.get_data())
        at = now()
        ch = live_challenge(payload.challenge_id, at)
        if ch is None or not hmac.compare_digest(ch.device_id, payload.device_id):
            return otp_failed()
        wait = (as_utc(ch.last_sent_at) + timedelta(seconds=OTP_RESEND_AFTER) - at).total_seconds()
        if wait > 0:
            return refuse("wait before asking for another code", 429, math.ceil(wait))
        if ch.sent_count > OTP_MAX_RESENDS or otps_last_hour(ch.user_id, at) >= OTP_PER_HOUR:
            return refuse("too many codes, retry later", 429, 3600)
        user = db.session.get(User, ch.user_id)
        if user is None:
            return otp_failed()
        code = new_code(ch)  # a new code, but attempts are NOT reset
        ch.sent_count += 1
        ch.last_sent_at, ch.expires_at = at, at + OTP_TTL
        record("otp_sent", request_keys(user.username, ch.device_id), at=at, user_id=user.id,
               action="resend")  # fmt: skip
        db.session.commit()
        deliver(ch, user, code)
        response = jsonify(otp_body(ch, user))
        response.status_code = 202
        return response

    @app.post("/api/auth/email")
    @jwt_required()
    @limiter.limit(AUTH_LIMIT)
    @limiter.limit(AUTH_LIMIT, key_func=user_key)
    def add_email() -> Response:
        """Bind an email to an account that has none verified (trust-on-first-use accounts).

        Needs the password again (same lock check, hash budget and failure scoring as login), and
        users.email is untouched until the code sent to the NEW address is verified. A verified
        address is never replaced here (rule: refuse; an operator changes it), so a stolen JWT,
        even with the password, cannot redirect login OTPs.
        """
        payload = EmailPayload.model_validate_json(request.get_data())
        at = now()
        did = get_jwt().get("did")
        device = db.session.get(Device, did) if isinstance(did, int) else None
        if device is None:
            return refuse("sign in with a device first", 400)
        user = current_user
        keys = request_keys(user.username, device.device_id)
        enforce = current_app.config["DEVICE_ENFORCE"]
        engine = state().engine
        if enforce and (active := engine.locks(keys, at)):
            return locked(active, at)
        with state().budget.permit(trusted=False) as ok:
            if not ok:
                return refuse(BUSY, 429, 2)
            good = core.check_password_hash(user.password_hash, payload.password)
        if not good:  # a password guess like any other: feeds the account's lock score
            record("login_fail", keys, at=at, action="reauth", detail={"exists": True})
            if enforce:
                engine.after_fail(keys, at, attack=False)
            db.session.commit()
            return refuse("invalid password", 401)
        if verified_email(user):
            record("email_add", keys, at=at, user_id=user.id, action="refused")
            db.session.commit()
            return refuse("a verified email cannot be changed here", 409)
        record("email_add", keys, at=at, user_id=user.id, action="pending")
        return issue_otp(user, device.device_id, "verify_email", keys, at, email=payload.email)

    @app.post("/api/auth/logout")
    @jwt_required()
    def logout() -> Response:
        """Revokes this device only; its next login needs OTP. Legacy JWTs (no did) bump tv."""
        at = now()
        did = get_jwt().get("did")
        device = db.session.get(Device, did) if isinstance(did, int) else None
        if device is None:
            current_user.token_version += 1
        else:
            revoke(device, "logout", at)
        record("logout", None, at=at, user_id=current_user.id)
        db.session.commit()
        return jsonify(message="logged out")

    @app.post("/api/auth/logout-all")
    @jwt_required()
    def logout_all() -> Response:
        """Old logout: token_version bump (every JWT dies) and every device revoked."""
        at = now()
        current_user.token_version += 1
        db.session.execute(
            update(Device)
            .where(Device.user_id == current_user.id, Device.revoked_at.is_(None))
            .values(revoked_at=at, revoked_reason="logout_all")
            .execution_options(synchronize_session=False)
        )
        record("logout_all", None, at=at, user_id=current_user.id)
        db.session.commit()
        return jsonify(message="logged out everywhere")

    @app.get("/api/admin/threats")
    @limiter.limit("10 per minute")
    def admin_threats() -> Response:
        expected = current_app.config.get("ADMIN_TOKEN")
        if not expected:
            abort(404)
        given = request.headers.get("X-Admin-Token", "")
        if not hmac.compare_digest(given.encode(), expected.encode()):
            return refuse("authentication required", 401)
        try:
            hours = min(max(int(request.args.get("hours", 24)), 1), 24 * 30)
        except ValueError:
            return abort(400, "hours must be an integer")
        record("admin_view", None)
        db.session.commit()
        return jsonify(threat_summary(hours, now()))

    # ---- CLI ----

    @app.cli.command("threat-summary")
    @click.option("--hours", default=24, show_default=True, type=click.IntRange(min=1))
    def threat_summary_cmd(hours: int) -> None:
        """Counts by kind, attack-mode periods, active locks (8-char key prefixes), top keys."""
        click.echo(json.dumps(threat_summary(hours, now()), indent=2))

    @app.cli.command("purge-security-events")
    @click.option("--days", default=90, show_default=True, type=click.IntRange(min=30))
    def purge_security_events(days: int) -> None:
        """Delete audit events (and spent OTP challenges) older than DAYS (min 30: 7-day windows)."""
        cutoff = now() - timedelta(days=days)
        done = db.session.execute(delete(SecurityEvent).where(SecurityEvent.created_at < cutoff))
        db.session.execute(delete(OtpChallenge).where(OtpChallenge.expires_at < cutoff))
        db.session.commit()
        click.echo(f"purged {done.rowcount} security events older than {days} days")

    def lock_target(username: str | None, ip_key: str | None) -> tuple[str, str]:
        """(scope, full key). An ip key may be the 8-char prefix that threat-summary prints."""
        if bool(username) == bool(ip_key):
            msg = "give exactly one of --username or --ip-key"
            raise click.UsageError(msg)
        if username:
            return "account", keyed_hash(f"acct:{username.strip().lower()}")
        prefix = (ip_key or "").lower()
        key = db.session.scalar(
            select(SecurityEvent.ip_key).where(SecurityEvent.ip_key.startswith(prefix)).limit(1)
        ) or db.session.scalar(
            select(AuthLock.key).where(AuthLock.scope == "ip", AuthLock.key.startswith(prefix))
        )
        if len(prefix) < 8 or key is None:  # noqa: PLR2004
            msg = "unknown --ip-key (use at least the 8-char prefix from threat-summary)"
            raise click.UsageError(msg)
        return "ip", key

    @app.cli.command("auth-unlock")
    @click.option("--username")
    @click.option("--ip-key")
    def auth_unlock(username: str | None, ip_key: str | None) -> None:
        """Clear the lock on an account or an IP key."""
        scope, key = lock_target(username, ip_key)
        done = db.session.execute(delete(AuthLock).where(AuthLock.scope == scope, AuthLock.key == key))
        db.session.commit()
        click.echo(f"cleared {done.rowcount} {scope} lock(s)")

    @app.cli.command("auth-block")
    @click.option("--username")
    @click.option("--ip-key")
    def auth_block(username: str | None, ip_key: str | None) -> None:
        """Hard block (manual, applies to trusted devices too) until auth-unlock."""
        scope, key = lock_target(username, ip_key)
        at = now()
        row = db.session.get(AuthLock, (scope, key))
        if row is None:
            row = AuthLock(scope=scope, key=key, level=0)
            db.session.add(row)
        row.until, row.reason = at + MANUAL, "manual"
        record("lock_set", None, at=at, action=scope, detail={"reason": "manual"})
        db.session.commit()
        click.echo(f"blocked {scope} {key[:8]}")

    @app.cli.command("attack-mode")
    @click.argument("mode", type=click.Choice(["on", "off", "auto"]))
    def attack_mode_cmd(mode: str) -> None:
        """Force global attack mode on or off, or return it to automatic detection."""
        db.session.execute(delete(AuthLock).where(AuthLock.scope == "global"))
        if mode != "auto":
            db.session.add(AuthLock(scope="global", key="attack", level=0,
                                    until=now() + MANUAL, reason=f"manual_{mode}"))  # fmt: skip
        state().engine.attack_cache = None
        db.session.commit()
        click.echo(f"attack mode: {mode}")
