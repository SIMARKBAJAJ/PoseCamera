# Plank hold time: not saved, not synced (needs a backend field)

Android: `RepSetSummary.holdSeconds` (LiftSession.endSet, plank only) makes `saveBlocker()` return `UNSUPPORTED` (sync/SyncPayloads.kt), so `LocalRepository.saveSet` stores nothing and enqueues no outbox op. The summary screen says "Plank holds are not synced yet." The hold is therefore also absent from local history/Progress.

Server (backend/app.py `CompletedSetPayload`, `WorkoutSet`, SYNC_API.md `workout_sets`): accepted fields are exercise_type, exercise_slug, rep_count (gt 0, le 1000), clean_count, attempted_count, formscore, load_kg, rpe, set_type, timestamp. Unknown fields are rejected. There is no duration/hold_seconds column.

Missing: a nullable `duration_sec` (or `hold_seconds`) on `workout_sets` + payload + migration, with rep_count allowed to be 0/null for timed sets. Client work after that: add the Room column (schema change), map holdSeconds in `toLocalSet`/`payload()`, drop the UNSUPPORTED blocker.

Not done as a client-only fix on purpose: stuffing seconds into rep_count would pass validation but corrupt volume, PR scoring (Epley on reps) and the Home rep totals, and needs a Room schema change anyway (off limits this round).
