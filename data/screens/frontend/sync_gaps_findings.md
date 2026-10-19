# Sync gaps (read-only investigation, 2026-10-18)

## 1. Catalog removals not applied on Android — NOT a small fix
- Backend never signals removals: `backend/catalog.py` `import_rows` only creates/updates (L272-322), `bump_catalog` only for touched sections; `app.py` `track()` writes tombstones only for user-owned models; SYNC_API.md catalog export has no tombstones.
- Android catalog download is upsert-only: `sync/CatalogLoader.kt:64-100`, `sync/RoomSync.kt:100-104`; `finish` only clears progress/etags (L118-122). Nothing prunes rows missing from the export. SyncEngine skips an entity when its version is unchanged (L123-127).
- Needed: server-side removal signal (tombstone or retired flag) + version bump on removal (likely a migration), then ~40 lines of Android pruning. Android-only pruning is inert without the backend change.

## 2. Judged lift form data rarely uploaded — needs a product/backend decision
- Squat/push-up always send formscore (`network/SahiRepApi.kt:70-80`).
- Lifts: `allJudged = attemptedReps == totalReps` (SahiRepApi.kt:84) is almost never true because the first BASELINE_REPS=2 reps are calibration and never judged (LiftSession.kt:359, 371-385), and zero-fault lifts are never judged. Form fields are nulled (89-91); SyncPayloadsTest.kt:62 asserts that.
- Backend accepts formscore for "other" (app.py:229-232) but requires clean+attempted == rep_count (app.py:247-251); lift rep_count includes baseline reps, so sending form fields now would be rejected as count_mismatch.
- Options: (a) Android only: send rep_count = judged reps for lifts (~10 lines; changes lift rep totals = product decision); (b) backend: relax app.py:247 to `<=` for "other" (1-3 lines, no schema change) + Android `allJudged` -> `attemptedReps > 0` (~3 lines).
- Not changed: both touch backend or product semantics.

## 3. Plank hold time not synced
See plank_sync_findings.md: holds are intentionally not saved (UNSUPPORTED); server has no duration field; needs a server field (+ Room schema change on Android).
