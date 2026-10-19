# SahiRep — Frontend Design Brief (v1)

Dark-only. Compose Canvas, zero new dependencies. 3D only where it tells the lifter something.

## 1. Reference analysis (principles, not copies)

Evidence level: **viewed** = I looked at the Play Store page/screenshots in the built-in browser this session; **recalled** = general knowledge of the app, not re-viewed (treat as lower confidence).

| App | Evidence | Take | Avoid |
|---|---|---|---|
| 90 Day Challenge (Browney) | viewed: Play listing + screenshots | One giant bold caption per screen; a vertical "path" of numbered nodes for the programme (progress = place on a road); front/back red-highlighted muscle figure for "visualize your growth"; streaks + badges as the motivation loop; monthly strength tests | Light/white cards, photo-heavy before/after emphasis, red as the only accent (reads as "alert") |
| Hevy | viewed: Play listing | Logging grid is king: set rows with big tap targets, green check per set, previous-set ghost values; muscle figure beside an exercise; simple line charts | Blue-on-white generic look; chart without meaning |
| Strong / Fitbod | recalled | Strong: zero-friction set entry, rest timer as persistent bar. Fitbod: muscle recovery heat on a body, "why this workout" | Dense tables on small screens |
| Apple Fitness / Strava | recalled | Concentric rings with satisfying close animation; one hero metric per card; activity heat. Strava: restrained palette, one orange accent, share-card | Rings copy: we use a *rep ring* tied to tempo, not a move/exercise/stand trio |
| WHOOP | recalled | Dark, data-confident; one big score with a semantic colour band (recovery/strain) | Score without explanation |
| NTC / Gymshark | recalled | Big condensed type over dark imagery, cinematic motion on workout start | Stock-photo dependence (we have none; figures are ours) |

Cross-cutting principles adopted: (1) one hero number per screen; (2) colour carries state, never decoration; (3) motion explains cause→effect (a rep happened, a set locked, a PR landed); (4) the body figure is the recurring character; (5) bottom-heavy thumb zone for one-handed gym use.

## 2. Identity concept — "The Plumb Line"

*Sahi* = correct / aligned. A plumb line is the oldest test of "is this straight". SahiRep's signature is **the line**: every screen has a thin vertical plumb line with a bead (the "bob") that reflects state — it hangs still when form is sahi, swings when it is off, drops as reps count. This echoes the product's actual tech: pose joints (dots) + bones (lines).

- **Motif:** joint (filled dot, 1 per key point) + bone (hairline capsule). Backgrounds carry a faint vertical grid of plumb lines; section dividers are a bone with end-joints.
- **Shape language:** *chamfered plate* — cards have one 45° cut corner (top-right), like a weight-plate bevel/gym stamped steel; controls are fully round (joints). No generic rounded-rect-everywhere.
- **Indian grounding:** haldi/saffron effort colour, sindoor fault red, kansa-warm neutrals instead of cold grey; Devanagari-friendly type (system fonts, no download); Hinglish microcopy ("Sahi!", "Ek aur", "Thoda seedha"); kg-first with plate maths for local plate sets (1.25/2.5/5/10/20 kg; 25 kg bar optional).
- **Signature motion — "Snap to plumb":** anything that arrives overshoots ±6° / few px then locks to vertical with a tiny joint-ping (ring pulse). Used for rep counts, PR badge, card entry, tab change.
- **Signature 3D object — "The Plumb Figure":** the FK demo figure projected in true perspective, orbitable by drag (yaw/pitch with inertia), with a **ghost target pose** and a vertical plumb plane through the lifter's centre of mass/bar path. It answers: *"where is my bar/limb versus where it should be, from any angle?"*

## 3. 3D inventory (each must answer a question)

| 3D element | Question it answers | Tech (zero dep) | SAVER | CRITICAL |
|---|---|---|---|---|
| Plumb Figure (exercise viewer + post-set replay) | Where should each joint be? | Own Canvas projection (rotY/rotX, perspective divide, depth-sorted bones, depth-shaded stroke width) over existing FK joint data; ghost = target pose at 35% alpha | Static 3/4 pose, drag only, no idle spin | Flat 2D front view |
| Muscle map (front/back, tilt-rotate) | What did I train, what is fresh/overworked? | Layered Canvas paths per muscle group, graphicsLayer rotationY flip between front/back with cameraDistance; heat from weekly volume | No flip animation | Static |
| Barbell + plate stack | What do I load on the bar? | Canvas isometric plates sized by real diameters/colours (IWF-style), tilt on drag | Static | Numeric text only |
| Depth rep/form gauge (HUD) | How deep/controlled was this rep? | Ring + inner "depth well" whose fill and parallax track the ROM% of the current rep | Plain ring | Number |
| Streak "chain" | Am I consistent? | Links drawn in perspective receding to the past; broken link = missed day | Flat row | Text |
| PR Medal | Did I beat my best? | graphicsLayer rotationY coin spin once, on a real PR only | Fade | Fade |

Not building: spinning logos, decorative 3D backgrounds, parallax for its own sake.

## 4. Colour tokens (dark only)

| Token | Hex | Use |
|---|---|---|
| `Ink` | #0A0B0C | App background |
| `Slate` | #14171A | Surface 1 (cards) |
| `Slate2` | #1D2125 | Surface 2 (raised/inputs) |
| `Hairline` | #2C3237 | Bones, dividers |
| `Chalk` | #F3EEE4 | Primary text, skeleton lines (warm white) |
| `Dust` | #9AA2A8 | Secondary text |
| `Haldi` | #FFB81C | Signature accent: effort, primary buttons, active tab, rep ring |
| `Sahi` | #3FE0A0 | Correct form ONLY (good rep, green plumb) |
| `Sindoor` | #FF4A3A | Fault / destructive / missed |
| `Neel` | #5AA9FF | Info/ghost target pose/sync |
| `Mehendi` | #B8D94A | PR/celebration highlight (rare) |

Rules: Haldi never means "good form"; Sahi is reserved so the lifter learns green = correct. Contrast checked: Chalk/Ink ≈ 17:1; Dust/Ink ≈ 7:1; Haldi/Ink ≈ 11:1. Ink text on Haldi buttons. Semantic glow (blurred stroke) only on active elements, off in SAVER.

## 5. Type scale (system fonts only, no downloads)

Display/numbers: `FontFamily.SansSerif` condensed via `sans-serif-condensed` family, bold, tabular figures. Body: default sans. Devanagari falls back to system Noto.

| Style | Size/Line | Weight |
|---|---|---|
| Hero number | 72/72 | Black, condensed |
| Display | 40/44 | Bold, condensed |
| Title | 22/28 | SemiBold |
| Body | 16/24 | Regular |
| Label | 13/16 | Medium, +0.8 sp caps |
| Micro | 11/14 | Medium |

## 6. Motion principles

1. **Cause→effect:** motion starts at the thing that changed (rep ring pulses on the rep, not the screen).
2. **Snap to plumb:** spring `dampingRatio 0.55, stiffness 380` for arrivals; `0.8/200` for layout.
3. **Durations:** micro 90–140 ms, state 200–280 ms, screen 320–420 ms; exits 70% of entries. Easing `FastOutSlowIn`/`EaseOutBack` for lands.
4. **Shared element:** exercise card figure → viewer; PR card → medal; ring on Home → HUD ring.
5. **Choreography:** staggered 40 ms per list item, max 6 items.
6. **Celebrations:** set-complete joint-ping (≤400 ms), PR coin spin + particle bones (≤900 ms, once), streak link snap.
7. **Loading/sync:** a bone "draws" joint to joint (indeterminate), offline = dashed bone + Neel dot, synced = solid + ping.
8. **Performance budget:** animate only `graphicsLayer`/Canvas draw state (no recomposition per frame); cap particles at 24; one infinite transition per screen; AGSL (API 33+) only for the HUD glow with a gradient fallback.
9. **Power/accessibility gating (single `MotionSpec` object):** NORMAL = full; SAVER = no idle/infinite loops, no 3D auto-motion, shorter springs (tween 150 ms), no glow; CRITICAL = no animation, flat 2D; Android "remove animations" (`ANIMATOR_DURATION_SCALE == 0`) = treated as CRITICAL motion-wise but keeps NORMAL visuals.

## 7. Screen list (build order)

1. **Home** — plumb-line streak chain, today's plan card (chamfered), recent PRs with medal, rep-tempo "last session" ring, sync pill.
2. **Workout log** — set grid with previous-values ghost, rest-timer bar, plate calculator with 3D bar.
3. **Form coach (live camera)** — existing camera/lift flow wrapped in HUD: rep ring, depth well gauge, form score, fault cue chips, ghost-pose mini Plumb Figure.
4. **Exercise library/search + viewer** — search, muscle filter chips; viewer with orbitable Plumb Figure, target ghost, muscle tags.
5. **Plan** — vertical path of days (borrowing the "road" idea), today node pulsing.
6. **Nutrition** — day macro rings, food log list/add, search.
7. **Progress** — weight chart with drawn-on line, body muscle map heat, PR list.
8. **Profile / settings** — profile fields, units, reminders, battery/motion info (no theme toggle).
9. **Sync / offline states** — pill + sheet: queued count, last sync, retry; empty/error/offline skeletons for every screen.

Navigation: bottom bar Home · Train · Library · Progress · You (Plan inside Home/Train, Nutrition inside Progress or Home card) — to confirm vs. data shape in implementation.

## 8. Code-grounding notes (from source survey)
- No nav library today (state-driven in `PoseCameraApp.kt`); plan: simple sealed-state nav + `AnimatedContent`/`Transition`, no navigation-compose dependency.
- Android has no PR entity: PRs are derived from `local_sets` (max `loadKg`×reps per exercise) in a read-only query/helper — no schema change.
- Data for screens exists: `local_food_logs`, `local_weights`, `local_profile`, `local_user_plan`/`local_plan_completions`, `catalog_*` (exercises, muscles, foods, plans). Sync status: `SyncRuntime.status`. Power: `rememberPowerUi()` (`.policy`, `.animationScale`).
- Plumb Figure reuses `Fk`/`demoSolve`/`poseAt` read-only (x/y/z FloatArray[17]); `demo\` is not edited.
- `Theme.kt` is already dark-only; remove any remaining light/system-theme branches during Phase B.

## 9. Open items
- Muscle-map paths and plate art are hand-authored vector data (no external asset).
- Real-phone 60 fps and battery impact unverified until Phase C (emulator only).
