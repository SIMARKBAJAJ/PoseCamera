# Plan import schema

Run: `flask --app app import-plans plans.json` (or call `plans.import_plans(dict)`).
Upserts by key, so re-importing is idempotent. Bad rows are skipped and reported; the rest load.
Prints `{"created": {...}, "updated": {...}, "errors": [{"section", "index", "error"}]}`.

Sections are optional, unknown fields are rejected. Import order: sources, plans.
Sources are the shared `sources` table (see CATALOG_SCHEMA.md).

```json
{
  "sources": [{"title": "...", "url": "...", "publisher": "...", "year": 2017, "evidence_level": "..."}],
  "plans": [
    {"slug": "lower-kebab-case", "name": "...",
     "level": "beginner|intermediate|advanced", "goal": "strength|hypertrophy|general",
     "days_per_week": 3, "split": "full_body|upper_lower|push_pull_legs",
     "verified": false, "notes": "...", "evidence_notes": ["claim to double-check"],
     "sources": ["<source title>"],
     "days": [
       {"name": "Full body A", "slots": [
         {"movement_pattern": "squat", "muscles": ["quadriceps"], "sets": 3,
          "rep_min": 6, "rep_max": 12, "rest_s": 120, "rir": 2, "difficulty_max": "beginner"}
       ]}
     ]}
  ]
}
```

Key: plan `slug`. Required: `slug`, `name`, `level`, `goal`, `days_per_week` (2-6), `split`, `days`
(>= 1, each with >= 1 slot). Slot limits: sets 1-10, reps 1-100 with `rep_min <= rep_max`,
`rest_s` 0-600, `rir` 0-5, `difficulty_max` defaults to `advanced`.
Re-importing a slug replaces its days and slots. `days` is the rotation, in order.
`verified: true` is stored only if the plan cites at least one source; unsourced plans stay `false`.

Slots never name an exercise. They are matched to the catalog when read, so catalog exercises
need these vocabularies (case-insensitive):

- `movement_pattern`: squat, lunge, hinge, knee_flexion, horizontal_push, vertical_push,
  horizontal_pull, vertical_pull, elbow_flexion, elbow_extension, plus the accessory values
  lateral_raise, front_raise, rear_delt, upright_row, shrug, chest_fly, calf_raise, wrist_curl,
  core_stability, core_rotation, core_flexion, hip_extension, neck. Slots match by exact string.
  The built-in generator uses core_flexion, core_stability, hip_extension, lateral_raise,
  rear_delt and calf_raise from the accessories (see below).
- muscles: quadriceps, glutes, hamstrings, chest, shoulders, back, biceps, triceps, calves,
  abdominals, forearms, neck.

Resolution: same pattern, a primary muscle in the slot `muscles`, difficulty <= `difficulty_max`,
machine type allowed by equipment (`gym` any; `home` free-weight/bodyweight; `bodyweight`
bodyweight only; an exercise without a machine has unknown equipment and is gym-only). Preference order: verified,
then (gym only) machine-based, then the hardest allowed difficulty, then slug. No match gives
`exercise: null` and `unmet`: the first failing criterion (`movement_pattern`, `muscles`,
`difficulty_max`, `equipment`).

Isolation fallback (`home`/`bodyweight` only): an unmet `elbow_flexion`, `knee_flexion` or
`elbow_extension` slot retries as a compound whose exercises work the same muscle (primary OR
secondary): biceps -> `vertical_pull`, `horizontal_pull`; hamstrings -> `hinge`; triceps ->
`horizontal_push`, `vertical_push`. Every other criterion, equipment included, still applies, and
nothing is invented: if no compound qualifies the slot stays unmet. A substituted slot has
`movement_pattern` set to the compound and `substituted_from` set to the original pattern; the key
is absent otherwise.

# Built-in generator

`plans.generate_plan(level, goal, days_per_week)` builds a plan from named constants in `plans.py`
(citations in comments). Output is always `verified: false` with `evidence_notes` listing every
claim to check. Splits: beginners always full body; others full body (2-3 days), upper/lower
(4-5), push/pull/legs x2 (6). Weekly sets are counted on each slot's first muscle only.
"Medium" = `intermediate`.

Accessory slots: `PATTERN_MUSCLES` maps all 13 accessory patterns to muscles (they count as
isolation: 8-15 reps, 75 s rest). Splits add core (`core_flexion` full body A and lower/legs days,
`core_stability` full body B), `hip_extension` (lower/legs days), `lateral_raise` (upper, push),
`rear_delt` (upper, pull) and `calf_raise` (lower, legs). Full body days get no delt/calf slots.
When several slots share a muscle and rounding would drop its weekly sets below the level minimum,
sets per slot are rounded up instead.

Bodyweight: no catalog exercise for `lateral_raise`, `rear_delt` or `calf_raise` can be done without
equipment (raw equipment is dumbbell, cable, bands, machine, barbell or other; "Donkey Calf Raises"
and "Sled Reverse Flye" are `other`, i.e. unknown, and stay unmapped). `resolve_plan` therefore omits
these slots when `equipment=bodyweight` (`plans.BODYWEIGHT_OMITTED`) instead of returning them as
unmet; bodyweight sessions simply have no direct delt-isolation or calf work. Gym and home resolve
all of them. If the catalog gains a bodyweight exercise for one, remove it from that set. Over all
level/goal/days: bodyweight unmet 177 of 1188 slots, gym and home 0 of 1308.

`plans.double_progression(last_reps, rep_min, rep_max, load)`: all sets at `rep_max` -> load +5%
(min 0.5 kg, rounded to 0.5) and reps back to `rep_min`; otherwise same load, weakest set + 1 rep.

# API (JWT = `Authorization: Bearer <token>`)

Public: `GET /api/plans?level=&goal=&days=`, `GET /api/plans/<slug>?equipment=gym|home|bodyweight`
(slots resolved against the catalog).

JWT:

- `POST /api/plans/generate {days_per_week, level?, goal?, equipment?, save?}`: level defaults to
  beginner. goal defaults to the profile goal (gain -> hypertrophy, maintain/lose -> general), else
  general. Returns the resolved plan plus `saved`. `save: true` creates it as `builtin-<level>-<goal>-<n>d` only if that slug is free; otherwise the existing plan is returned unchanged (200, `existing: true`).
- `POST /api/me/plan {slug, start_date?, equipment?}`: assign (replaces the current plan, resets progress).
- `GET /api/me/plan/today`: `day_index`, `completed_today`, resolved `day`. The day is the next one in
  rotation after the last completed day, or the day completed today if there is one. Each slot has
  `kcal` (MET x kg x (sets x (avg reps x 3 s + rest)), null without MET or weight), and the day has
  `kcal_estimate` (sum of known slots). Weight comes from the latest weigh-in, else the profile.
- `POST /api/me/plan/complete-day {day_index?, date?}`: logs a completion (default: the next day, today in SAHIREP_TZ). One per plan per date: a repeat the same date returns 200 with the existing one and does not advance. Rotation follows the latest completion by (date, id); day indexes wrap modulo the plan length if a re-import shortened it.
