# Phase C emulator verification (c_ screenshots)

Setup: emulator SahiRep_API_36 (1080x2340, swiftshader), emu APK with 10.0.2.2:5000, throwaway backend on a copy of sahirep.db, user `verifyc`, seeded with `--ez seed true`. Login worked (You > "Last result: OK"). Not all 49 captures were individually opened; those marked (seen) were. Emulator and backend were killed, db copy deleted, plain assembleDebug restored (BuildConfig BASE_URL = http://192.168.1.16:5000/).

Identity key: plumb line (left timeline rail + plumb plane), 3D figure/muscle, Haldi (amber) / Sahi (green) / Sindoor (red), chamfered card corners.

## Verdicts
| Screen | Files | Verdict | Identity visible |
|---|---|---|---|
| Home top (seen) | c_01, c_04 | CLEAN | plumb rail yes, chamfer yes, Haldi yes; muscle hero is a flat 2D front/back figure, not 3D |
| Home scrolled (seen) | c_02, c_03 | CLEAN | rail + green node, chamfers, Haldi streak chain |
| Home hero drag (seen) | c_05 | CLUTTERED | drag turns FRONT to BACK (flip), no tilt. Hero card has large empty band under the figure. Pill still says "Offline" although You says Synced |
| Train landing (seen) | c_06 | CLEAN | Haldi CTA; chamfer only on LIFTS card; no 3D/plumb on this screen |
| Workout log, set checked, rest timer, PR (seen) | c_07-c_10 | CLEAN | plate stack Sindoor/Haldi, Sahi check, "PR!" lime medal; stick figure header is tiny |
| Library list (seen) | c_11, c_14 | CLEAN | mini muscle thumbnails Haldi |
| Library search / chip | c_12, c_12b, c_13 | WRONG (test artifact + real bug) | search field and filter persist across tab switches (typed "squat" twice gave "squatsquat" and "Kuch nahi mila"). Chip row clips "TRAPS" at edge (scrollable, acceptable) |
| Library detail without demo (seen) | c_15 | CLEANISH | front and back muscle maps, big empty area below (~25% of screen) |
| Exercise viewer default / orbit / SIDE / ghost off (seen) | c_16-c_20 | CLUTTERED | 3D orbit works, plumb plane, Neel ghost, chamfer. Defects: stick figure is crude, the ghost and the figure overlap into a blue smear in SIDE; floating "Log this" bar covers the MUSCLES card header |
| Progress top / scrolled (seen) | c_21, c_22 | CLUTTERED | macro rings: end-knob overlaps "PROTEIN"/"CARBS" labels, "1358" touches ring edge. PR rows: only first has a medal, others show an empty dark circle |
| Nutrition (seen) | c_23, c_24 | CLEAN | red X delete targets, text hints Hinglish. Header ring knobs again touching labels |
| Add-food sheet (seen) | c_25 | CLUTTERED | bottom nav stays drawn over the sheet; list is raw USDA ("Abiyuch", "Agutuk, fish with seal oil...") with no Indian-food-first ordering; sheet has no chamfer/identity |
| Plan (seen) | c_26, c_27 | WRONG | "Plans download ho rahe hain. Sync ke baad aayenge" and "Koi plan nahi" with ~55% empty screen. Plans never loaded even after sync OK (copied DB probably has no plans, so unverified) |
| You / sync / power (seen) | c_28, c_29 | CLEAN | Tier line, Sync now, Log out; profile has no chamfer-less cards. Header "Pehle sex chuno" CTA wording ok |
| Camera HUD (seen) | c_30-c_32, c_48, c_49 | CLUTTERED | HUD shows on virtual scene (rep ring 0, depth ring, plumb line on right, 27 FPS). On a bright scene the white "Demos" and "Sync now" text is invisible, exercise tab "Romanian deadlift" is clipped to "Roman", light active tab pill blends with white scene. Bottom nav is visible during camera |
| Power gating | c_40-c_45, c_50-c_54 | WRONG (by design, but misleading) | unplugged + 20%: home, You and viewer stay NORMAL ("Tier: NORMAL, Full quality") until consent is given on the camera screen. After tapping Reduce there: You shows "Tier: SAVER ... animations reduced, camera 15 fps, simple overlay". At 10% CRITICAL has no consent so still full animation. So SAVER/CRITICAL is only reachable via camera prompt; non-camera screens give no prompt |
| animator_duration_scale 0 | c_46, c_47 | CLEAN | viewer: Play button and "DRAG TO ORBIT" removed, scrubber static, figure flat |

## Jank (swiftshader software GL, NOT representative; dumpsys gfxinfo after reset)
| Screen | frames | janky | p90 | p95 | p99 |
|---|---|---|---|---|---|
| Viewer orbit + play (12 swipes) | 431 | 159 (36.89%) | 53 ms | 61 ms | 69 ms (gpu p90 reported 4950 ms, emulator artifact) |
| Library scroll (8 swipes) | 225 | 19 (8.44%) | 44 ms | 48 ms | 65 ms |
| Home (hero drag + 3 scroll cycles) | 335 | 16 (4.78%) | 42 ms | 48 ms | 53 ms |
| Progress (4 scroll cycles) | 250 | 18 (7.20%) | 34 ms | 36 ms | 42 ms |

Viewer is the heaviest by far (median 46 ms).

## Crashes
`adb logcat -d | grep FATAL`: the lines seen were `FATAL EXCEPTION: SystemUIBg-*` (system UI, not com.example.posecamera). No FATAL from the app process seen. I killed the emulator before a final per-process check, so this is from the grep output only.

## Worst 10 defects
1. Power gating only reachable through the camera consent prompt. On home, library, viewer and You the tier stays NORMAL at 20% and 10% unplugged. Expected SAVER/CRITICAL there is not met.
2. Plans screen: empty with "download ho rahe hain" after a successful sync, ~55% blank (check with seeded plans).
3. Viewer: orbit scene is the heaviest screen (37% janky, p99 69 ms) and the ghost overlaps the figure into a smear.
4. Library search and filter persist across tabs and re-typing appends to old text.
5. Camera HUD: white "Demos" / "Sync now" invisible on bright scenes, tab labels clipped ("Roman"), bottom nav visible over the camera.
6. Progress and nutrition macro rings: end-knobs overlap the PROTEIN and CARBS labels, 4-digit kcal touches the ring.
7. Progress PR list: only the newest row has a PR medal, other rows have an empty dark circle.
8. Add-food sheet: bottom nav drawn over sheet, raw USDA ordering (Abiyuch, Acerola ...) instead of common foods first.
9. Home pill reads "Offline" at first load and in the hero-drag shot while sync is OK on the You tab.
10. Home hero: drag turns the figure (flip) rather than tilting, and the hero card has a large empty band beneath the figure. "Log this" bar in viewer covers the MUSCLES card header.

Tap targets: workout-log +/- buttons ~98 px tall (about 36-40 dp at this density) were not measured precisely; they look under 48 dp wide at 2x density.
