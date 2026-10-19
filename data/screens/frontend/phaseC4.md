# Phase C4 emulator verification (c4_ screenshots)

Setup: emulator SahiRep_API_36 (1080x2340, swiftshader, headless; `adb devices` empty beforehand, I started and killed it), emu APK (-PsahirepBaseUrl=http://10.0.2.2:5000/, BuildConfig checked = 10.0.2.2 before install), waitress on a COPY of backend/instance/sahirep.db (random 40-char JWT secret), the 4 real templates in backend/plan_templates/*.json imported into the COPY (0 errors each), user `verifyc4`, UI login (one char per adb call), `--ez seed true`, Sync now. No source edited; the real sahirep.db was never touched. Cleanup: emulator killed, waitress killed (PIDs 34288/29700), db copy + emu apk + secret deleted, plain assembleDebug restored (BuildConfig BASE_URL = http://192.168.1.16:5000/), `dumpsys battery reset` done.

Method notes
- `export JAVA_HOME=...; MSYS_NO_PATHCONV=1 cmd /c ".\\gradlew.bat --offline assembleDebug -P..."` was needed; without MSYS_NO_PATHCONV the first run only printed the cmd banner and BASE_URL stayed 192.168.1.16.
- `adb.exe` is a Windows binary: scratch paths must be `C:/...`, not `/c/...`.
- Searching with `keyevent 4` when no keyboard is open exits the app to the launcher (happened twice during the power run); relaunched without `--ez seed`.
- `dumpsys battery unplug` + `set status 3` + `set level 10` worked at once. An Android System heads-up ("10% battery left, Turn on Extreme Battery Saver") covered the first home capture (c4_37); retaken as c4_39.
- Emulator clock reads 23:5x, so "default meal by time of day" = Dinner is correct.

## Verdicts
| Screen | Files | Verdict | Defects / notes |
|---|---|---|---|
| Home top | c4_01, c4_06 | CLEAN | Compact hero, label on its own line, streak card starts on first screen. |
| Home pill vs You sync label | c4_01 (Syncing), c4_06 (Synced), c4_04/05 (You: Synced / Last result OK / Queued 0) | CLEAN | Pill read "Syncing" ~5 s after login (transient), then "Synced" matching You tab. Consistent. |
| Library list | c4_10, c4_11 | CLEANISH | Thumbnails now about 72 dp with a lighter mannequin outline and a clearly visible yellow highlight (abs bar, hamstrings, adductors). Contrast is better than C3 but still dark-on-dark. Non-demo rows (Deadlift with Bands / Chains, Axle Deadlift) still show NO highlight at all while Barbell Deadlift (3D) shows back + glutes. "3D" tag in blue. |
| Viewer Barbell Deadlift (all 6 chips, default 45 DEG) | c4_12 | CLEAN | All 6 chips visible without scrolling, second row (COMPARE, PLUMB PLANE) fully visible with full rounded bottoms. Fixed vs C3. Play button, scrubber, plumb plane present. |
| Viewer COMPARE (45) | c4_13 | CLEAN | Two figures: live white one labelled NOW, upright blue one labelled TARGET, both on the floor grid, same scale. Reads as a comparison now. |
| Viewer COMPARE + TARGET GHOST (45 / SIDE / FRONT) | c4_14, c4_15, c4_16 | CLEAN | Side-by-side NOW / TARGET in all three angles; the detached dashed line from C3 is gone. Minor: toggling TARGET GHOST while COMPARE is on makes no visible difference (the blue TARGET figure is the same as the ghost). |
| Viewer SIDE | c4_15, c4_42 | CLEAN | Good hip-hinge read with the plumb plane. |
| Viewer Pullups (45, SIDE) | c4_18, c4_19 | CLUTTERED | Still reads as standing and reaching, not hanging: feet just above the floor grid, bar is a thin unclosed rectangle outline that sits beside the body instead of above the hands (SIDE shows it as a vertical frame to the right). Chips fine. c4_20 (FRONT) captured, not opened. |
| Viewer Pushups (45) | c4_21 | CLEANISH | Prone plank reads, arms down as vertical lines, body mass shaded, chest highlighted. Feet float slightly above the floor grid and the silhouette is a lumpy blob. Not cluttered. |
| Viewer Bodyweight Squat (45) | c4_22 | CLEAN | Clear parallel squat, arms forward, quads highlighted. |
| Workout log, barbell lift (Deadlift) | c4_23, c4_24 | CLEAN | PLATES card (20 / 4x25 / 2x2.5, red plates both sides), kg/reps/RPE on one grid. After tapping check: the tick is a clean black check on a green circle, row greys out, rest bar "Rest 1:28" appears. Header mini figure is gone. Fixed. |
| Workout log, Pull-up | c4_25 | CLEANISH | No plates card (fixed). Still defaults to 20 kg on a bodyweight lift. |
| Workout log, Lateral raise | c4_27 | CLEAN | No plates card. 20 kg default (dumbbell lift, arguably wrong default). |
| Workout log, Push-up | c4_26 (is the Pushups VIEWER) | UNVERIFIED | There is no Push-up lift in the Train list (Pull-up, Lat pulldown, Lateral raise, Leg press... exist) and the Pushups viewer has no "Log this" bar, so push-up logging is not reachable from the logger. c4_26 shows the viewer, not a log. |
| Progress top | c4_28 | CLEAN | Rings and labels clear, 4-digit kcal fits, "13 sets logged" (12 seed + the 1 I ticked in the COPY). Streak card has an empty right half (cosmetic). |
| Progress weekly volume | c4_29, c4_30 | CLEAN | Labels W-5, W-4, W-3, W-2, W-1, This and caption "kg x reps, last 6 weeks (right = this week)". Fixed. Only 2 of 6 bars have data (seed). PR list with PR badge then 2/3/4 fine. |
| Nutrition | c4_31 (is actually Progress top; tap missed), c4_32 | CLEAN | Meal sections with kcal totals, red X targets, Add food button. (c4_31 is a retake error; the Nutrition screen is c4_32.) |
| Add-food sheet | c4_33, c4_34 | CLEAN | COMMON list is now curated: Poha (flattened rice, raw), Roti / chapati, Atta, Paratha, Cooked rice, Rice (raw), Toor dal, Moong dal, Cooked dal... with kcal/100 g. Default meal = Dinner at 23:57 (correct by clock). Tab bar hidden. Picking Roti opens a detail pane titled "Bread, chapati or roti, whole wheat, commercially prepared, frozen" (USDA long name, 3 lines, 100 g = 299 kcal, 1 piece 43 g). |
| After save | c4_35, c4_36 | WRONG | Saved row is named "Usda sr 174075 bread chapati or roti whole wheat commercially prepared frozen" (raw slug, wraps to 3 lines), not "Roti". Saved correctly under DINNER 299 kcal; the seeded rows are named "Roti", "Poha", "Dal tadka". |
| Plan tab | c4_08, c4_09 | CLEAN | Pick list shows all 4 real plans: Beginner Full Body 3x (gym), Home Bodyweight 3x (no equipment), Upper / Lower 4x (gym), Push / Pull / Legs (gym), each with level - days/week - split. Choosing Upper/Lower shows ACTIVE and the DAY 1-4 timeline (Upper A with Train/Done, Lower A, Upper B, Lower B). Plan card appears as "TODAY'S PLAN" on Train afterwards. Empty lower third of the screen (acceptable). |
| You tab | c4_03, c4_04, c4_05 | CLEAN | Profile steppers, Sync card (Synced / Sync now / Last result OK / Queued 0), Power card, Log out. "Pehle sex chuno" (yellow) shown since sex unset. |
| Power CRITICAL (10%): home | c4_37 (covered by system notification), c4_39 | CLEAN | Hero is static ("FRONT - tap to flip" instead of "drag to turn"), compact one-line banner "Battery 10%: reduce motion? Reduce Keep X" pushes content down. |
| Power CRITICAL: viewer | c4_40, c4_41 | WRONG | Figure is flat, front-only, no floor/plumb, scrubber stuck at 0, Play button gone, "DRAG TO ORBIT" gone (as intended), BUT the chips are still live: FRONT / SIDE / 45 DEG / COMPARE / GHOST / PLUMB PLANE all render and tapping SIDE highlights the chip while the figure stays front. Dead controls. (45 DEG was highlighted while a front figure was drawn.) |
| Power CRITICAL: You | c4_38 | CLEAN | "Tier: CRITICAL", "Camera quality: full", "(now: no animations)". Consistent. Minor: "Camera quality: full" at CRITICAL looks suspicious but I did not check the camera. |
| Power SAVER (20%): viewer | c4_42 | CLEANISH | Orbit + 3D kept, Play button gone, scrubber frozen at 0, banner "Battery 20%..." pushes everything 95 px down. Figure animation gated, consistent with SAVER. |
| Power SAVER: You | c4_43 | CLEAN | "Tier: SAVER", "(now: reduced)". |

## Before / after (vs phaseC3.md)
- Viewer chips: before - row 2 clipped on deadlift/pullups. After - fully visible on deadlift and pullups. Fixed.
- COMPARE: before - detached dashed line in SIDE. After - full blue TARGET figure beside the live NOW figure, labelled, in 45 / SIDE / FRONT. Fixed.
- Library thumbnails: before - tiny near-black mannequin, small bar. After - about 72 dp, visible highlight; non-demo rows still unhighlighted.
- Workout log: before - plates card on Pull-up, tilted/clipped tick, tiny header figure. After - plates card only on barbell lifts, clean tick, no header figure. Fixed. Push-up log still unreachable.
- Weekly volume: before - bars without labels. After - W-5..This labels + caption. Fixed.
- Add-food: before - COMMON list matched wrong USDA foods (Poha -> groundcherries, Roti -> turkey) and default Snack. After - correct curated foods and time-of-day default. New problem: the saved log row gets a raw USDA slug name.
- Plan: before - 3 synthetic plans. After - 4 real templates, pick + timeline work.
- Power: before - banner OK, tier text OK. After - same, plus at CRITICAL the viewer shows dead chips (new finding; C3 did not retest 10%).
- Pullups: before - open rectangle, feet near floor. After - unchanged.

## Jank (swiftshader software GL, NOT representative of phones; gfxinfo reset before each)
| Screen | frames | janky | p50 | p90 | p95 | p99 | previous (C3) |
|---|---|---|---|---|---|---|---|
| Viewer orbit + play (10 x 400 ms swipes, ~10 s) | 272 | 127 (46.69%) | 48 ms | 65 ms | 69 ms | 85 ms | 37.14%, p90 53, p95 61, p99 69 |
| Home (1 hero drag each way + 3 scroll cycles) | 564 | 17 (3.01%) | 34 ms | 40 ms | 42 ms | 57 ms | 9.27%, p90 48, p95 57, p99 93 |
| Library scroll (4 up + 4 down swipes) | 232 | 27 (11.64%) | 31 ms | 42 ms | 46 ms | 53 ms | 17.39%, p90 48, p95 57, p99 69 |
Home and library improved clearly (home also sampled more frames, 564 vs 248, so not strictly like-for-like). Viewer got WORSE (46.7% vs 37.1%, p99 85 ms) - the compare/plumb/body-silhouette additions are the likely cost; re-check on a phone. Trend only, swiftshader noise is large.

Crashes: `FATAL EXCEPTION` count in logcat = 0 this run.

## Worst 8 remaining defects
1. Saved food row name is a raw slug ("Usda sr 174075 bread chapati or roti whole wheat commercially prepared frozen", 3 lines) after picking Roti / chapati from COMMON; detail pane title is also the long USDA name.
2. Power CRITICAL: viewer shows live chips (FRONT/SIDE/45/COMPARE/GHOST/PLUMB) that do nothing; the figure stays front-on while 45 DEG is highlighted.
3. Viewer jank worsened: 46.7% janky, p95 69 ms, p99 85 ms (was 37.1%).
4. Pullups viewer still reads as standing: feet above the floor, bar is a thin open rectangle drawn beside the body.
5. Push-up logging unreachable: no Push-up lift in Train, Pushups viewer has no "Log this" (c4_26 is the viewer, not a log). Pull-up and Lateral raise default to 20 kg.
6. Library non-demo rows (Deadlift with Bands/Chains, Axle Deadlift) have no muscle highlight; thumbnails are still dark-on-dark.
7. TARGET GHOST toggle has no visible effect while COMPARE is on.
8. Cosmetic: Streak cards (Home and Progress) have an empty right half; Plan screen is about 30% empty below the list; "Camera quality: full" at CRITICAL tier is unexplained.

## Screenshots actually opened (all c4_ prefix, all in this folder)
01, 03, 04, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16, 18, 19, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32, 33, 34, 36, 37, 38 (also opened c4_31 which turned out to be Progress top), 39, 40, 41, 42, 43.
Captured but NOT opened: c4_02_home_pill, c4_05_you_synced, c4_17_lib_pullups (list with Pullups; viewer is c4_18), c4_20_pullups_front, c4_35_after_save (top of nutrition after save; c4_36 is the dinner section). I deleted two wrong captures (splash screen, launcher). The Camera HUD/Demos screens were not in the C4 brief and were not re-verified.
