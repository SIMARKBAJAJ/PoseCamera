# SECURITY_DESIGN.md: Device-bound sessions and login hardening

Status: design only, nothing implemented yet. Scope is backend auth (`backend/app.py`, `backend/me.py`), a new `backend/security.py`, migration 0009, and the Android auth flow (`network/TokenStore.kt`, `network/SahiRepApi.kt`, `network/SahiRepRepository.kt`, `ui/AuthScreen.kt`, `ui/PoseCameraApp.kt`).

Decisions already made by the user:
- OTP starts with a DEV sender behind a pluggable interface. SMTP via env vars comes later.
- A new device always needs password + email OTP.
- The threat engine applies delays, locks and blocks automatically, based on risk.
- No alert-email feature.
- Android stores tokens with Keystore AES-GCM and no new dependency.

## 0. What exists today (verified)

| Area | Current state | Source |
|---|---|---|
| Password hash | werkzeug 3.1.8 default `scrypt:32768:8:1` (N=2^15, r=8, p=1, about 32 MiB per hash). Both live users have it. Measured on the dev box: **132 ms per check**; N=2^17 takes 545 ms. | `app.py` `register()`, `DUMMY_HASH` |
| Unknown-user timing | `login()` checks the password against `DUMMY_HASH` when the user doesn't exist, so one scrypt always runs. Tested by `test_unknown_user_login_still_verifies_a_hash`. | `app.py:597` |
| Login error | one 401 message, `"invalid username or password"`, for every failure | `app.py:601` |
| Rate limits | `AUTH_LIMIT="5 per minute"`. Login is limited per IP and per `login_username`; register per IP. Counters live in `memory://` unless `RATELIMIT_STORAGE_URI` is set. Limits are off when `testing=True`. | `app.py:76,569-591`, `test_security.py:41` |
| JWT | expires after 12 h, issuer `sahirep`, claims `sub` = user id and `tv` = token_version. `load_user` rejects a token whose `tv` doesn't match. There is no refresh endpoint. | `app.py:502,519-525,602` |
| Logout | `token_version += 1`, which signs the user out on every device | `app.py:607-612` |
| Register | returns 409 `"username already exists"`, which reveals taken usernames. This comes with letting users pick usernames. | `app.py:577` |
| Email | none. `users` has id, username, password_hash, token_version, consent_at. | `app.py:269-276` |
| Android token | `TokenStore`: AndroidKeyStore AES/GCM/NoPadding, alias `sahirep_jwt`, random IV. Stores `IV:ciphertext` in the `auth` prefs file. | `TokenStore.kt` |
| Android logout | only clears the token on the phone; never calls `/api/auth/logout` | `SahiRepRepository.kt:81` |
| Sync device id | `"android-" + 8 hex` (32 bits) kept in Room and wiped by `db.clearAllTables()` on account switch. Not usable as an auth device id. | `RoomSync.kt:22` |
| Backups | `android:allowBackup="true"` with no rules | `AndroidManifest.xml:9` |
| App version | `versionCode = 1` | `app/build.gradle.kts:18` |
| Migrations | 0001-0008. Adding a nullable column needs no table rebuild (as in 0008). Dropping a column from `users` uses the FK-off pattern from 0003's downgrade. `test_model_and_migration_agree` (`test_sync.py:673`) checks that models and migrations match. | `MIGRATIONS.md` |

**Password hash verdict:** keep scrypt N=2^15. It is below OWASP's N=2^17, but 2^17 costs 4x CPU and 128 MiB per hash, which makes a CPU-starvation attack (section 6) four times worse. Every hash now runs under the global hash limit (6.3), so raising the cost later only lowers the number of hashes per second. If it is ever raised:
- Use one `PASSWORD_METHOD` constant for both `generate_password_hash` and `DUMMY_HASH`. A mismatch would let timing reveal which accounts exist.
- Rehash the password on the next successful login.

## 1. Goals and threat model

Protect against:
1. Online password guessing against one account.
2. Distributed credential stuffing, including slow-roll, plus CPU starvation of real users while it happens.
3. Takeover from a new device with a stolen password.
4. Replay of a stolen device token.
5. Discovering which accounts exist through login.
6. A database leak exposing secrets that still work.

Out of scope (see section 11): a compromised email inbox, real-time phishing, malware on a trusted phone, and full server compromise.

## 2. Identifiers and secrets

| Item | Created by | Format | Where it is stored | Secret? |
|---|---|---|---|---|
| `device_id` | the Android app, once per install | UUIDv4 | TokenStore (encrypted); `devices.device_id` on the server | No. It is an identifier, bound to the token. |
| `device_token` | the server, on every successful login | `secrets.token_urlsafe(32)` (256 bits) | phone: TokenStore. Server: only `HMAC-SHA256(pepper, token)` | Yes |
| Request-signing key `K_dev` | the server, derived as `HMAC(pepper, "sig:" + devices.id + ":" + sig_epoch)` | 32 bytes, returned once when a device is enrolled | phone: imported into AndroidKeyStore so it can't be read back out. Server: **not stored**, re-derived when needed. | Yes |
| OTP code | the server | 6 digits from `secrets.randbelow(10**6)` | server: `HMAC(pepper, challenge_id + ":" + code)` | Yes, for 10 min |
| `challenge_id` | the server | `secrets.token_urlsafe(16)` | `otp_challenges.id` | No |
| ip / account / device / subnet keys | the server | `HMAC(pepper, kind + ":" + value)` in hex | `security_events`, `auth_locks` | Pseudonymous |

Notes:
- **Pepper:** a server-wide secret key from env `SAHIREP_SECURITY_PEPPER` (at least 32 chars). If unset, it is derived from the JWT secret. Hashing with this key means a database leak alone can't reverse IPv4 addresses (only 2^32 possible) or OTP codes (only 10^6 possible) by brute force.
- **Subnet keys:** IPv4 /24 and IPv6 /48. IPv6 addresses are cut to /64 for the per-IP key.
- **`account_key`:** derived from the lowercased username. It exists whether or not the account does, so lockouts look the same for real and fake usernames.
- **Comparisons:** every hash comparison uses `hmac.compare_digest`.

## 3. Login flow and API

### 3.1 `POST /api/auth/login`
Request body (`LoginPayload`, `extra="forbid"`): `{username, password, device_id?, device_token?, device_label?}`.

Headers:
- Always: `X-SahiRep-Client: android/<versionCode>`.
- When a device token is sent, also: `X-SahiRep-Ts` (unix seconds) and `X-SahiRep-Sig = hex(HMAC(K_dev, "POST\n/api/auth/login\n" + ts + "\n" + sha256(body)))`.

Steps run in the pre-hash order from section 6.2. Once the password has been checked:
- **Wrong password, or unknown user:** record a `login_fail` event with the same keys for both cases. Run the threat check, which may write locks. Return 401 `"invalid username or password"`.
- **Correct password:** classify the device.
  - **Trusted:** the signature and the token are both valid. Rotate the token: new token, `prev_token_hash = old hash`, `rotation += 1`. Return 200 `{access_token, device_token}`. A threat score of 60 or more forces OTP even here (step-up).
  - **Replay:** the token matches `prev_token_hash`, or a revoked device row. Revoke the device, record `token_replay`, add +60 to the score, then treat it as unknown. No email is sent; the event goes to the audit log only.
  - **Unknown:** no device row, a revoked row, or no token. Always ask for OTP: create a challenge, send the code with the configured sender, and return 202 `{status:"otp_required", challenge_id, expires_in:600, resend_after:60, email_hint}`.
  - **Legacy:** the request has no `device_id`. Allowed only while `SAHIREP_DEVICE_ENFORCE` is off, during cutover.
- **JWT claims:** `{"tv": user.token_version, "did": devices.id}`.

**Lost response:** if the phone never receives the rotated token, its next login looks like a replay. That costs the user one OTP. This is accepted on purpose, which is why replay adds +60 (forces OTP) and is not a lock. A lock needs 2 replays within 24 h.

### 3.2 `POST /api/auth/otp/verify`
- Body: `{challenge_id, code, device_id, device_label?}`. Limited to 10 per minute per IP and per challenge.
- Every failure returns the same 400 `"invalid or expired code"`: unknown challenge, expired, already used, 5 attempts used, wrong `device_id`, or wrong code.
- Each wrong code adds 1 to `attempts`, records an `otp_fail` event and adds +15 to the score. The challenge dies at 5 attempts.
- A correct code:
  1. Is used up by `UPDATE ... WHERE consumed_at IS NULL`; exactly 1 row must change.
  2. Inserts or updates the device row, clears `revoked_at` and increments `sig_epoch`.
  3. Returns 200 `{access_token, device_token, sig_key}`. `sig_key` is base64 `K_dev` and is sent only here.
- For `purpose='verify_email'` it also sets `users.email_verified_at`.

### 3.3 `POST /api/auth/otp/resend`
- Body: `{challenge_id, device_id}`.
- Rules: 60 s cooldown, at most 3 resends per challenge, at most 5 OTPs per account per hour.
- A resend creates a **new code** but does **not** reset `attempts`. Otherwise every resend would give an attacker 5 fresh guesses.
- Worst case for an attacker who already has the password: 5 guesses per challenge × 5 challenges per hour, so 25 × 10^-6 per hour. That is about 20% a year if they keep it up with no pause, and the score-based locks (section 5) cut this much further.

### 3.4 Sessions and logout
- `load_user` (`app.py:519`) keeps the `tv` check. When the JWT has a `did` claim, it also requires that device row to be not revoked and owned by that user. That is one primary-key lookup per request.
- `POST /api/auth/logout` revokes only the current device and drops its trust, as the spec says: the next login on that phone needs OTP.
- `POST /api/auth/logout-all` does the old `token_version` bump and revokes every device. `test_logout_revokes_all_of_the_users_tokens` moves to this endpoint.
- Optional Phase 4: `POST /api/auth/refresh` (signed request + device token, same rotation and replay rules). See fork 3.

### 3.5 Register
- `RegisterPayload` gains `email`, checked by regex, at most 254 chars, lowercased, **not unique** (fork 2).
- Register returns 202 `otp_required` with `purpose='verify_email'`. Verifying it issues the first JWT, device token and signing key.
- It runs through the same pre-hash pipeline and hash limit, because `generate_password_hash` costs the same as checking one.

## 4. OTP sender (pluggable)

`security.py`:
```python
class OtpSender(Protocol):
    def send(self, to: str, code: str, purpose: str) -> None: ...
```
- **Phase 1 implementations:**
  - `DevSender`: logs `"DEV OTP for s***@gmail.com: 123456"` at WARNING.
  - `MemorySender`: puts codes in `app.extensions["otp_outbox"]` for tests.
- **Phase 3:** `SmtpSender` using the standard library's `smtplib` (`SMTP_SSL` on 465, or STARTTLS on 587), 10 s timeout, plain-text message with no links. Configured by `SAHIREP_SMTP_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_FROM`. The user sets these in the service environment; never in chat or the repo.
- **Selection:** `SAHIREP_OTP_SENDER=dev|smtp`. `create_app` refuses to start if enforcement is on, testing is off and no sender is set. Choosing `dev` outside testing logs a startup warning.
- Sending is synchronous. It only happens after a correct password, so its timing reveals nothing new. If sending fails, the challenge is still returned so the user can press resend.

## 5. Threat engine (per-request scoring)

- `ThreatEngine` in `security.py` reads windowed counts from indexed `security_events` and writes only to `auth_locks`.
- Storage interface: `count`, `distinct`, `get_lock`, `set_lock`. Moving to Redis later swaps this one class: INCR/EXPIRE counters, HyperLogLog (PFADD/PFCOUNT) for distinct counts, and SET with EX for locks.

| Signal | Window | Weight |
|---|---|---|
| Failures for `account_key`, all IPs combined | 15 min | +10 each |
| Failures for `account_key`, all IPs combined | 24 h / 7 d | +2 each beyond 10 / beyond 30 (slow-roll, section 6.5) |
| Failures for `ip_key` | 15 min | +2 each (low because of CGNAT) |
| Distinct usernames failing from `ip_key` | 24 h | 3 or more → IP on the blocklist (6.4) |
| Distinct usernames failing from subnet /24 or /48 | 1 h | 10 or more → +30 |
| Distinct users on one `device_key` | 24 h | 3 or more → +25 |
| Registrations from `ip_key` | 24 h | 3 or more → +20; 5 or more → 429 |
| Unknown device | — | +15 (OTP is required anyway) |
| `token_replay` on the account | 24 h | +60; a second one +100 |
| OTP failures on the account | 1 h | +15 each |
| Global attack mode active (6.4) | — | +20 for every login without a valid signature |

| Score | Action |
|---|---|
| < 30 | Allow. Unknown devices still need OTP. |
| 30-59 | Progressive delay: refuse with 429 + `Retry-After` until `last_fail + min(2^(n-3), 300)` seconds. The server never sleeps, because a sleeping request ties up one of waitress's 8 threads. |
| 60-99 | Step-up: ask for OTP even on a trusted device. |
| ≥ 100 | Lock the account for untrusted devices for 15 min × 2^level, capped at 24 h. The level falls back after 24 h with no trouble. A `lock_set` event is logged; no email is sent. |
| IP / subnet block | Automatic, from the rules in 6.4. Lasts 1 h × 2^level. **Never applies to a request with a valid signature and device token.** |
| Hard block | Manual only, via the CLI `flask auth-block`. |

Flask-limiter's 5 per minute per IP and per username stays as an outer layer and returns the same 429 body. All weights and thresholds are module constants. They are rules of thumb, tuned with the simulation tests, not proven optimal.

## 6. Credential stuffing: 50k failures over 30k IPs

### 6.1 The scenario in numbers
- 50,000 attempts from 30,000 IPs is about 1.7 per IP. Spread over the 20-50k accounts in a leaked list, it is about 1-2 per account. Per-account lockouts and per-IP limits **never trigger**.
- At 132 ms of scrypt work per attempt, one vCPU does about 7 checks a second and a 2-vCPU server about 15. scrypt is the CPU-heavy part, so the server's cores, not waitress's 8 threads, set the ceiling.
- At 15 a second, 50k attempts keep the server fully busy for about **55 minutes**. Real users queue behind the attackers and time out.
- Memory: each concurrent scrypt uses 32 MiB, so 8 at once is 256 MiB.
- The defence therefore has to: (a) skip the hash wherever it can, (b) cap total hash work, and (c) keep a lane free for trusted devices.

### 6.2 Pre-hash filtering order (cheapest first)
Inside `security.login_flow()`, before `check_password_hash`:

0. **Flask-limiter** (existing decorators): per-IP and per-username limits, nearly free.
1. **Request shape** (microseconds):
   - The body must be JSON, smaller than `MAX_CONTENT_LENGTH`, and valid under `LoginPayload` (pydantic; already in place).
   - `X-SahiRep-Client` must match `^android/\d+$` with a version at least `MIN_CLIENT_VERSION`. Otherwise return 400 `"unsupported client, please update"`.
   - Once enforcement is on, `device_id` must be present.
   - These checks only stop lazy scripts, since anyone can copy the headers. They are free and say nothing about any account.
2. **Request signature** (only when `device_token` is present):
   - Find the device with one query that joins on the username: `SELECT d.* FROM devices d JOIN users u ON u.id=d.user_id WHERE u.username=:u AND d.device_id=:d`. It is the same query whether or not the user exists.
   - Check that `X-SahiRep-Ts` is within ±300 s, then re-derive `K_dev` and compare the signature in constant time. If it's valid, compare the token hash.
   - **Valid signature and token** → **trusted lane**: skips the IP/subnet blocklist, account locks and attack-mode penalties, and goes to step 5 with the reserved hash permit.
   - **Token present but the signature or token is invalid** → record a `bad_signature` event (+30 on `device_key` and `ip_key`) and return 401 `"invalid username or password"` **without hashing**. This happens before anything about the account is known, so it reveals nothing.
   - **Replay protection:** no nonce store is needed. The timestamp window bounds replay of a captured request, and the token rotates on every login, so a replayed login carries `prev_token_hash` and is caught as a replay.
3. **Threat score and locks** (indexed SQL counts, about 1 ms on SQLite): IP, subnet and device blocks, then the account lock. A lock returns 429 with no hash. Lock keys exist for fake usernames too, so the answer is the same either way.
4. **Hash budget** (6.3): if no permit is free, return 429 `"server busy, retry shortly"` with `Retry-After: 2`. This is decided before the user lookup, so it reveals nothing.
5. **User lookup and `check_password_hash`**: real hash or `DUMMY_HASH`. Exactly one scrypt runs, for every request that gets this far.

### 6.3 Global hash budget (`security.HashBudget`)
- Two `threading.BoundedSemaphore`s, one per process:
  - `UNTRUSTED = max(1, cores - 1)`, configured by `SAHIREP_HASH_SLOTS`.
  - `TRUSTED = 1`, reserved.
- **Trusted-lane requests** try `TRUSTED`, then `UNTRUSTED`. Each uses `acquire(timeout=0.5)`.
- **All other requests** (unknown devices, no token, register) use `UNTRUSTED` with `acquire(timeout=0)`. If none is free, they fail fast with 429. They never wait in line, so they never pile up behind attackers.
- The cap bounds both CPU and memory: slots × 32 MiB.
- When the server moves to several processes (Postgres + Redis), switch to a Redis limit on concurrent hashes plus a token bucket. Until then each process has its own budget, which still works because each process protects its own CPU.
- Result during an attack: trusted devices get answers in about 150 ms, while new-device logins and registrations may see "busy, retry" during spikes. The Android client retries automatically with jitter, at most 3 times.
- **Optional:** after a successful hash, a "known good" fast path could skip the hash for trusted devices entirely, since they already proved possession of the signing key. That would make device + signature enough to skip the password. **Rejected:** the spec requires the password on every login.

### 6.4 Detecting distributed attacks
- **Failures per account across all IPs:** the account-key counters in section 5 combine all IPs, so a list that retries one account from 30 IPs still adds up.
- **IP blocklist:** an IP that fails on **3 or more distinct usernames in 24 h** goes on `auth_locks(scope='ip')` for 24 h × 2^level. Real users rarely mistype three different usernames. A subnet (/24, /48) with 10 or more distinct failing usernames in 1 h gets +30. It is not blocked outright, because whole carrier ranges sit behind CGNAT.
- **Global attack mode:**
  - Computed each minute from a cached count (`security_events` over the last 5 min, cached for 30 s in memory).
  - Triggers when failures in 5 min exceed `max(200, 10 × the 7-day median per 5 min)`, **or** distinct failing account_keys in 5 min reach 100 or more, **or** more than 50% of failures are for usernames that don't exist.
  - Whether a username exists is stored in `security_events.detail` for internal use only. It is never visible in any response.
  - Effects while active:
    1. Every login without a valid signature gets +20.
    2. The untrusted hash slots halve.
    3. Optional proof-of-work kicks in (6.7).
    4. An `attack_mode` event is logged, so it shows in `flask threat-summary`.
  - It turns off after 15 min below the trigger.
  - The trusted lane is never affected.
- **Bot-like fingerprints** (+10 each, never a block on their own):
  - a `device_id` never seen before, combined with a failure;
  - one `device_id` used with 3 or more usernames;
  - the same `device_label` + client version failing on 20 or more accounts in 1 h.

### 6.5 Slow-roll evasion
- Attackers stay under the 15-minute windows by spreading attempts over days. Long-window counters catch this:
  - account failures over 24 h and 7 d (section 5);
  - distinct usernames per IP over 24 h;
  - distinct failing usernames per subnet over 7 d (50 or more gives +20 for 7 d).
- `security_events` keeps 30 days at minimum (`flask purge-security-events --days 90` by default), so the 7-day queries always have data.
- The long-window counts only run after the short-window checks pass, so they cost nothing for requests already being refused.
- Redis later: per-day buckets such as `fail:acct:<key>:<yyyymmdd>` with a 9-day TTL, summed over 7 keys.

### 6.6 Keeping responses uniform
- Nothing tells real and fake usernames apart before the hash:
  - **no username bloom filter** or "user exists" fast path;
  - locks keyed by `account_key` exist for fake usernames too;
  - the signature lookup is a single join query.
- Fake usernames pay the same hash cost and go through the same budget. This is deliberate: it is the price of uniformity.
- Every refusal before the hash (bad shape, bad signature, lock, busy) is decided by request properties that are the same for every username. Each returns a fixed body.
- A correct password with an unknown device gives 202, which only someone who already knows the password can reach.
- Known, accepted leak: register's 409 for taken usernames.

### 6.7 Optional layer: proof-of-work (off by default)
- In attack mode only, unsigned logins and registrations must include `X-SahiRep-Pow`. It is a solution to `sha256(server_nonce || client_nonce)` with 20 leading zero bits (about 1 M hashes, roughly 0.5-2 s on a budget phone). The server nonce is an HMAC-signed `{ts, ip_key}` from `GET /api/auth/pow`, so the server keeps no state.
- Checking a solution costs one SHA-256, far cheaper than scrypt.
- It makes a botnet pay per attempt but does not stop one, and it costs real users battery and about a second. Recommended only if section 6.4 proves insufficient in practice.

## 7. Audit log and admin view

- **`security_events` kinds:** `login_ok`, `login_fail`, `bad_signature`, `busy_reject`, `otp_sent`, `otp_ok`, `otp_fail`, `device_new`, `device_rotated`, `device_revoked`, `token_replay`, `lock_set`, `attack_mode`, `register`, `logout`, `logout_all`, `admin_view`. Passwords, codes, tokens, keys and raw IPs are never stored. `busy_reject` events are sampled 1 in 10 so an attack can't flood the table.
- **Retention:** `flask purge-security-events` on a cron, like `purge-sync-ops` (`sync.py:477`).
- **`delete_me`** (`me.py:189`) must delete the user's `devices` and `otp_challenges` rows; they are foreign keys and the delete would fail otherwise. It also sets `security_events.user_id` to NULL.
- **`export_me`** (`me.py:162`) adds the user's device list (label, created, last seen, revoked) without any hashes.
- **Admin view:**
  - `flask threat-summary [--hours 24]` is the main tool, since a CLI command has no network exposure. It shows counts by kind, attack-mode periods, active locks (first 8 chars of the key), the top 10 IP and subnet keys by failures, user ids with replays, and the busy-reject rate.
  - `GET /api/admin/threats` exists only when `SAHIREP_ADMIN_TOKEN` (at least 32 chars) is set; otherwise it is a 404. It needs the `X-Admin-Token` header (constant-time compare), is limited to 10 per minute, and logs `admin_view`.
- **Recovery CLI:**
  - `flask auth-unlock --username X | --ip-key K`
  - `flask auth-block --username X | --ip-key K`
  - `flask attack-mode on|off|auto` (manual override)

## 8. Schema (migration 0009)

`users`: add `email VARCHAR(254) NULL` (index `ix_users_email`, not unique) and `email_verified_at DATETIME NULL`. No table rebuild.

`devices`:
```
id INTEGER PK
user_id INTEGER NOT NULL FK users.id (index)
device_id VARCHAR(64) NOT NULL
token_hash CHAR(64) NOT NULL
prev_token_hash CHAR(64) NULL
rotation INTEGER NOT NULL DEFAULT 0
sig_epoch INTEGER NOT NULL DEFAULT 0
rotated_at, created_at, last_seen_at DATETIME NOT NULL
ip_hash CHAR(64) NULL
label VARCHAR(128) NULL
revoked_at DATETIME NULL
revoked_reason VARCHAR(16) NULL   -- CHECK IN ('logout','logout_all','replay','admin')
UNIQUE uq_devices_user_device (user_id, device_id)
```

`otp_challenges`:
```
id VARCHAR(32) PK
user_id FK users.id (index)
device_id VARCHAR(64) NOT NULL
purpose VARCHAR(16) NOT NULL      -- CHECK IN ('login','verify_email')
code_hash CHAR(64) NOT NULL
attempts INTEGER NOT NULL DEFAULT 0
sent_count INTEGER NOT NULL DEFAULT 1
created_at, last_sent_at, expires_at DATETIME NOT NULL
consumed_at DATETIME NULL
ip_hash CHAR(64)
```

`security_events`:
```
id                -- BigInteger, Integer variant on SQLite, autoincrement (like SyncChange)
created_at DATETIME NOT NULL
kind VARCHAR(24) NOT NULL
user_id INTEGER NULL              -- no FK
account_key, ip_key, subnet_key, device_key CHAR(64) NULL
score INTEGER NULL
action VARCHAR(16) NULL
detail JSON NULL
INDEX (account_key, kind, created_at)
INDEX (ip_key, kind, created_at)
INDEX (subnet_key, kind, created_at)
INDEX (device_key, created_at)
INDEX (kind, created_at)
```

`auth_locks`:
```
scope VARCHAR(8)   -- CHECK IN ('account','ip','subnet','device','global')
key CHAR(64)
PK (scope, key)
until DATETIME NOT NULL
level INTEGER NOT NULL DEFAULT 0
reason VARCHAR(24)
```
Global attack mode is the row `scope='global', key='attack'`.

`0009_device_sessions.py`:
- **Upgrade:** `add_column` twice and `create_index` on `users`, then `create_table` four times with named constraints. No table rebuild and no FK-off, same as 0008's upgrade.
- **Downgrade:**
  1. Drop the four tables and the index.
  2. Drop the two `users` columns inside the FK-off block from 0003's downgrade: `PRAGMA foreign_keys=OFF` in an `autocommit_block`, then `batch_alter_table("users")`, then `PRAGMA foreign_key_check` (raise if it finds problems), then turn foreign keys back on. SQLite only.
  3. Include the `_drop_leftover` guard from 0008.
- **Procedure:**
  1. Stop the server.
  2. Back up with SQLite's backup API, which handles the WAL file: `sqlite3.connect('instance/sahirep.db').backup(sqlite3.connect('../data/backup/sahirep.db.pre-0009'))`.
  3. On a second copy, run `SAHIREP_DATABASE_URI=sqlite:///<copy>` with `flask db upgrade`, then `flask db check`, `flask db downgrade 0008`, and `flask db upgrade` again. Compare `users` and `workout_sets` row counts before and after.
  4. Upgrade the live database.
  5. Smoke test on the live server under waitress: register, read the DEV OTP from the log, verify, run a sync pull.
  6. Update the backup line in `MIGRATIONS.md`.

## 9. Android

### 9.1 Storage: Keystore AES-GCM directly (decided, no new dependency)
**Comparison, for the record:** `androidx.security:security-crypto` 1.1.0 has been **deprecated as a whole library** since 1.1.0-alpha07 (April 2025). It pulls in Tink, about 1-2 MB before R8 shrinking (approximate). It also has known keyset-corruption crashes on some OEM phones. It would add nothing over the current `TokenStore`.

`TokenStore` changes:
- Generalise to `put`, `get` and `remove` by name for `jwt`, `device_token` and `device_id`. Keep `saveToken`/`getToken` so `SyncRuntime.kt:115-142` doesn't change.
- `device_id`: a `UUID.randomUUID()` created on first use. It survives logout and account switch. It is never ANDROID_ID or anything hardware-based.
- **Signing key:**
  - Import with `KeyStore.setEntry("sahirep_sig", SecretKeyEntry(SecretKeySpec(raw, "HmacSHA256")), KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build())` (API 23+, minSdk is 24). Zero out `raw` right after import.
  - Sign with `Mac.getInstance("HmacSHA256").apply { init(keyStore.getKey("sahirep_sig", null)) }`.
  - On API 28+, add `setUnlockedDeviceRequired(true)` to `sahirep_sig` only, since it is used only for an interactive login. Not on `sahirep_jwt`, because background sync runs while the phone is locked.
  - No StrongBox.
- **Backups:** add `res/xml/data_extraction_rules.xml` (API 31+) and `full_backup_content.xml` (API 23-30) that exclude `sharedpref/auth.xml`. A phone restored from backup then shows up as a new device.

### 9.2 Network and UI
- **`SahiRepApi.kt`:**
  - `LoginRequest(username, password, device_id, device_token?, device_label = Build.MODEL)`.
  - `LoginResponse(access_token?, device_token?, sig_key?, status?, challenge_id?, resend_after?, email_hint?)`.
  - New calls: `verifyOtp`, `resendOtp`, `logout`. Register gains `email`.
- **`SahiRepRepository.kt`:**
  - An OkHttp interceptor for `/api/auth/*` adds `X-SahiRep-Client: android/${BuildConfig.VERSION_CODE}`. When a signing key exists, it also adds `X-SahiRep-Ts` and `X-SahiRep-Sig`, with the body read through an okio `Buffer`.
  - On 429, retry up to 3 times with jitter, honouring `Retry-After`.
  - Run `accountSwitch` **before** the network login, so the user never has to confirm while holding unsaved server tokens.
  - Extract `completeLogin()`, shared by login and OTP verify.
  - Return a sealed `LoginOutcome`: `LoggedIn`, `OtpRequired`, or `Failure`.
  - Add `"/api/auth/logout"` to `PROTECTED_PATHS`. `logout()` calls the server as best effort, then clears `jwt`, `device_token` and `sahirep_sig`, and keeps `device_id`.
- **`AuthScreen.kt` and `PoseCameraApp.kt:109`:**
  - An OTP step: a 6-digit `NumberPassword` field, "Code sent to {email_hint}", a Resend button with a countdown, and Back.
  - An email field when registering.
  - A "Server busy, retrying..." state.
  - In Phase 1 (DEV sender), the code appears in the backend log.

## 10. Threat-engine storage now vs later
- **Now:** SQLite tables (section 8) plus the per-process `HashBudget` and a 30 s in-memory cache for attack mode.
- **Later:** Redis behind the same `ThreatStore` interface (counters, HyperLogLog, locks, a concurrent-hash limit), plus `RATELIMIT_STORAGE_URI=redis://...` (needs `uv add redis`, as `DEPLOY.md` already says). Both arrive with multiple processes on Postgres.

## 11. Honest limits

**What it stops or makes more expensive:**
- **Stolen password used from a new device:** it also needs the user's email inbox.
- **Guessing one account:** blocked by locks on the account key, which work the same for fake usernames.
- **Credential stuffing:**
  - IPs that try several usernames get blocklisted;
  - global attack mode kicks in;
  - total hash work is capped, so trusted users stay fast;
  - slow-roll attacks are caught by the 7-day counters.
- **Copied device token without the signing key:** rejected before the hash.
- **Replay after the owner logs in again:** detected, and the device is revoked.
- **Database leak alone:** no working tokens, OTPs or signing keys (keyed HMAC, keys derived rather than stored).
- **Account discovery through login.**

**What it does NOT stop (nothing is attack-proof):**
- **Real botnets with valid-looking clients.** Anyone can unpack the APK and copy the headers, client version and request format. For unknown devices the signature proves nothing, because there is no key yet. A residential-proxy botnet that stays under the per-IP and subnet thresholds while spreading load across a whole day can keep testing passwords. The design limits how fast it goes (hash budget, attack mode) and bounds the damage (OTP still needed). It cannot tell those requests apart from real users.
- **Rooted phones copying device tokens.**
  - Root can read `device_token` from prefs after decrypting it through the app's Keystore key on that same phone. It can also *use* the Keystore signing key on that phone.
  - On a phone with a TEE-backed Keystore, the key can't be exported, so the copied token can't be used from another machine.
  - On phones with a software-only Keystore (some budget models) the key can be extracted.
  - Root on the phone at enrolment can catch `sig_key` in memory before it is imported.
  - The password is still needed in every case.
- **Malware on, or theft of, an unlocked trusted phone.**
- **A compromised email inbox.** Email OTP is only as strong as the inbox.
- **Real-time phishing or relay** of password + OTP. Only passkeys/WebAuthn fix that.
- **Server compromise.** The pepper plus the database means everything can be forged.
- **Lockout as a weapon:** an attacker can lock an account for new devices and slow new-device logins during attack mode. Trusted devices are exempt.
- **IP signals are weak** behind Indian carrier CGNAT. They are useless unless the proxy's `X-Forwarded-For` is trusted (`DEPLOY.md:26`); otherwise every client looks like the proxy.
- **Register still reveals** which usernames are taken.

**Are more layers worth it?** Not now: password + email OTP on new devices, a trusted-device fast lane, and the threat engine fit what the app stores.
- Optional, out of scope:
  - proof-of-work (6.7);
  - captcha or Cloudflare Turnstile on register and on new-device login in attack mode;
  - Play Integrity attestation, which would make faking a client much harder but is a Google dependency, can be bypassed on rooted phones, and needs Play Services;
  - signing with a hardware-bound ECDSA key (fork 4);
  - passkeys, as the long-term defence against phishing.

## 12. Phased plan
Every phase ends with backend `python -m pytest -q` (274 passing today) and the Android build plus unit tests.

**Phase 1: backend, enforcement off, DEV sender**
- `backend/security.py` (NEW): the models, `keyed_hash`/`pepper`, `ThreatEngine` + `ThreatStore`, `HashBudget`, `login_flow` (the 6.2 order), OTP issue/verify/resend, `DevSender`/`MemorySender`, and signature checks. `register_security(app)` adds the routes and the CLI commands: `threat-summary`, `purge-security-events`, `auth-unlock`, `auth-block`, `attack-mode`. Hooked in at `app.py:644-655`.
- `backend/app.py`: the `User` email columns, `LoginPayload`, `RegisterPayload.email` (optional until Phase 3), `login()` calling `login_flow`, the `did` claim, the device check in `load_user`, and logout/logout-all. Config: `SAHIREP_DEVICE_ENFORCE`, `SAHIREP_HASH_SLOTS`, `MIN_CLIENT_VERSION`, and `create_app(..., otp_sender=None)`.
- `backend/me.py`: the `delete_me` cleanup and the device list in `export_me`.
- `backend/migrations/versions/0009_device_sessions.py` (NEW), with the procedure from section 8.
- Tests: `backend/test_device_auth.py` (NEW), the logout test in `backend/test_security.py`, and a 0009 migration test next to `test_sync.py:609`.
- Docs: `backend/MIGRATIONS.md`, `backend/DEPLOY.md` (pepper, hash slots, admin token, trusted proxy).

**Phase 2: Android**
- Change `TokenStore.kt`, `SahiRepApi.kt`, `SahiRepRepository.kt`, `AuthScreen.kt`, `PoseCameraApp.kt` and `AndroidManifest.xml`.
- Add two `res/xml` backup-rule files.
- Extend `SahiRepApiMappingTest.kt`, and add unit tests for the signing string.

**Phase 3: cutover and SMTP**
1. Existing accounts enrol (fork 1).
2. Add `SmtpSender` and set `SAHIREP_OTP_SENDER=smtp` plus the env credentials.
3. Turn on `SAHIREP_DEVICE_ENFORCE=1`; legacy logins and JWTs without `did` are then refused.
4. Make email required on register.

**Phase 4 (optional):** `/api/auth/refresh` (fork 3), proof-of-work, Redis storage.

## 13. Test list (`backend/test_device_auth.py`; `MemorySender`; enforcement on; clock patched)
1. **New-device flow:**
   1. Login returns 202 and puts one OTP in the outbox.
   2. Verifying returns 200 with `access_token`, `device_token` and `sig_key`.
   3. A signed login with that token returns 200 without OTP, and the token rotates.
2. **Signature:**
   - A token with no signature, a wrong signature, or a timestamp 301 s old returns 401, and `check_password_hash` is **not called** (spy).
   - A valid signature with the wrong password returns 401 after exactly one hash.
3. **Replay:**
   - Re-using a rotated token revokes the device, makes JWTs carrying the old `did` return 401, logs a `token_replay` event and asks for OTP.
   - A second replay within 24 h locks the account.
   - Re-using a token after logout counts as a replay.
4. **OTP rules:**
   - single use;
   - expires after 10 min;
   - 5 wrong codes kill it, even if the 6th is right;
   - a different `device_id` fails;
   - resend within 60 s gives 429;
   - resend keeps the attempt count;
   - a 6th OTP in an hour gives 429;
   - `code_hash` differs from both the code and its plain sha256.
5. **Brute force:** 20 wrong passwords on one account from rotating IPs bring progressive 429s with `Retry-After`, then the lock. During the lock, a signed trusted device with the right password still gets 200.
6. **Distributed stuffing simulation:**
   - 3,000 failures spread over 1,800 fake IPs and 2,000 usernames (a scaled-down version of the 50k scenario), mostly non-existent usernames.
   - Assert that attack mode turns on, IPs with 3 or more usernames are blocked, and the number of `check_password_hash` calls stays bounded.
   - A signed trusted login during the attack returns 200.
7. **Hash budget:** with `SAHIREP_HASH_SLOTS=1` and a slot held by a blocking `check_password_hash` spy:
   - an unsigned login gets an immediate 429 `"server busy"` with `Retry-After`;
   - a signed trusted login gets the reserved slot and returns 200.
8. **Slow-roll:** 40 failures on one account spread across 7 days, each 15-min window under its threshold. The 7-day counter adds score and eventually locks. The same pattern across a subnet adds +20.
9. **Lock expiry:** jump the clock past `until` and logins work again. The level doubles on a repeat lock and falls back after 24 h without trouble. `flask auth-unlock` clears a lock.
10. **Uniform responses:**
    - Fake and real usernames give the same status, body and events, with one hash each.
    - Lock and busy answers are the same for real and fake usernames.
    - All OTP failures share one body.
    - No response field or header differs on whether the user exists.
11. **Legacy:** with enforcement off, the old `{username, password}` login still returns 200, and the 274 existing tests pass apart from the moved logout test. With enforcement on, the old login is refused.
12. **Client gate:** a missing or old `X-SahiRep-Client` returns 400 with no hash.
13. **Admin:** without a token set, the route is 404; a wrong token gets 401; the right token gets a summary with only 8-char key prefixes. CLI commands run through `app.test_cli_runner()`.
14. **Account deletion:** `delete_me` works when devices and challenges exist, and events are kept with `user_id` set to NULL.
15. **Migration:** 0009 up, down and up again on a populated 0008 database keeps all data, `foreign_key_check` is clean, and `test_model_and_migration_agree` passes.
16. **Limits:** in a `limited_client` test, `/api/auth/otp/verify` returns a JSON 429 after 10 in a minute.

## 14. Remaining decisions for you (the earlier choices are already settled)
1. **How the 2 existing accounts get started.** They have no email and no devices.
   - A: trust-on-first-use. The first login after the change enrols that phone as trusted and prompts the user to add and verify an email; this only works while enforcement is off.
   - B: force "add email + OTP" before any login.
   - **I recommend A.** You own both accounts, and Phase 3 closes the window.
2. **Email at sign-up.** **I recommend required for new accounts, not unique, and verified by OTP.** Making email unique would let anyone check which emails have accounts through register, and login uses the username anyway.
3. **Silent refresh with the device token (Phase 4).** Today the 12 h JWT forces a password login about twice a day. Signed refresh, with the same rotation and replay rules, would end that, but it lets the device token plus signing key start sessions without the password. **I recommend yes**, with a 30-day idle expiry and a forced OTP when the score reaches 60.
4. **Signing key type.**
   - The HMAC key in this design is derived by the server and imported once. It is simple and needs no new storage, but it passes through app memory once and the server can recreate it.
   - The alternative is an ECDSA P-256 key generated inside the AndroidKeyStore. Its private key never leaves the hardware, and the server keeps only the public key. It needs one more column and `java.security.Signature` on the phone (no new dependency). It is stronger against rooted phones and leaked server keys.
   - **I recommend HMAC now** (it fits Phase 1) **and ECDSA as an upgrade.** Move to ECDSA early if rooted-phone token theft matters to you.