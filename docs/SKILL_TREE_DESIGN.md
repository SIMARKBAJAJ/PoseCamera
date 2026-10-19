# SahiRep C1: Skill Tree design

Status: design only (read-only architect pass, 2026-10-19). Nothing implemented. Every number below is an UNVERIFIED PROJECT DEFAULT. Not medical advice.

## Decisions by the user (2026-10-19)
- Steps missing from the catalog: **local-only first** (no backend catalog change; Room table in Phase 2).
- Pose steps unlock on **all reps** (not only clean reps).
- Defaults taken without asking: "Next up" is recency-based (no goal skill, no equipment filter in Phase 1); no new pose hold configs now (AGENTS.md scope rule; the hold timer cannot confirm position).

Notes: AGENTS.md asks to read simar.md; that file does not exist. This design adds no new pose configs; it reuses the 4 existing ones plus manual logging.

## 0. What the code says
### Catalog
- Source `data\out\exercises.json` (876 exercises). No Android asset copy: `app\src\main\assets` holds only `pose_landmarker_lite.task`. Room `catalog_exercises` is filled by streaming `/api/catalog/export` (`sync\CatalogLoader.kt:48-55`), so the backend catalog decides which slugs exist.
- All entries have `verified:false`. Only `bodyweight-squat` and `pushups` have `has_pose_tracking:true`; that flag is stale vs the app's `Lifts`, so the tree must use the app registry.

### Storage
- `LocalSet` has no duration column (`data\Entities.kt:64-81`). Plank holds are refused at save time: `saveBlocker` returns UNSUPPORTED when `holdSeconds != null` (`sync\SyncPayloads.kt:71-75`, `data\LocalRepository.kt:46-55`), so holds are never stored today.
- Camera squat/push-up rows are saved with `exerciseSlug = null` and `exerciseType "squat"/"push-up"` (`SyncPayloads.kt:88-91`); backend maps them with `POSE_SLUG = {"squat":"bodyweight-squat","push-up":"pushups"}` (`backend\app.py:80`).
- Backend accepts `exercise_type "other"` with any catalog slug plus rpe, load_kg, rep_count > 0 (`backend\app.py:78, 252-283`); unknown fields are rejected (`backend\SYNC_API.md:31-33`). So rep steps that are in the catalog can be logged and synced at zero backend cost; steps not in the catalog would come back syncFailed. Syncing holds needs a backend field + migration: not cheap.

### Manual logging today
- Only `Lifts.all` entries with a `repJoint` (`LibraryLogic.kt:90-95`, `TrainLogic.kt:64-75`, `WorkoutLogScreen(repo, config: LiftConfig)` `WorkoutLogScreen.kt:86-87,121,153`, `TrainScreen.kt:59-60,77-80`). Chin-ups, dips, scapular pulls etc. cannot be logged today. The screen only depends on LiftConfig for slug, label and manualSummary, so it is easy to generalise.

### Pose tracking
- `Lifts.all` has 22 lifts (`lifts\Lifts.kt:491-495`); camera also does SQUAT/PUSH_UP via RepCounter. In the skill families pose tracking exists for exactly 4 steps: pushups, bodyweight-squat, pullups (`Lifts.kt:304-326`), plank (`Lifts.kt:214-232`, hold timer, result not stored).
- Hold path is generic (`LiftModel.kt:120` repJoint=null = isometric; `LiftSession.kt:497-545`) but only times steadiness vs your own first 3 s; it does NOT check you are in the skill position. No L-sit, dip, hollow or dead-hang configs.

### Room
- Version 2; `MIGRATION_1_2` is one ALTER (`data\AppDatabase.kt:16-20,30,43`); `exportSchema` writes `app\schemas\...\2.json`. No migration test harness (no room-testing dependency, no androidTest). The 1->2 migration was never verified on an upgrade.
- Account switch runs `db.clearAllTables()` (`network\SahiRepRepository.kt:105-107`); SharedPreferences are NOT cleared.

### Other
- `validSets`/`isValid` = `repCount > 0 && !syncFailed` (`ui\home\HomeLogic.kt:51-54`); day bucket `dayOf()` (`TrainLogic.kt:49`). `recentSets(limit=500)` (`LocalRepository.kt:58`): achievements derived from it would "un-achieve" past 500 sets, so use an unlimited slug-filtered query.
- Nav is state-driven: `AppShell` `overlay` string ("plan", "nutrition") (`ui\AppShell.kt:80,106-119`). Home cards `HomeScreen.kt:157-219`. `PlanScreen.RoadRow` (`ui\plan\PlanScreen.kt:118-136`) is the node/path idiom, idle ping gated by `allowIdleLoops`.
- Design rules: Sahi green means correct form only; Haldi never means good form (`Tokens.kt:11`, `DESIGN_BRIEF.md:54-59`). Snap-to-plumb: spring 0.55/380, +-6 deg overshoot. Gating via `MotionSpec`: FULL / REDUCED tween(150) / OFF snap (`ui\design\Motion.kt:22-58`). `Scene3`/`Cam3` is a pooled allocation-free depth-sorted renderer with ortho option (`ui\design\g3d\Cam3.kt:36-53,108-156`).

## 1. Catalog audit (tell the user)
Already in the catalog:
- Pull: scapular-pull-up, inverted-row, band-assisted-pull-up, chin-up (also mixed-grip-chin, one-arm-chin-up), pullups (also wide-grip-rear-pull-up, v-bar-pullup), weighted-pull-ups, one-handed-hang, muscle-up, kipping-muscle-up.
- Push: incline-push-up (+4 grip variants), pushups, decline-push-up, push-ups-with-feet-elevated (duplicates decline), push-ups-close-triceps-position (nearest to a diamond push-up), single-arm-push-up, handstand-push-ups.
- Dips: bench-dips, parallel-bar-dip, dips-triceps-version, dips-chest-version, ring-dips, weighted-bench-dip.
- Legs: bodyweight-squat, split-squats, single-leg-high-box-squat, bodyweight-walking-lunge. Pistol squats exist only loaded: kettlebell-pistol-squat, smith-machine-pistol-squat.
- Core: plank, side-bridge, flat-bench-lying-leg-raise, knee-hip-raise-on-parallel-bars, hanging-leg-raise, hanging-pike.

MISSING from the catalog (25 total):
- Pull: two-arm dead hang, negative pull-up, archer pull-up, straight-bar dip.
- Push: knee push-up, a push-up named "diamond", archer push-up, pike push-up.
- Legs: Bulgarian split squat, assisted pistol, bodyweight pistol squat, shrimp squat.
- Core: hollow body hold, tuck L-sit, L-sit, toes-to-bar, dragon flag (and its negative).
- Skills: wall handstand hold, freestanding handstand, planche lean, pseudo-planche push-up, frog/crow stand, tuck/straddle/full planche, tuck/advanced-tuck/straddle/full front lever, every human-flag step.

## 2. Data-model decision
Phase 1: derive rep progress on the fly, no table. Read `local_sets` through a new unlimited DAO query filtered to skill slugs; map each set to a step as `exerciseSlug ?: CAMERA_SLUG[exerciseType]` (`CAMERA_SLUG` mirrors `POSE_SLUG`). Rep steps that are in the catalog log through a new generic `saveManualSet(slug,...)` with `exercise_type="other"` and sync for free. Holds and not-in-catalog steps are ADVISORY in Phase 1: shown, do not block later steps, never suggested as Next up.

Phase 2: one local-only table `skill_logs`, needed for (1) holds (no storage anywhere), (2) not-in-catalog steps (would be syncFailed in local_sets), (3) explicit "I can already do this" self-marks. A Room table is wiped by clearAllTables on account switch (prefs would leak across accounts). Sync: local-only (server rejects unknown fields; syncing holds is a separate backend task). No goal-skill state in Phase 1; Next up is driven by recency.

## 3. Deliverables
### a) Families, ordered steps, branching (topological order; every `requires` entry appears earlier)

PUSH
| id | in catalog | pose | requires |
|---|---|---|---|
| incline-push-up | yes | N | root |
| pushups | yes | Y | incline |
| decline-push-up | yes | N | pushups |
| push-ups-close-triceps-position (close/diamond-style) | yes | N | pushups |
| archer-push-up | NOT IN CATALOG | N | decline + close |
| single-arm-push-up | yes | N | archer |

PUSH, dips branch
| id | in catalog | pose | requires |
|---|---|---|---|
| bench-dips | yes | N | root |
| parallel-bar-dip | yes | N | bench-dips + pushups |
| ring-dips | yes | N | parallel-bar-dip |

PUSH, planche-lean branch
| id | in catalog | pose | requires |
|---|---|---|---|
| planche-lean (hold) | NOT IN CATALOG | N | pushups + plank |
| pseudo-planche-push-up | NOT IN CATALOG | N | planche-lean + decline |

LEGS
| id | in catalog | pose | requires |
|---|---|---|---|
| bodyweight-squat | yes | Y | root |
| split-squats | yes | N | squat |
| bulgarian-split-squat | NOT IN CATALOG | N | split |
| single-leg-high-box-squat | yes | N | split |
| assisted-pistol-squat | NOT IN CATALOG | N | box squat |
| pistol-squat | NOT IN CATALOG | N | assisted + bulgarian |
| shrimp-squat | NOT IN CATALOG | N | bulgarian |

PULL
| id | in catalog | pose | requires |
|---|---|---|---|
| dead-hang (hold) | NOT IN CATALOG | N | root |
| inverted-row | yes | N | root |
| scapular-pull-up | yes | N | dead-hang |
| negative-pull-up | NOT IN CATALOG | N | scapular + inverted-row |
| band-assisted-pull-up | yes | N | negative |
| chin-up | yes | N | band |
| pullups | yes | Y | chin-up |

CORE
| id | in catalog | pose | requires |
|---|---|---|---|
| plank (hold) | yes | Y (timer; storage arrives in Phase 2) | root |
| hollow-body-hold (hold) | NOT IN CATALOG | N | plank |
| flat-bench-lying-leg-raise | yes | N | plank |
| knee-hip-raise-on-parallel-bars | yes | N | lying leg raise |
| hanging-leg-raise | yes | N | knee raise + dead-hang |
| tuck-l-sit (hold) | NOT IN CATALOG | N | hollow + knee raise + bench-dips |
| l-sit (hold) | NOT IN CATALOG | N | tuck-l-sit |

32 steps; 12 are not in the catalog. Shared foundation: incline push-up, squat, plank, dead hang, inverted row. Specialisations cross families through `requires`, so this is a DAG, not a strict tree.
Phase 3 (data rows only): pike push-up -> wall handstand -> handstand -> handstand-push-ups; muscle-up (pullups + parallel-bar-dip); one-arm-chin-up via archer pull-up; tuck front lever (pullups + hanging-leg-raise); dragon flag (hanging-leg-raise + hollow); human flag (from side-bridge); weighted-pull-ups (needs a needsLoad field).
Pose-trackable is derived, not hand-set: `id in Lifts.all.map { it.catalogSlug } + CAMERA_SLUG.values`.

### b) Unlock criteria: one table in `skills\SkillTree.kt`
```kotlin
/* EVERY number in this file (targets, 3 sets, 2 sessions, RPE 8, plateau 4 sessions, 21-day staleness, RPE 9 deload)
 * is an UNVERIFIED PROJECT DEFAULT: no cited source, not coach-reviewed. Not medical advice. */
enum class Family(val label: String) { PUSH("Push"), LEGS("Legs"), PULL("Pull"), CORE("Core") }
enum class Metric { REPS, SECONDS }
class Step(
    val id: String, val name: String, val family: Family, val inCatalog: Boolean, val requires: List<String>,
    val metric: Metric, val target: Int, val sets: Int = 3, val sessions: Int = 2, val maxRpe: Double = 8.0,
    val perSide: Boolean = false,
)
```
Targets (per set; reps unless seconds; /side = per arm or leg):
- PUSH: incline 15, pushups 12, decline 10, close 10, archer 6/side, one-arm 3/side, bench dip 12, parallel-bar dip 8, ring dip 6, planche lean 15 s, pseudo-planche push-up 8.
- LEGS: squat 20, split 10/leg, Bulgarian 10/leg, box single-leg 8/leg, assisted pistol 6/leg, pistol 3/leg, shrimp 3/leg.
- PULL: dead hang 30 s, inverted row 10, scapular 10, negative 5, band-assisted 8, chin-up 5, pullups 8.
- CORE: plank 45 s, hollow 30 s, lying leg raise 12, parallel-bar knee raise 12, hanging leg raise 8, tuck L-sit 10 s, L-sit 10 s.

Criterion: a step is achieved on `sessions` (2) distinct local days. A day qualifies with at least `sets` (3) qualifying sets. A set qualifies when `value >= target && (rpe == null || rpe <= maxRpe) && setType != "warmup"`. A self-mark (Phase 2) also counts as achieved.

### c) Pure functions (`skills\SkillLogic.kt`, no Android imports)
```kotlin
class Evidence(val stepId: String, val value: Int, val rpe: Double?, val ts: Long)
enum class StepState { LOCKED, OPEN, ACHIEVED, ADVISORY }
class StepStatus(val state: StepState, val achievedAt: Long?, val implied: Boolean, val qualifyingDays: Int, val lastBest: Int?, val selfMarked: Boolean)
fun evidenceOf(sets: List<LocalSet>, logs: List<SkillLog> = emptyList()): Map<String, List<Evidence>>
fun statusOf(steps: List<Step>, ev: Map<String, List<Evidence>>, marked: Set<String>, canLog: (Step) -> Boolean, tz: TimeZone): Map<String, StepStatus>
fun nextUp(steps: List<Step>, st: Map<String, StepStatus>, ev: Map<String, List<Evidence>>, canLog: (Step) -> Boolean): Step?
sealed interface Plateau { data object None; data class Regress(val to: Step); data object Deload }
fun plateauOf(step: Step, ev: List<Evidence>, st: Map<String, StepStatus>, nowMs: Long, tz: TimeZone, window: Int = 4): Plateau
fun layoutOf(steps: List<Step>): Map<String, Pair<Float, Int>> // x lane, depth = 1 + max(depth of requires)
```
- evidenceOf: validSets only; step id = `exerciseSlug ?: CAMERA_SLUG[exerciseType]`; ids that are not steps are dropped; value = repCount (all counted reps, not cleanCount: user decision).
- statusOf walks the table in order: `canLog` false (Phase 1: a hold or not-in-catalog step) and not marked -> ADVISORY (does not block later steps); achieved -> ACHIEVED even if prerequisites are not done; every prerequisite ACHIEVED or ADVISORY -> OPEN; else LOCKED. A reverse pass marks ancestors of every ACHIEVED step as ACHIEVED with implied=true, so a pull-up doer is not told to do chin-ups.
- nextUp: candidates are OPEN steps with canLog true, sorted by (1) the family's most recent evidence newest first (never-trained families last), (2) steps with evidence first, (3) lower depth, (4) table order. Edge cases: no data -> incline-push-up; no candidates -> null ("Map complete for now"); a plateaued step is still next up with an advice line; equipment is ignored.
- plateauOf: only for an OPEN step. Sessions are local days with that step's evidence. Progress = a new best set (strictly greater) or more qualifying sets than any earlier day. Plateau = at least window+1 (5) sessions, the last 4 show no progress, latest session within 21 days (older = return from a break). Outcome: mean RPE over those 4 sessions >= 9 -> Deload ("try an easier week: fewer sets"); otherwise Regress(first `requires` entry that can be logged), or Deload if none (root). Edge cases: ties = no progress; missing RPE = unknown (leads to Regress); several sets on one day = one session; a day spanning midnight counts as 2 days (documented).

### d) Canvas visual (`ui\skills\SkillMap.kt`)
Geometry: world positions from `layoutOf`: lane x per family (-1.5..1.5, branches 0.6 apart), y = depth x 0.5. Foundation near the bottom; progressions climb and recede under `Cam3(yaw=0, pitch~0.35, dist~6)`: a tilted steel plate, one pooled `Scene3` (`Cam3.kt:108`). A vertical Hairline plumb line runs up each family spine.
Nodes are joints with a Slate2 shadow dot at z-0.03 for thickness:
- LOCKED: Hairline ring. OPEN: Chalk ring + dot. ACHIEVED: Chalk filled; bones between achieved nodes turn Haldi ("the line you built"). ADVISORY / not in catalog: dashed Dust ring (offline idiom). Pose-trackable: small Neel dot ("camera can count"). Next up: hosts the Haldi plumb bob with a ping. Unlock moment: Mehendi, once.
- Sahi green is NEVER used (it means form only). Plateau is Dust text, not Sindoor.
Labels/input: micro labels measured once with `rememberTextMeasurer()` and cached per id. Tap: projected positions from the last draw in a FloatArray; nearest node within 24 dp wins and opens a sheet (`ui\design\Sheet.kt`) with the criterion, progress ("best 6 / 8, 1 / 2 days"), why it is locked, a catalog-viewer link and Log. Vertical drag pans `camTargetY`, read in the draw scope only.
Snap-to-plumb unlock plays for at most one node: the newest step with `achievedAt` within 24 h (no stored "seen" flag). FULL: incoming bone swings from +6 deg to 0 with `motion.snapSpring()`, then a Mehendi ring ping with `motion.ping()` plus glow. REDUCED: no swing/glow, alpha ping via tween(150). OFF: static plus text "Naya unlock: X". The next-up idle ping is the screen's single infinite transition, FULL only (`allowIdleLoops`). CRITICAL/OFF uses an orthographic camera at pitch 0 (flat 2D) per the brief.
Accessibility: MAP / LIST `SahiChip` toggle. LIST is a LazyColumn grouped by family, each row with name, state in words ("Next up", "Locked: needs Chin-up", "Not in our library yet"), criterion sentence, progress, and "Camera counts this" / "Log by hand". The Canvas has a `semantics` contentDescription pointing to LIST. State is carried by shape as well as colour; hit targets >= 48 dp.
Performance budget (UNVERIFIED until tested on a real budget phone): <= 64 nodes, ~300 draw items; zero allocations per frame after warm-up; no per-frame recomposition (animated values read in draw only); derivation O(sets + steps), recomputed only when the Flow emits; target map draw <= 4 ms on a Snapdragon-4xx-class phone (Profile HWUI rendering).

### e) Data model and migration
Phase 1: none. `UserDao`:
```kotlin
@Query("SELECT * FROM local_sets WHERE exerciseSlug IN (:slugs) OR (exerciseSlug IS NULL AND exerciseType IN ('squat','push-up'))")
fun skillSets(slugs: List<String>): Flow<List<LocalSet>>
```
`LocalRepository`: `skillSets()` and `saveManualSet(slug, reps, loadKg, rpe)`; returns NO_REPS when reps <= 0; otherwise builds `LocalSet(id, "other", slug, reps, null, null, null, loadKg, rpe, null, t, t, false)` and saves with outbox op E_SETS upsert the same way `saveSet` does.

Phase 2: one migration. Entity:
```kotlin
@Entity(tableName = "skill_logs", indices = [Index("stepId")])
data class SkillLog(@PrimaryKey val clientId: String, val stepId: String, val reps: Int?, val seconds: Int?,
    val rpe: Double?, val selfMarked: Boolean, val timestamp: Long)
```
`AppDatabase.kt`: add `SkillLog::class`, `version = 3`, `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`:
```kotlin
val MIGRATION_2_3_SQL = listOf(
  "CREATE TABLE IF NOT EXISTS `skill_logs` (`clientId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `reps` INTEGER, `seconds` INTEGER, `rpe` REAL, `selfMarked` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`clientId`))",
  "CREATE INDEX IF NOT EXISTS `index_skill_logs_stepId` ON `skill_logs` (`stepId`)")
val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_2_3_SQL.forEach(db::execSQL) }
```
Steps: (1) build once so KSP writes `app\schemas\com.example.posecamera.data.AppDatabase\3.json`; (2) make the constant byte-identical to 3.json's createSql (with `${TABLE_NAME}` substituted); keep 3.json beside 1.json and 2.json; (3) UserDao: `@Insert suspend fun insert(row: SkillLog)`, `skillLogs(): Flow<List<SkillLog>>`, a DELETE for un-marking; (4) LocalRepository: `saveHold(stepId, seconds, rpe)`, `markStep(id)`, `unmarkStep(id)`; (5) no outbox op, no SyncPayloads/RoomSync change; (6) plank camera path: `SetSummaryScreen`/`LocalRepository` save `holdSeconds.roundToInt()` (>= 1) as a `SkillLog("plank")`; message "Saved on this phone; holds don't sync yet" (also fixes the known gap at HANDOFF:35); (7) Phase 2 makes `canLog` true for every step.
Migration test plan (pure JVM, no new dependency; Gson is already present): `MigrationSqlTest` reads 3.json and asserts (a) skill_logs createSql equals `MIGRATION_2_3_SQL[0]`, (b) index SQL equals `[1]`, (c) every table's createSql in 2.json is unchanged in 3.json, (d) version is 3. Emulator upgrade smoke (SahiRep_API_36): install the current v2 debug APK and seed; log one manual set; `adb install -r` the v3 build and launch; logcat must not contain "Migration didn't properly handle"; Home still shows the set; save a plank hold, relaunch, confirm it persists; also test a fresh install.

### f) Files and phases
All tests are JVM tests under `app\src\test\java\com\example\posecamera\`. Run: `cd /d/coding/sahirep; cmd //c ".\gradlew.bat --offline testDebugUnitTest assembleDebug"` with JAVA_HOME = `D:\coding\toolchains\jdk17\jdk-17.0.20.1+1`.

Phase 1 (no schema change): 4 new files, 7 edits.
- New: `skills\SkillTree.kt` (table + CAMERA_SLUG), `skills\SkillLogic.kt`, `ui\skills\SkillTreeScreen.kt` (MAP/LIST toggle, sheet, Log routing), `ui\skills\SkillMap.kt` (Canvas).
- Edits: `data\Daos.kt` and `data\LocalRepository.kt` (section e); `ui\train\WorkoutLogScreen.kt` (parameter becomes `(repo, key: String, label: String, save: suspend (reps, kg, rpe) -> SaveResult, onBack)`); `ui\train\TrainScreen.kt` (route `selected = "slug:<id>"` to the generic logger; lifts and camera types keep their routes); `ui\home\HomeScreen.kt` (add a `NextUpContent` ChamferCard after the hero card, before `RecoveryContent`, showing "NEXT UP", step name, criterion, progress, optional plateau line, "Log it" and "Skill map" buttons; new `onOpenSkills` and `onLog` params); `ui\AppShell.kt` (`overlay == "skills"` -> `SkillTreeScreen(repo, onBack, onLog = {...})`); `ui\library\LibraryScreen.kt` (a "SKILLS" chip next to the "Library" header).
- Phase 1 tests:
  - `skills\SkillTreeTest`: ids unique; every `requires` entry exists and appears earlier (acyclic); every `inCatalog` id exists in `../data/out/exercises.json` (assumeTrue when the file is missing); no not-in-catalog id exists in that catalog; the pose-derived steps are exactly {pushups, bodyweight-squat, pullups, plank}.
  - `skills\SkillLogicTest`: camera rows with null slug map to pushups / bodyweight-squat; syncFailed, 0-rep and warmup sets excluded; day bucketing UTC vs IST; RPE null qualifies and 8.5 does not; 3 sets on 1 day is not achieved, on 2 days it is; implied ancestors; ADVISORY does not block; LOCKED until all prerequisites achieved; next-up cases (empty data -> incline; most recent family wins; in-progress beats untouched; all achieved -> null; ADVISORY never chosen); plateau cases (4 sessions -> None; 5 flat -> Regress(prerequisite); 5 flat at RPE 9.5 -> Deload; flat root -> Deload; a new best in the window -> None; more qualifying sets counts as progress; last session older than 21 days -> None; same-day sets are one session); layout (depth is the longest path; no two nodes share an (x, depth) position).
  - `ui\train\TrainLogicTest` additions: the `saveManualSet` row and payload have exercise_type "other", the slug, and null form fields.

Phase 2 (migration): edit `data\Entities.kt`, `data\AppDatabase.kt`, `data\Daos.kt`, `data\LocalRepository.kt`, `ui\SetSummaryScreen.kt`, `ui\skills\SkillTreeScreen.kt` (hold entry with seconds stepper and RPE; self-mark labelled "self-marked"); add `schemas\...\3.json`. Tests: `data\MigrationSqlTest`; SkillLogic with hold logs and self-marks; the plank save mapping; then the emulator smoke.

Phase 3: advanced data rows, a "Part of: Pull path" chip in `ExerciseViewerScreen`, equipment filter if chosen. Same integrity tests over the new rows.

### g) Honest caveats (shown in the app or docs)
1. Every target, set count, session count, RPE cap, plateau window, staleness window and deload trigger is an UNVERIFIED PROJECT DEFAULT; none is cited or coach-reviewed.
2. The progression order is a common convention, not validated for any individual.
3. The camera counts only 4 steps, and even those are counting plus unverified baseline-relative cues. The hold timer measures steadiness, NOT that you are in the position.
4. No form coaching for any other step, especially advanced skills. Nothing checks wrist, elbow or scapular load; negative-rep tempo is not measured.
5. RPE is self-reported and optional. Camera sets have no RPE and count as qualifying.
6. Per-side reps are taken on trust.
7. Holds, not-in-catalog steps and self-marks are local-only: they do not sync and are lost on account switch or reinstall.
8. Not medical advice. Stop if something hurts.
9. The migration is verified only by the SQL-vs-schema test and the emulator; the 1->2 migration was never verified either.
10. Real-phone performance has not been verified.

Needs a real person or phone: a coach or physio to review step order and thresholds; a budget phone for frame rate and jank; a TalkBack pass; an upgrade install on a phone with real data; a Hinglish copy review; any claim that the camera can count incline push-ups or chin-ups (NOT claimed now).

## 4. Forks (architect's recommendations; decisions above)
1. Missing steps local-only vs added to backend catalog: DECIDED local-only first.
2. Goal skill / equipment filter: default none in Phase 1; equipment filter in Phase 3.
3. All reps vs clean reps for pose steps: DECIDED all reps.
4. New pose hold configs (L-sit, hollow, dead hang): not now.

Single biggest risk: the Phase-2 Room migration. No MigrationTestHelper (would be a new dependency) and the previous migration was never verified; a createSql mismatch crashes on app open for every upgrading user. Mitigations: byte-equality test against 3.json plus the emulator upgrade smoke before release. Phase 1 ships with no schema change.
