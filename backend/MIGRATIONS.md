# Migrations (Flask-Migrate / Alembic)

- Set `SAHIREP_JWT_SECRET`, `FLASK_APP=app:create_app`; run `python -m flask db upgrade` to create/update the DB (`SAHIREP_DATABASE_URI` / `DATABASE_URL` override the default `instance/sahirep.db`).
- Change a model, then `python -m flask db migrate -m "msg"`; review the script, then `upgrade`. `python -m flask db check` fails if the models and migrations disagree.
- Never edit an applied migration; add a new one. `db.create_all()` only runs when `create_app(database_uri=...)` is passed (tests).
- Pre-alembic backup of the live DB: `data/backup/sahirep.db.pre-alembic`; before 0008: `data/backup/sahirep.db.pre-0008`.
- 0003 downgrade is safe with FKs (FK-off rebuild); full downgrade to 0001 is lossy (drops later columns/data, e.g. non-squat/push-up sets per 0006, null form fields per 0007, all sync state per 0008).
- SQLite table rebuilds that touch referenced tables (0003, 0005, 0006 downgrade, 0008) switch `PRAGMA foreign_keys` off in an `autocommit_block` and prove integrity with `foreign_key_check`; those blocks are `sqlite`-only, Postgres runs plain `ALTER`s.
- 0008 (sync): `client_id` + `updated_at` on workout_sets, food_logs, weight_logs, user_profiles, user_plans, plan_completions (backfilled), tables `sync_changes`, `sync_ops`, `catalog_meta`. Tombstones are `sync_changes` rows (`op='delete'`), there is no `deleted_at` column. Existing rows are seeded into `sync_changes` so a new device pulling from cursor 0 gets them.
- Postgres review of 0001-0008: only 0006 needed a change (a data step compared a boolean to `1`; it now binds a real boolean, identical on SQLite). Everything else is `batch_alter_table`, named constraints and standard SQL. Not yet executed against a live Postgres: see DEPLOY.md.
- `sync_ops` retention: `flask purge-sync-ops [--days 30]` (run daily from cron).
- 0009 (device sessions, SECURITY_DESIGN.md section 8): nullable `users.email` (index `ix_users_email`, not unique) and `users.email_verified_at`, tables
  `devices`, `otp_challenges` (its nullable `email` holds a pending address until verified), `security_events`, `auth_locks`. Upgrade is ADD COLUMN + CREATE TABLE only (no rebuild, no FK-off). Downgrade drops the
  four tables (device trust, pending OTPs, audit log: lossy by nature) and the two `users` columns inside the 0003 FK-off block with `foreign_key_check`.
  Verified on a scratch copy of the live DB: upgrade, `db check`, `downgrade 0008`, upgrade; users/workout_sets/food_logs/sync_changes counts unchanged,
  `foreign_key_check` empty. Live DB NOT yet upgraded.
- Before 0009 on the live DB (server stopped): back up with the SQLite backup API (handles WAL), then upgrade:
  `python -c "import sqlite3; sqlite3.connect('instance/sahirep.db').backup(sqlite3.connect('../data/backup/sahirep.db.pre-0009'))"`,
  then `flask db upgrade` and the smoke test in DEPLOY.md (register with email + device_id, read the DEV OTP from the log, verify, sync pull).
  Once done, record it here: before 0009: `data/backup/sahirep.db.pre-0009`.
- `security_events` retention: `flask purge-security-events [--days 90]` (minimum 30; the threat engine reads 7-day windows).
