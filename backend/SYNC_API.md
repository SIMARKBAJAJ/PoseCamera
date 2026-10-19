# Sync API (phone = Room DB is the source of truth while offline; server = sync hub)

All JSON, JWT `Authorization: Bearer`, errors are `{"error": ...}`. Pose/video never reaches the server.
Existing endpoints (`/api/sets`, `/api/history`, auth, nutrition, plans) are unchanged and their writes
also appear in `pull`.

## Entities and `client_id`
`workout_sets`, `food_logs`, `weight_logs`, `plan_completions`: `client_id` = UUID the phone generates.
`profile` -> client_id `"profile"`, `user_plans` -> client_id `"plan"` (one row per user; cannot be deleted).
Keep the client_id of a row forever; the server never rewrites it.

## POST /api/sync/push   (30/min per user, body <= 1 MB, <= 200 ops)
```
{"device_id": "pixel-7", "ops": [{"op_id": "<uuid>", "entity": "food_logs", "client_id": "<uuid>",
  "action": "upsert"|"delete", "data": {...}, "client_updated_at": "2026-10-16T10:00:00+05:30"}]}
-> 200 {"results": [{"op_id": "...", "status": "applied"|"duplicate"|"conflict"|"rejected", "error": "..."}]}
```
- One result per op, same order. Ops are independent: one failure never loses the others. HTTP 400 only
  for a malformed envelope (no `device_id`, >200 ops, ops not objects); 413 over 1 MB; 429 rate limit.
- `op_id` is the idempotency key (unique per user, kept 30 days). Replaying an op returns its stored result
  and never applies twice: on timeout/crash, resend the same ops with the same `op_id`s.
- Terminal statuses: `applied`, `duplicate` (already there / already gone, nothing to do), `rejected`
  (validation: fix or drop the op, never retry as-is), `conflict` (server holds a newer version: drop the
  local change and take the server row on the next pull).
- `client_updated_at` must carry an offset. It is the last-writer-wins clock, clamped to server now + 5 min.
  Strictly newer wins; equal or older -> `conflict`. A delete older than the row's version -> `conflict`.
  A stale upsert of a row deleted on the server (client time before the server saw the delete) -> `conflict`.
- `workout_sets` are append-only: an upsert of an existing client_id is `duplicate` (no edit); delete works.
- `weight_logs` and `plan_completions` hold one row per day: a newer write for a day replaces the row of
  another client_id (that one is tombstoned), an older one is a `conflict`.
- `data` per entity (same bounds as the REST bodies; unknown fields are rejected):
  - `workout_sets`: `POST /api/sets` body + required `timestamp` (aware ISO; future values clamp to now,
    older than 366 days rejected). `exercise_slug` must exist in the catalog.
  - `food_logs`: `POST /api/nutrition/log` body with `date` required (`food_slug`+`quantity_g`|`serving_id`,
    or quick-add `name`+`kcal`[+macros]; never both). The server freezes per-100 g macros at creation.
  - `weight_logs`: `{kg, date}`. `profile`: `PUT /api/profile` body. `user_plans`: `{slug, start_date, equipment}`
    (a different slug restarts the rotation: the old completions are tombstoned). `plan_completions`:
    `{day_index, date}` (needs an assigned plan).

## GET /api/sync/pull?cursor=N&limit=500   (120/min per user, limit 1..1000)
```
-> {"changes": [{"cursor": 7, "entity": "...", "client_id": "...", "op": "upsert"|"delete",
                 "updated_at": "...Z", "data": {...}|null}], "next_cursor": 7, "has_more": false}
```
- Start at `cursor=0` (new device gets everything), then store `next_cursor` and call again while `has_more`.
- `upsert` carries the row's CURRENT state (same `data` shape as push, plus read-only `computed` macros on
  `food_logs`; do not send `computed` back). `delete` is a tombstone (`data: null`): remove the local row.
  The same client_id may appear several times; apply in order. Pull also returns your own pushed changes.
- Apply a pulled upsert over a local row only if it is not newer than a local unsynced edit (compare
  `updated_at` with your edit time).

## Catalog (public, 60/min per IP)
`GET /api/catalog/version` -> `{"foods": 3, "exercises": 1, "machines": 1, "muscles": 1, "plans": 2}`; compare with
your cached versions and re-download only the entities that moved.
`GET /api/catalog/export?entity=foods|exercises|machines|muscles|plans&limit=500&offset=0` ->
`{entity, version, total, limit, offset, items: [full records: servings, sources, muscles, plan days/slots]}`
(limit <= 1000; page until `offset >= total`; if `version` changes between pages, restart).
Send `Accept-Encoding: gzip` (OkHttp does) and `If-None-Match: <ETag>`: unchanged -> `304`, no body.
Exercise `slug` is the key used by `workout_sets.exercise_slug`; food `slug` by `food_logs.food_slug`.
