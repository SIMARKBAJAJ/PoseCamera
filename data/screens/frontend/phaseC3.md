# Phase C3 emulator verification (c3_ screenshots)

Setup: emulator SahiRep_API_36 (1080x2340, swiftshader, headless; none running beforehand, I started and killed it), emu APK (-PsahirepBaseUrl=http://10.0.2.2:5000/), waitress backend on a COPY of backend/instance/sahirep.db (random JWT secret), 3 synthetic plans from the previous scratchpad plans.json imported into the COPY only, user `verifyc3`, UI login, `--ez seed true`. Cleanup: backend killed (PID 29344), db copy and emu apk deleted, plain assembleDebug restored (BuildConfig BASE_URL = http://192.168.1.16:5000/). `dumpsys battery reset` done. No source edited. Note: `emulator.exe` PID 28988 was still in tasklist after my kill; it is not the one I started (mine was 24640/qemu 17068) so I left it.

Method notes
- Gradle only picked up -PsahirepBaseUrl when run with `export JAVA_HOME=...; cmd /c ".\\gradlew.bat --offline assembleDebug -P... "`; my first run (JAVA_HOME as inline env prefix) printed only the cmd banner and left BASE_URL at 192.168.1.16, so the first install showed "Can't reach SahiRep". Check BuildConfig after building the emu APK.
- `adb input text` with a whole string dropped chars in the Login username (typed "verifyc3", got "v"). One char per call (0.25 s apart) worked. Library search typed one char at a time (0.6-1.2 s) kept all chars: "d", "de", "dead", "deadlift" all correct.
- Power test needs `dumpsys battery unplug`, `set level 20` AND `set status 3`. After unplug alone, status stayed 2 (charging) and the app (correctly) showed no banner and Tier NORMAL.

## Verdicts
| Screen | Files | Verdict | Defects |
|---|---|---|---|
| Home top | c3_01 | CLEAN | Hero is a compact card (about 45% of the first screen), streak card starts on first screen. Headline no longer overprints the label; "FRONT - drag to turn" sits on its own line. Figure is a flat front poly mannequin with muscle highlights. |
| Home hero drag (400 ms) | c3_02 (mid), c3_03 (after) | CLEAN | Mid-drag the figure is rotating (foreshortened side view), after drag it reads "BACK - drag to turn" with the back muscle map. Works. The streak ring pulse is mid-animation in c3_02 (cosmetic). c3_03b is an extra capture, not opened. |
| Library list | c3_04 | CLEAN | Rows now show a dark mannequin thumbnail with the target muscle highlighted in yellow, with "3D" tag on demo lifts. Thumbnails are tiny (about 70 dp) and the mannequin is very low contrast; highlight is a small yellow bar for ab/hamstring rows. Chip row is cut at "TRAP" (scrollable). |
| Library search "dead" | c3_05 (not opened, "de"), c3_06, c3_07 | CLEAN | No dropped chars, X clear button appears, results filter live (Dead Bug, Deadlift with Bands, ...). Non-3D rows have a blank mannequin with no highlight (Deadlift with Bands/Chains/Axle Deadlift). |
| Viewer deadlift | c3_08, c3_09 (compare), c3_10 (SIDE+ghost), c3_32 | CLEANISH | All 6 chips present: FRONT, SIDE, 45 DEG, TARGET GHOST, COMPARE, PLUMB PLANE (two rows). Second row is cut off at its bottom edge (about 10 px of the pills clipped) on deadlift and pullups. Compare shows a second dashed figure; in SIDE it is a detached thin dashed vertical line far right of the live figure (reads as a stray line, not a comparison). Ghost on + SIDE is the same dashed blue line. Body silhouette behind the skeleton makes the pose readable. |
| Viewer Pullups | c3_11 | CLUTTERED | Bar is still an open rectangle frame, hands touch it but feet hang near the floor grid (better than C2, still reads as standing). Same clipped chip row. |
| Viewer Pushups | c3_12 | CLEAN | Now has a 3D demo (was none). Body prone, arms down, plank. Chip row not clipped here. Floor plane reads fine. |
| Viewer Bodyweight Squat | c3_13 | CLEAN | Now has a 3D demo, arms forward, thick silhouette. Chip row not clipped. |
| Workout log set card | c3_14 (c3_15 not opened) | CLEANISH | Fixed: kg/reps/RPE rows share one column grid (-, value, +); no more three offsets. Remaining: check button at top-right is a green blob with a tilted/clipped tick; bar-weight "PLATES / 20 bar" card shows for a Pull-up (bodyweight) with a 20 kg default set; header mini 3D figure is a tiny clutter of dots (about 90 dp) overlapping the title area; "Pehle: -" label is small and sits under the first minus button. |
| Progress top | c3_16 | CLEAN | Rings and labels clear, 4-digit kcal fits, "12 sets logged" present. Carbs end-knob touches the "175" digits' left edge. |
| Progress scroll | c3_17 (not opened); c3_18-style crop was the weight/PR screen opened as c3_18 first take | CLEAN | PR list with PR badge then 2/3/4, long exercise name wraps (acceptable). Weekly volume: only two bars with no per-week labels or axis. |
| Nutrition | c3_18, c3_19 (not opened) | CLEAN | Meal sections with kcal totals, red X delete targets. |
| Add-food sheet | c3_20 | CLUTTERED | Tab bar hidden, COMMON list now shows first. But COMMON is wrong data: "Groundcherries (cape-gooseberries or poha), raw" for Poha, "Turkey, white, rotisserie, deli cut" for Roti, "Soybean, curd cheese" for Paneer, "Egg, white, dried" for Egg, "Rice flakes" for rice. Fuzzy name matching into USDA; the real Poha/Dal tadka/Roti entries shown in the log above are not what the sheet offers. Meal default is still Snack. |
| You (normal battery) | c3_21, c3_22 | CLEAN | Profile at top with big steppers; Sync card; Power card reads "Tier: NORMAL / Camera quality: full / Reduce motion follows Android's Remove animations setting (now: full). No switch here." |
| You at 20% unplugged | c3_23, c3_24 (not-unplugged-yet, status 2, no banner; superseded), c3_25 | CLEAN | Banner is now a compact single-line strip ("Battery 20%: reduce motion?  Reduce  Keep  X"), the page content is pushed below it instead of being covered; while scrolling the strip floats over the top of content (acceptable). Tier text now "Tier: SAVER", "(now: reduced)". Consistent. |
| Banner dismiss | c3_26 | CLEAN | Tapping X removes the strip (also confirmed with uiautomator: Battery/Reduce/Keep text gone). Battery icon stays red. |
| Train top | c3_27 | CLEAN (not opened) | Form coach + plan + lift list present (uiautomator text only; screenshot not opened). |
| Camera HUD | c3_28 (not opened, disclaimer), c3_29 | CLEAN | Tab bar hidden, dark pills legible on the bright scene, rep ring + DEPTH ring labelled, End set pill. "Roma" still clipped at the right edge at rest but the row is scrollable. |
| Camera exercise select | c3_30 | CLEAN | Tapping the clipped "Romanian deadlift" scrolled the row so it is fully visible and selected, setup cue banner appears. Fixed. |
| Demos | c3_31 | CLUTTERED | Picker is now grouped (Legs / Push / Pull / Core) with large chips, easier. First screen still ends with the control row (Pause/Front/Side/45) cut in half at the bottom edge. Figure is thin white/green lines. Chips at right edge cut ("Bar", "Bench press") but scrollable. |

## Before / after
- Home hero: before (C2) - full-screen-height card, headline overprinted "FRONT - drag", horizontal drag did nothing. After - compact card, label on its own line, drag rotates the figure (mid-drag foreshortened) and lands on BACK. Fixed.
- Library: before - tiny white-dot 3D thumbnails, 28.1% janky. After - dark muscle-highlight mannequin thumbnails (very small, low contrast), 17.39% janky, p95 57 ms. Better (still above C1's 8.4%).
- Viewer chips: before - FRONT/SIDE/45/GHOST only, no Compare. After - 6 chips in two rows (adds COMPARE and PLUMB PLANE), but row 2 clipped at the bottom on deadlift/pullups. Bodyweight demos now exist for Pushups and Bodyweight Squat (were orphaned).
- Power banner / tier text: before - big banner overlaid You content, no dismiss, "Tier: NORMAL" stale at 20%/10%. After - compact strip with Reduce/Keep/X, content pushed down, X dismisses, tier text reads "Tier: SAVER ... now: reduced". Fixed (10% tier not retested).

## Jank (swiftshader software GL, NOT representative of phones; gfxinfo reset before each)
| Screen | frames | janky | p50 | p90 | p95 | p99 | previous (C2) |
|---|---|---|---|---|---|---|---|
| Viewer orbit + play (10 x 400 ms swipes) | 245 | 91 (37.14%) | 48 ms | 53 ms | 61 ms | 69 ms | 42.48%, p90 65, p95 69, p99 73 |
| Home (1 hero drag each way + 3 scroll cycles) | 248 | 23 (9.27%) | 34 ms | 48 ms | 57 ms | 93 ms | 13.11%, p90 48, p95 57, p99 61 |
| Library scroll (8 swipes) | 184 | 32 (17.39%) | 34 ms | 48 ms | 57 ms | 69 ms | 28.11%, p90 61, p95 61, p99 69 |
All three improved versus C2, but still above the pre-C2 numbers (viewer 36.89%, home 4.78%, library 8.44%). Home p99 got worse (93 vs 61 ms: one or two long frames). Run-to-run noise on swiftshader is large; treat as a trend only.

Crashes: `FATAL EXCEPTION` x2 in logcat, both `SystemUIBg-3` (com.android.systemui, "Unknown authority com.google.android.flipendo.api", emulator system noise during battery set). Zero in com.example.posecamera.

## Worst 8 remaining defects
1. Add-food COMMON list is wrong data: Poha -> groundcherries, Roti -> turkey rotisserie, Paneer -> soybean curd cheese, Egg -> dried egg white. Meal default still Snack.
2. Viewer chip row 2 (COMPARE / PLUMB PLANE) is clipped at its bottom edge on deadlift and pullups (not on pushups/squat).
3. Viewer COMPARE in SIDE draws a detached thin dashed vertical line far from the live figure; reads as an artifact, not a comparison.
4. Pullups viewer: bar is an open rectangle and feet hang right above the floor grid; reads as standing/reaching.
5. Workout log: a bodyweight lift (Pull-up) shows the bar/plates card with "20 bar" and a 20 kg default; check button tick looks clipped/tilted; tiny header figure.
6. Demos screen: control row (Pause/Front/Side/45) is still cut in half at the first-screen bottom edge.
7. Library thumbnails: muscle highlight is a tiny yellow bar on a near-black mannequin; non-3D rows (Deadlift with Bands/Chains, Axle Deadlift) have no highlight at all.
8. Jank still above pre-C2 baselines on swiftshader (viewer 37%, library 17%, home 9%; home p99 93 ms). Weekly volume chart has no week labels, only two bars.

## Screenshots actually opened
c3_00_after_login (login screen, wrong BASE_URL attempt), c3_01, c3_02, c3_03, c3_04, c3_06, c3_07, c3_08, c3_09, c3_10, c3_11, c3_12, c3_13, c3_14, c3_16, c3_18 (nutrition, second take; first take was a mis-tap on the weight chart), c3_20, c3_21, c3_22, c3_23 (twice: first pre-status-3, no banner; then re-captured with banner), c3_24, c3_25, c3_26, c3_29, c3_30, c3_31, c3_32.
Captured but NOT opened: c3_00b_login_state, c3_03b_home_afterdrag2, c3_05_lib_de, c3_15_workout_log2, c3_17_progress_scroll, c3_19_nutrition_bottom, c3_27_train_top, c3_28_formcoach_1. Note c3_18 and c3_23 were overwritten by their retakes; c3_17 and c3_18's first take (progress scrolled) were viewed only via the earlier overwritten c3_18.
