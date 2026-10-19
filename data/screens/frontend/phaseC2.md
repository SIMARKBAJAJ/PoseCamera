# Phase C2 emulator verification (c2_ screenshots)

Setup: emulator SahiRep_API_36 (1080x2340, swiftshader, headless), emu APK (-PsahirepBaseUrl=http://10.0.2.2:5000/), throwaway waitress backend on a COPY of backend/instance/sahirep.db (random JWT secret), user `verifyc2`, `--ez seed true`, UI login. Emulator was not running beforehand (adb devices empty); I started and killed it. Backend killed, db copy deleted, plain assembleDebug restored (BuildConfig BASE_URL = http://192.168.1.16:5000/). No source edited.

## Plans import
`flask --app app import-plans <file>` (backend/plans.py, PLANS_SCHEMA.md) exists. The original DB has 0 plans and the repo has NO plan template files (no plans*.json anywhere). So I wrote 3 synthetic plans into a scratchpad JSON (Beginner Full Body 3x, Upper/Lower 4x, Strength Upper/Lower) and imported them into the COPY only (created 3, 0 errors). After You > Sync now, Train > Plan chuno lists all 3 and picking one shows the day timeline (c2_37, c2_38). Sync of catalog plans works. Real plan content still has to be authored.

## Method notes
- `adb input text` with a whole string drops characters in the Library search (typed "squat", got "tsqu"). One char per call works. Likely emulator IME timing, but worth a look (possible state write-back race in the search field).
- Library search + tab switch: the old bug (search persists across tabs, text appends) is fixed. Returning to Library shows an empty field (c2_25).
- Only 22 Lifts have 3D demos. The bodyweight demos `push-up` and `squat` (demoOf) never match the catalog: catalog slugs are `pushups` and `bodyweight-squat`, so "Pushups" and "Bodyweight Squat" open with NO demo. I used Pullups as the bodyweight lift.
- No "Compare" chip exists on the viewer. Chips are FRONT / SIDE / 45 DEG / TARGET GHOST. Ghost only draws in SIDE (code: ghostIn needs SIDE).

## Verdicts
| Screen | Files | Verdict | Defects |
|---|---|---|---|
| Home top | c2_00, c2_01 | CLUTTERED | Hero is now a full-screen-height card (about 85% of first screen). Headline "Glutes + shoulders garam, abs + biceps thanda" overprints the small "FRONT - drag" label (both rendered at the same spot). Figure is a flat 2D front view with no 3D feel. |
| Home hero drag | c2_02 | WRONG | Horizontal drag on the hero: nothing changed (still FRONT, same pose, pill now says Synced). Vertical drag scrolls the page fine. |
| Home scrolled | c2_03, c2_04 | CLEAN | Streak chain, Pick-a-plan CTA, PR rows, last session ring all fine. Streak card has an empty top-right half. After the hero, nothing is visible without scrolling past 1.5 screens. |
| Library list/search | c2_06, c2_07e, c2_15 (pullups), c2_20 | CLEAN | 3D badge + live mini 3D thumbnails on demo lifts. Mini 3D thumbnails are a smudge of white dots at 130 px. Non-demo rows show a dark mannequin with a tiny yellow highlight. |
| Library back-to-tab | c2_25 | CLEAN | Search cleared. |
| Viewer barbell (deadlift) | c2_10, c2_11, c2_12(not opened), c2_13, c2_14 | CLEANISH | Body silhouette behind stick figure now makes it readable. Orbit works, plumb plane, SIDE good, ghost is a thin dashed blue line hugging the back (legible but easy to miss). Floating "Log this" bar covers the bottom of the next card (EQUIPMENT "Level: intermediate" is cut) while scrolling. Feet are two flat lines (no shoes), bar is a thin line through the hands. |
| Viewer bodyweight (Pullups) | c2_16-c2_19 | CLUTTERED | Feet touch the floor while hands grip the bar, so it reads as standing and reaching, not hanging. Bar drawn as an unclosed rectangle frame. In SIDE the ghost dashes sit beside the head and arms, detached. |
| Viewer machine (Leg press) | c2_21-c2_24 | CLEAN | Best of the three. Sled rails, pad, seated body. Opens in SIDE by design. Head circle floats far left of the torso on the backrest. |
| Workout log | c2_27, c2_28 | CLUTTERED | Each set card: kg row, reps row and RPE row have three different left offsets (kg starts at x 140, reps at 85, RPE at 75), large dead area right of the kg/reps rows, check button isolated at top-right and its tick looks clipped/rotated. +/- buttons about 94 px (about 34 dp). |
| Progress top/scrolled | c2_29, c2_30 | CLEAN | Ring labels no longer overlapped. CARBS end-knob sits flush on the ring's left edge but clear of the label. PR list now shows rank 2/3/4 badges instead of empty circles, PR medal on first. 4-digit kcal fits. Weekly volume bars have no axis/labels visible in this crop. |
| Nutrition | c2_31, c2_32 | CLEAN | Red X targets, empty DINNER state "Kuch nahi". |
| Add-food sheet | c2_33 | CLUTTERED | Tab bar IS hidden now. List is still raw USDA alphabetical (Abiyuch, Acerola, Agutuk fish with seal oil (Alaskan Native)); Poha, Dal, Roti are not first. Meal default is Snack. |
| Plan (with plans) | c2_37, c2_38 | CLEAN | Pick list with 3 plans, ACTIVE tag, Day 1/Day 2 timeline with Train/Done. About 30% empty below the list (acceptable). |
| You | c2_34, c2_35, c2_36 | CLEAN | Sync OK. Power card text is now an explanatory note. |
| Camera HUD | c2_39, c2_40 | CLUTTERED | Tab bar hidden. HUD chips (Demos, Sync now, FPS, exercise tabs) are on solid dark pills, legible on the white virtual scene. Remaining: exercise row still clips "Romanian deadlift" at "Roma|" (scrollable), the second ring below the rep ring is empty/unlabeled, "End set" is a white pill with no icon. |
| Demos (leg press) | c2_41-c2_44 | CLEAN | No CLOSE pill, no tab bar. Key cues fully visible after scrolling and not covered. Defects: first screen ends with the control row (Pause/Front/Side/45) cut in half at the bottom edge (needs a scroll to reach them); picker rows are tiny-scroll chips ("Leg curl (s" clipped). Figure is thin white/green lines, no body mass. |
| Power 20% | c2_45, c2_46, c2_47 | WRONG | Needs real unplug (`dumpsys battery unplug`; set status 3 alone leaves AC powered = charging). Then a global banner "Battery is 20%... Reduce / Keep full quality" appears on home and You (also on the viewer at 10%), which is new. Viewer at 20%: Play button gone, scrubber frozen at 0 (motion gated automatically, good). But You still says "Tier: NORMAL, Full quality: nothing reduced" at both 20% and 10%, while the line under it says "now: reduced" then "now: no animations". Banner overlays (covers) the top of You content. |
| Power 10% | c2_48, c2_49, c2_50 | CLEAN/WRONG | Viewer is flat, front-only, no orbit, chips or scrubber (OFF). Good. Banner pushes the viewer content down 290 px, covering nothing. Tier line on You still NORMAL (stale). |

## Before / after (vs phaseC.md)
- Home hero: before - flat figure, large empty band under it, drag flips FRONT/BACK. After - figure fills the whole card (no empty band) but the headline overlaps the FRONT label and drag now does nothing visible. Better fill, worse interaction.
- Viewer: before - crude stick figure, ghost smeared into a blue blob in SIDE, Log-this bar over MUSCLES header. After - translucent body mass, ghost is a dashed line (no smear), Log-this bar still floats over the next card but the MUSCLES header is no longer hidden at rest. Better.
- Camera HUD: before - white text invisible on bright scene, tab bar visible, "Roman" clipped. After - dark pills, tab bar hidden, "Roma|" still clipped. Mostly fixed.
- Demos screen: before - not verified separately. After - CLOSE and tab bar hidden, cues readable. Control row clipped at first-screen bottom.
- Plan: before - empty with "download ho rahe hain". After - 3 plans shown after sync, picking works, timeline. Fixed (data only synthetic).

## Jank (swiftshader software GL, NOT representative; gfxinfo reset before each)
| Screen | frames | janky | p90 | p95 | p99 | previous |
|---|---|---|---|---|---|---|
| Viewer orbit + play (12 x 2 swipes) | 525 | 223 (42.48%) | 65 ms | 69 ms | 73 ms (from a later 913-frame cumulative sample; 40.96% janky there) | 36.89% janky, p95 61 ms |
| Home (hero drag + 3 scroll cycles) | 267 | 35 (13.11%) | 48 ms | 57 ms | 61 ms | 4.78%, p95 48 ms |
| Library scroll (8 swipes) | 185 | 52 (28.11%) | 61 ms | 61 ms | 69 ms | 8.44%, p95 48 ms |
Home and Library got worse (4.78 to 13.1% and 8.44 to 28.1%); viewer worse (36.9 to 42.5%). Home first run read 10.49% (28/267), second run 13.11%; I report the second (same method as the library one). Run-to-run noise on swiftshader is large and the library list now draws 3D mini thumbnails, so treat as a regression to re-check on a phone, not a verdict.

Crashes: one `FATAL EXCEPTION: SystemUIBg-2` (system UI, pid 949). App process: 0 FATAL.

## Worst 10 remaining defects
1. You > Power card says "Tier: NORMAL, Full quality" at 20% and 10% unplugged while motion is already gated (viewer frozen / flat). Stale or wrong tier text.
2. Library jank: 28% janky, p95 61 ms (was 8.4%). Home 13% (was 4.8%). Viewer 42.5%.
3. Home hero: horizontal drag does nothing; hero eats the whole first screen; headline overprints the "FRONT - drag" label.
4. Bodyweight demos orphaned: `push-up` / `squat` demo keys do not match catalog slugs `pushups` / `bodyweight-squat`, so Pushups and Bodyweight Squat have no 3D viewer.
5. Library search drops typed characters when text arrives quickly (could be real IME/state race).
6. Add-food sheet is raw USDA alphabetical; Indian staples are not first.
7. Power banner appears on every screen, overlays You content and shifts viewer by 290 px; it has no dismiss other than the two buttons.
8. Pullups viewer: feet on the floor while hanging from the bar; bar drawn as an open rectangle; ghost dashes detached from the body.
9. Workout log set card: three different left edges for kg/reps/RPE rows, empty right half, tick on the check button looks clipped; +/- targets about 34 dp.
10. Demos screen: first-screen control row (Pause/Front/Side/45) cut at the bottom edge; camera exercise row still clips "Roma|"; "Log this" bar covers the bottom of the viewer's next card.

Figure legibility, honestly: the barbell and machine viewers read clearly at phone size; the Pullups figure reads wrong; mini 3D thumbnails in the library are unreadable smudges at 130 px; Demos line figures are legible but thin and look like diagrams, not a body.

## Screenshots actually opened (all in this folder, c2_ prefix)
00, 02, 03, 04, 06, 07, 07b, 07c_chk, 07e_squat, 08_viewer_squat_default (actually the library search screen), 08_viewer_squatjerk_default (non-demo Squat Jerk), 09, 10, 11, 13, 14, 15_lib_pullups, 16-19 (pullups), 20-24 (leg press), 25-40, 41-44, 45-51.
Captured but NOT opened: 01_home_top, 05_home_back_up, 12_viewer_deadlift_side, 07d_cleared. I also opened and then deleted four wrong-exercise captures (Push-Up Wide, not a demo lift). Stray extras: 07_library_search (garbled text "tbarbell sq"), 07b/07c/07e (typing retries), 09 deadlift results.
