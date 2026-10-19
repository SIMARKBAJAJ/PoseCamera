# Deploy

Run behind a TLS-terminating reverse proxy (nginx/Caddy); never expose waitress directly.

```
flask --app app:create_app db upgrade        # always first, after every deploy
waitress-serve --listen=127.0.0.1:8000 --threads=8 --call app:create_app
flask --app app:create_app purge-sync-ops    # daily cron: drops idempotency records older than 30 days
flask --app app:create_app purge-security-events   # daily cron: audit events older than 90 days (min 30)
```

Required environment:

- `SAHIREP_JWT_SECRET`: random, >= 32 characters (tokens die if it changes).
- `SAHIREP_TZ`: IANA zone for day boundaries (default `Asia/Kolkata`); invalid values abort startup.
- Database, first match wins: `SAHIREP_DATABASE_URI`, then `DATABASE_URL`, then `sqlite:///sahirep.db` (`instance/sahirep.db`).
  Postgres: `DATABASE_URL=postgresql+psycopg://user:pass@host:5432/sahirep` (`postgres://` and `postgresql://` are rewritten
  to the psycopg 3 driver).
- `SAHIREP_DB_POOL_SIZE` (default 10) and `SAHIREP_DB_MAX_OVERFLOW` (default 10): non-SQLite only; `pool_pre_ping` is always on
  and connections are recycled after 30 min. Keep `pool_size + max_overflow >= waitress threads`, and
  `processes * (pool_size + max_overflow)` under Postgres `max_connections` (default 100).
- `RATELIMIT_STORAGE_URI`: e.g. `redis://host:6379` (also `uv add redis`); default is per-process memory.

Login security (SECURITY_DESIGN.md; Phase 1 = backend with enforcement off):

- `SAHIREP_SECURITY_PEPPER`: >= 32 random chars, keys every stored HMAC (device tokens, OTP codes, IP/account keys) and derives the
  request-signing keys. If unset it is derived from `SAHIREP_JWT_SECRET`, so rotating either one logs every device out and voids
  pending OTPs. Set it explicitly before Phase 3 and never change it casually. Keep it out of the repo and chat.
- `SAHIREP_DEVICE_ENFORCE` (default off): `1` refuses legacy logins and JWTs without a device (`did`), requires
  `X-SahiRep-Client: android/<versionCode>` (>= `SAHIREP_MIN_CLIENT_VERSION`, default 1) and email + device_id on register, and
  switches on the threat engine's refusals (progressive 429 delays, account locks, IP blocks, attack mode). While off: signatures,
  replay detection, the hash budget, OTP rules and the audit log still apply; an account with no devices enrols its first phone
  without OTP (trust-on-first-use), and an account without email gets a legacy JWT. Turn it on only after the Android release
  (Phase 2) and after both existing accounts have enrolled and added an email (`POST /api/auth/email`).
- Email binding: `users.email` is written only when the OTP sent to that address is verified (register and
  `POST /api/auth/email` keep the address pending on `otp_challenges.email`). `/api/auth/email` needs a device JWT plus the
  password again (failures count as login failures). Login OTPs go only to a verified address. A verified address is never
  replaced through the API (409); changing it is an operator action in the database for now.
- `SAHIREP_OTP_SENDER`: `dev` writes codes to the server log at WARNING (Phase 1 only). Unset falls back to `dev` with a warning;
  with enforcement on and no sender, startup fails. SMTP comes in Phase 3.
- `SAHIREP_HASH_SLOTS` (default cores - 1): concurrent password hashes for untrusted requests per process (each ~32 MiB, ~130 ms);
  one more slot is reserved for signed trusted devices. When full, untrusted logins and registrations get 429 `server busy` +
  `Retry-After: 2` instead of queueing.
- `SAHIREP_ADMIN_TOKEN` (>= 32 chars, optional): enables `GET /api/admin/threats` with header `X-Admin-Token`; unset = 404.
  Prefer the CLI: `flask threat-summary [--hours 24]`, `flask auth-unlock|auth-block --username X | --ip-key <8+ char prefix>`,
  `flask attack-mode on|off|auto`.
- The trusted-proxy setting below matters even more here: without it every client has the proxy's IP, so the per-IP and subnet
  signals (and IP blocks) hit everyone at once.

Proxy:

- Terminate TLS and send `Strict-Transport-Security` (HSTS) at the proxy.
- Rate limits key on the client IP (sync push/pull key on the user): add `--trusted-proxy=127.0.0.1 --trusted-proxy-headers=x-forwarded-for`
  to waitress and have the proxy set `X-Forwarded-For`, or every client shares the proxy's address.
- `/api/sync/push` accepts 1 MB; set the proxy's body limit (`client_max_body_size`) to at least 1 MB for that path.
- Catalog export gzips itself (stdlib); the proxy may gzip other JSON.

Local Postgres + Redis for development: `docker compose up -d` (see `docker-compose.yml`; dev password, localhost only).

## Sizing and limits (sync)

- waitress is ONE process with N threads (`--threads`, default 4, 8 is reasonable); Python threads share the GIL, so more threads
  help only while requests wait on the database. More cores = more processes behind the proxy, which requires Postgres
  (SQLite allows one writer) and Redis (otherwise rate limits are per process).
- Measured here (SQLite file DB, in-process test client, Windows dev box, one writer): push about 200 ops/s in 200-op batches
  (~5 ms/op: validation + ORM + savepoint + 3 inserts per op), pull about 13,000 changes/s. Treat these as ballpark, not a promise.
  Postgres removes the single-writer ceiling; expect the same per-op CPU cost, so roughly 200 ops/s per waitress process.
- In principle: a user who logs ~100 sets/meals/weights a day pushes ~100 ops/day; one process serves ~17M ops/day, i.e. tens of
  thousands of daily users, if pushes are spread out. Burst limits: 30 pushes/min and 120 pulls/min per user, 200 ops/push.
- Limits to know: (1) `sync_changes` only grows (about 100-150 bytes/change; no compaction yet), so a stale device re-pulls the
  whole history; (2) with SQLite a long push blocks other writers (busy_timeout 5 s, then the request fails with 500); (3) LWW trusts phone clocks
  (clamped to now + 5 min, but a phone that is hours slow loses every conflict); (4) a delete only blocks stale upserts that arrive
  after the server saw it (tombstone time = server receive time); (5) rejected ops are final and not retried;
  (6) on Postgres ids are taken under a per-user advisory lock so a user's cursor never skips; that path is written but was NOT
  run (no Postgres here); (7) ORM mapper events log writes: raw SQL writes to synced tables bypass the feed (bulk deletes in
  `DELETE /api/me` do so on purpose).

## Postgres status

NOT tested here: no Postgres server or embedded variant (`pgserver`) exists on this machine, and nothing was downloaded. Verified
only: psycopg 3 installs and imports, the models compile to Postgres DDL (46 statements), the engine is built from a
`postgresql+psycopg://` URL, and migrations 0001-0008 were reviewed (0006 fixed: boolean literal). To run it:

```
docker compose up -d
export DATABASE_URL=postgresql+psycopg://sahirep:sahirep-dev@127.0.0.1:5432/sahirep SAHIREP_JWT_SECRET=<32+ chars>
flask --app app:create_app db upgrade          # 0001 -> 0008 on empty Postgres
flask --app app:create_app db downgrade 0001 && flask --app app:create_app db upgrade
flask --app app:create_app db check            # expect: No new upgrade operations detected
```

then smoke-test by hand (the test suite builds temporary SQLite files and does not read `DATABASE_URL`): register a user, then
`POST /api/sync/push` and `GET /api/sync/pull` as in SYNC_API.md.
Moving the existing SQLite data (users, 8,192 foods, 876 exercises) needs a one-off copy (e.g. pgloader) after `db upgrade`.

Logout (`POST /api/auth/logout`) revokes only the calling device (its next login needs OTP); a legacy JWT without a device bumps
`users.token_version`. `POST /api/auth/logout-all` bumps `token_version` and revokes every device.

Data rights (DPDP Act 2023 / GDPR style): `GET /api/me/export` and `DELETE /api/me` cover access and erasure (sync feed and
idempotency records included); `register` records `consent_at` when the client sends `consent: true`. The consent text, its
versioning and whether consent is mandatory are product/legal decisions, not implemented here. Backups made before a deletion
still contain the user until they are rotated out.
