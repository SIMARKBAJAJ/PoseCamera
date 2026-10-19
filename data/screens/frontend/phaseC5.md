# Phase C5 emulator verification (c5_ screenshots)

Setup: reused the coordinator's emulator-5554 (left running). Emu APK built with -PsahirepBaseUrl=http://10.0.2.2:5000/ (BuildConfig checked = 10.0.2.2 before `adb install -r`). waitress on a COPY of backend/instance/sahirep.db (random token_urlsafe(32) JWT secret). The 4 templates in backend/plan_templates/*.json were imported into the COPY (0 errors each). User `verifyc5`, UI login (one char per adb call), `--ez seed true`. No source edited; original sahirep.db untouched (mtime still Oct 16).

Method notes
- The DB copy was behind the schema (users.email missing, register gave 500). Ran `flask db upgrade` on the COPY only (0008 -> 0009), then restarted waitress. A first waitress instance was still listening; killed both PIDs, restarted one.
- `MSYS_NO_PATHCONV=1 cmd /c ".\\gradlew.bat ..."` works; `cmd //c` only prints the banner and BASE_URL stays 192.168.1.16.
- Two of my own tap misses (tapping Library tab and search in the same batch while still on Train opened the camera screen) were recovered with CLOSE. Not app defects.
- Cleanup: backend killed (PID 2512), db copy dir deleted, plain assembleDebug rebuilt, BuildConfig BASE_URL = http://192.168.1.16:5000/, `dumpsys battery reset` done, emulator left running. NOTE: the APK currently installed on the emulator is still the 10.0.2.2 build; only the build output dir holds the plain one.
- Logcat `FATAL EXCEPTION` count = 0.

## Verdicts
| Screen | Files | Verdict | Defects / notes |
|---|---|---|---|
| Home top | c5_01 | CLEAN | Compact hero, chamfered corner has no poking lines (header rule ends inside with a dot), streak card starts on first screen. Pill reads "Not synced" right after login (transient, You tab later says Synced). |
| Home scrolled | c5_23 | CLEAN | Plan card, Recent PRs, Last session fine. |
| You | c5_02 | CLEAN | Profile steppers, "Pehle sex chuno". |
| Library list | c5_03 | CLEANISH | Thumbnails ~72 dp, outline lighter, yellow highlights readable. Still dark-on-dark body. |
| Library search 'deadlift' | c5_05 | CLUTTERED/WRONG for non-demo | Deadlift with Bands, Deadlift with Chains, Axle Deadlift (primary "back") show NO highlight at all; Barbell Deadlift (3D) shows traps/lats/glutes/hams. Same for Pullups-type "back" rows only when demo. Chest rows do highlight for non-demo (Pushups (Close and Wide), Plyo Kettlebell Pushups, c5_11), so the gap is the generic "back" muscle mapping. Same as C4. |
| Library pullups/pushups rows | c5_11, c5_20 | CLEAN | Highlights visible, 3D tag blue. |
| Viewer Barbell Deadlift default (45) | c5_06 | CLEANISH | All 4 chips of row 1 (FRONT, SIDE, 45 DEG, PLUMB PLANE) and the new TARGET row visible without scroll. TARGET row: "COMPARE: SIDE BY SIDE" and "OVERLAY: DASHED" (wraps to 2 lines, touches the right margin). Floor grid pokes below the hero box bottom border (faint diamond ~60 px below the box). |
| Viewer COMPARE (45) | c5_07 | CLEAN | NOW (white) beside TARGET (blue, upright, stiff) both labelled, same grid. Grid fully inside the box here. |
| Viewer OVERLAY dashed (45) | c5_08 | CLEANISH | Dashed blue target visible over the live figure, but grid again pokes below the box bottom. |
| Viewer OVERLAY dashed (SIDE) | c5_09 | CLEANISH | At the standing phase the live figure and the dashed target nearly coincide, so the dashes are hard to see; grid bottom edge 13 px below box. Reads fine as a plumb read. |
| Viewer COMPARE (SIDE) | c5_10 | CLEAN | NOW / TARGET side by side in side view. |
| Viewer Pushups (45) | c5_12 | CLEANISH | Prone plank reads (arms down, chest highlighted in muscles card). Not cluttered. DEFECT: floor grid lines run past the LEFT and RIGHT edges of the hero box (to x=0) and below the bottom. "Log this" bar present. Feet still look slightly above the grid. |
| Viewer Pullups (45) | c5_21 | CLUTTERED | Unchanged vs C4: figure stands with arms forward, feet above floor, bar is a thin open rectangle beside the body not above the hands. Grid pokes below box. |
| Pushups 'Log this' -> camera | c5_13, c5_14, c5_15, c5_16 | CLEAN | Log this opens the Train camera flow (permission prompt first), "Before you start" dialog, then camera screen with PUSH-UP chip selected (highlighted). CLOSE returns to Train list. Emulator camera is a synthetic colour scene so no pose was detected (expected, 0.0 reps). |
| Train landing | c5_04, c5_19 | CLEAN | LIFTS list now starts with "Squat (camera)" and "Push-up (camera)". |
| Train 'Push-up (camera)' | c5_17 | CLEAN | Opens camera with Push-up selected. CLOSE returned (c5_19 captured, not opened). |
| Train 'Squat (camera)' | c5_18 | CLEAN | Opens camera with Squat selected. |
| Nutrition day list | c5_25, c5_26 | CLEAN | Meal sections, kcal totals, + Add food. |
| Add-food sheet COMMON | c5_27 | CLEAN | Curated list (Poha, Roti / chapati, Atta, Paratha, Cooked rice...), Dinner default by clock. |
| Roti detail | c5_28 | CLEAN (fixed) | Detail title is now "Roti / chapati" (was the 3-line USDA name). 1 piece (43 g) chip, 299 kcal P8 C46 F9. |
| Roti saved row | c5_29 | WRONG (partly fixed) | Row is named "Bread chapati or roti whole wheat commercially prepared frozen" (2 lines). The raw "Usda sr 174075" prefix is gone (improved), but it is still the long USDA name, NOT "Roti" or "Roti / chapati", and inconsistent with the detail title. Kcal/grams correct (100 g - 299 kcal, DINNER 299 kcal). |
| Power CRITICAL viewer | c5_31, c5_32 | CLEAN (fixed) | Static front figure, caption "Battery bachao mode: 3D off", NO chip rows, NO scrubber or Play, no dead controls. Muscles card and Log this still there. Compact banner "Battery 10%: reduce motion? Reduce Keep X". The system "10% battery left" heads-up covered the title in c5_31 only. |
| Power CRITICAL You | c5_33, c5_34 | CLEAN | "Tier: CRITICAL", "(now: no animations)". "Camera quality: full" still shown at CRITICAL (unexplained, camera not tested). |
| Power SAVER You | c5_35 | CLEAN | "Tier: SAVER", "(now: reduced)". |
| Power SAVER viewer | c5_38 | CLEANISH | 3D + orbit kept, Play button gone, scrubber frozen at 0, all chips live. Banner pushes content 95 px down. Grid again pokes below box. |

## Before / after vs phaseC4.md
- Viewer chips: C4 6 chips in two rows. C5 4 chips plus a labelled TARGET row (COMPARE / OVERLAY), all visible. GHOST chip gone, so the "ghost toggle does nothing while COMPARE" defect is gone. Net: improved.
- Target: C4 compare only. C5 compare (NOW/TARGET) and overlay (dashed) both work; overlay is hard to read at the standing phase in SIDE.
- Power CRITICAL viewer: C4 WRONG (dead chips). C5 CLEAN with explicit "3D off" caption and no chips. Fixed.
- Nutrition saved row: C4 "Usda sr 174075 bread chapati..." (3 lines, slug). C5 "Bread chapati or roti whole wheat commercially prepared frozen" (2 lines). Detail title fixed to "Roti / chapati". Row name still wrong.
- Train landing: C4 had no Push-up entry. C5 Squat (camera) and Push-up (camera) present and working. Push-up logging reachable from both the Train list and Pushups viewer "Log this" (which C4 reported missing). Fixed.
- Library non-demo thumbnails: unchanged (back-primary rows still unhighlighted).
- Pullups viewer: unchanged.
- Hero box clip: top-right chamfer corner is clean (nothing pokes through). But the floor grid still draws OUTSIDE the box: below the bottom edge in most states and past the left/right edges in Pushups (c5_12). Not fixed, arguably new visible now that the box is shorter.

## Jank (swiftshader software GL, NOT representative of phones; gfxinfo reset before each)
| Screen | frames | janky | p50 | p90 | p95 | p99 | C4 |
|---|---|---|---|---|---|---|---|
| Viewer Barbell Deadlift, playing, 10 x 400 ms orbit swipes | 241 | 85 (35.27%) | 44 ms | 48 ms | 53 ms | 65 ms | 46.69%, p90 65, p95 69, p99 85 |
| Home (1 hero drag each way + 3 scroll cycles) | 268 | 7 (2.61%) | 29 ms | 38 ms | 40 ms | 48 ms | 3.01%, p95 42, p99 57 |
| Library scroll (4 up + 4 down) | 163 | 11 (6.75%) | 26 ms | 36 ms | 40 ms | 42 ms | 11.64%, p95 46, p99 53 |
Viewer improved clearly (35.3% vs 46.7%, p95 53 vs 69 ms), Home and Library also improved. Frame counts differ (sampling length), so trend only; swiftshader noise is large. Viewer was in the default state (no compare/overlay), unlike a worst case.

## Worst 8 remaining defects
1. Saved Roti row keeps the long USDA name ("Bread chapati or roti whole wheat commercially prepared frozen"), not the curated short name; inconsistent with the detail title.
2. Floor grid is drawn outside the hero box (below bottom edge in all viewers; past left/right edges in Pushups). Clip is not applied to the grid.
3. Library non-demo rows with primary "back" (Deadlift with Bands/Chains, Axle Deadlift) have no muscle highlight.
4. Pullups viewer still reads as standing, feet above floor, bar is an open rectangle beside the body.
5. OVERLAY: DASHED chip wraps to 2 lines and sits flush to the right margin; in SIDE the dashed overlay is nearly invisible at the standing phase.
6. Pushups viewer figure: feet above floor, lumpy silhouette (minor).
7. Home pill shows "Not synced" right after login until first sync (transient); "Camera quality: full" at CRITICAL tier unexplained.
8. SAVER banner pushes content ~95 px down; scrubber frozen at 0 while the pose shown is mid-bend (c5_38 pose is the lift start; consistent, but confusing with no Play).

## Unverified
- Emulator camera is a synthetic scene: no pose detection, no rep counting, no real form feedback; only screen entry and exercise selection verified.
- Jank numbers are swiftshader software GL, not a phone. Compare/overlay-on jank not measured (viewer measured in default state only).
- Not exercised: workout log screens (barbell plates card, pull-up default weight), Progress weekly volume, Plan tab (plan picker/timeline), FRONT angle, Demos screen; Home hero at CRITICAL/SAVER; the "Reduce/Keep" banner buttons. Progress top (c5_24) opened once, looked fine.
- Wrong-tab taps by me happened twice; no app defect implied.
- Left the 10.0.2.2 emu APK installed on the emulator (plain build only rebuilt, not installed).

## Screenshots actually opened (all c5_ prefix, in data/screens/frontend)
00_start, 01_home, 02_you, 03_library, 04_train, 05_lib_deadlift, 06_viewer_dl_default, 07_dl_compare45, 08_dl_overlay45, 09_dl_overlay_side, 10_dl_compare_side, 11_lib_pushups, 12_pushups_viewer, 13_pushup_logthis, 14_pushup_cam_from_log, 15_cam_pushup_clean, 16_after_close, 17_train_pushup_entry, 18_train_squat_entry, 20_lib_pullups, 21_pullups_viewer, 23_home_scrolled, 24_progress, 25_nutrition, 26_nutrition_bottom, 27_addfood, 28_roti_detail, 29_after_save, 31_viewer_critical, 32_viewer_critical_clean, 33_you_critical, 34_you_power_critical, 35_you_power_saver, 36_lib_saver, 37_lib_search_saver, 38_viewer_saver.
Captured but NOT opened: c5_19_train_back, c5_22_lib_after_scroll, c5_30_libcheck.
