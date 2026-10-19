# SahiRep form-coach research (2026-10-16)

Scope: MediaPipe PoseLandmarker (33 landmarks), one phone 30-45 deg off the user's front. Companion file: `form_rules.json` (25 lifts, 99 faults).

## Honesty note (read first)
- NSCA Essentials of Strength Training and Conditioning and ACSM guidelines are paywalled books. I could not open them, so **nothing is attributed to them**. Their technique cues remain to be checked by someone with the book.
- Public web search mostly returned coaching blogs, which I rejected as sources. I used PubMed records (abstracts read via NCBI E-utilities) and official Android/MediaPipe docs.
- **No peer-reviewed numeric "fault" threshold was found for any lift.** All 99 `threshold` fields are `null`. Numbers in the `rep_phase_joint` text and notes ("top >= ~165 deg" etc.) are unsourced project defaults to be tuned on real data.
- `verified: true` means the cited abstract directly supports the qualitative fault (1 of 99). `source` set but `verified: false` means the paper is relevant context only (e.g. Hewett 2005 is about jump landing, not squats).

## Verified source list
| Id | Reference | URL / DOI | What it supports |
|---|---|---|---|
| S1 | Schoenfeld 2010, J Strength Cond Res 24(12):3497 | https://doi.org/10.1519/JSC.0b013e3181bac2d7 | Review of squat kinematics/kinetics (abstract read only; general context) |
| S2 | Hewett et al. 2005, Am J Sports Med 33(4):492 | https://doi.org/10.1177/0363546504269591 | Valgus knee loading in jump-landing predicts ACL injury in 205 female athletes (landing, not squat; no angle cutoff) |
| S3 | Escamilla et al. 2000, Med Sci Sports Exerc 32(7):1265 | https://doi.org/10.1097/00005768-200007000-00013 | Sumo vs conventional deadlift from 24 competition lifters: descriptive kinematics (sumo trunk 5-10 deg more vertical, conventional knees/hips ~12 deg more extended at lift-off); not safety thresholds |
| S4 | Shoji et al. 2025, Front Sports Act Living 7:1682991 | https://doi.org/10.3389/fspor.2025.1682991 | Lumbar flexion and posterior pelvic tilt rise with load (60-90% 1RM) in deadlift; authors advise keeping lordosis (optical 6-marker spine model, n=13) |
| S5 | Fenwick, Brown, McGill 2009, J Strength Cond Res 23(5):1408 | https://doi.org/10.1519/JSC.0b013e3181b07334 | Standing barbell bent-over row gave the largest lumbar load among 3 rows (n=7) |
| S6 | Duffey & Challis 2007, J Strength Cond Res 21(2):556 | https://doi.org/10.1519/R-19885.1 | Bench-press bar path and speed change as sets approach failure |
| S7 | Lehman 2005, J Strength Cond Res 19(3):587 | https://doi.org/10.1519/R-15024.1 | Grip width/supination change EMG only slightly (not a form-error criterion) |
| S8 | Dickie et al. 2017, J Electromyogr Kinesiol 32:30 | https://doi.org/10.1016/j.jelekin.2016.11.004 | Pull-up grip variants change muscle activation (context only) |
| S9 | Escamilla et al. 2006, JOSPT 36(2):45 | https://doi.org/10.2519/jospt.2006.36.2.45 | EMG of crunch/sit-up and ab machines (context only) |
| S10 | Contreras et al. 2015, J Appl Biomech 31(6):452 | https://doi.org/10.1123/jab.2014-0301 | Hip thrust > back squat for glute EMG (not a form criterion) |
| S11 | Abdollahi et al. 2025, J Orthop Surg Res 20:776 | https://doi.org/10.1186/s13018-025-06212-1 | Hand position affects scapular kinematics in push-ups, healthy vs shoulder pain (context) |
| S12 | Fry, Smith, Schilling 2003, J Strength Cond Res 17(4):629 | https://doi.org/10.1519/00124278-200311000-00001 | Classic knee-travel study. **Bibliographic details only; abstract not read, so unverified** |
| S13 | Hanen et al. 2025, Front Bioeng Biotechnol 13:1597209 | https://doi.org/10.3389/fbioe.2025.1597209 | Conventional vs sumo deadlift kinematics/kinetics at 85% 1RM (n=30) |
| V1 | Bazarevsky et al. 2020, BlazePose | https://arxiv.org/abs/2006.10204 | 33 keypoints, single person, over 30 fps on a Pixel 2 |
| V2 | Asaeda et al. 2024, Heliyon | https://doi.org/10.1016/j.heliyon.2024.e36338 | MediaPipe vs VICON, 15 people, single-leg landing: MediaPipe knee angle was 18.83-19.68 deg higher (p<0.05); absolute values not valid, change from initial contact had good reliability and validity |
| V3 | Janisch et al. 2026, BMC Musculoskelet Disord 27:407 | https://doi.org/10.1186/s12891-026-09958-9 | BlazePose vs manual 2D app, frontal knee angle, n=16: CCC 0.89, bias -0.67 deg, LoA -8.76 to +7.42 deg (agreement with a manual 2D reference, not with optical mocap) |
| V4 | Oliosi et al. 2026, JMIR mHealth 14:e82412 | https://doi.org/10.2196/82412 | Camera angle/distance vs detection and rep counting, 44 people: squats best from diagonal at 200 cm (95.5%, MAE 0.05 reps), worst from side at 90 cm (0%); push-ups best from diagonal at 90-180 cm (up to 85.7%) |
| A1 | PoseLandmarker docs | https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker | Lite/Full/Heavy models; detector 224x224, landmarker 256x256; three confidence defaults of 0.5 |
| A2 | CameraX image analysis | https://developer.android.com/media/camera/camerax/analyze | Backpressure, resolution/aspect, 30 fps ~32 ms budget |
| A3 | ImageAnalysis reference | https://developer.android.com/reference/androidx/camera/core/ImageAnalysis | Default STRATEGY_KEEP_ONLY_LATEST |
| A4 | PowerManager reference | https://developer.android.com/reference/android/os/PowerManager | Thermal status/headroom, isPowerSaveMode |
| A5 | Thermal API guide | https://developer.android.com/games/optimize/adpf/thermal | Status meanings, listener usage |
| A6 | Battery monitoring | https://developer.android.com/training/monitoring-device-state/battery-monitoring | ACTION_BATTERY_CHANGED, charging state |

Also seen but NOT used (only snippets, no abstract, or preprint): an IMU-referenced MediaPipe squat study reporting knee flexion underestimated by about 13.9 deg with better lateral-view and tight-clothing results; an OpenCap single-participant feasibility study; the Edriss et al. 2025 mini review (https://doi.org/10.3389/fphys.2025.1649330, abstract not read). Treat the first two as leads, not citations.

## Part 1 - Lifts

### Cross-cutting findings
1. **Measurable from 2D/3D landmarks:** joint-angle ranges (knee, hip, elbow), trunk inclination, depth proxy (hip vs knee height), L-R asymmetry, body-line deviation (shoulder-hip-ankle), heel lift, shoulder-ear distance (shrug proxy), arm-to-torso angle (elbow flare), momentum (angular velocity / trunk-angle range during a set).
2. **Not reliably measurable:** lumbar/thoracic rounding and pelvic tuck (no spine/pelvis landmarks; S4 needed an optical spine marker set), scapular winging/elevation (no scapula), bar path (no barbell landmark; wrist is only a proxy), foot pressure/ankle roll, hyperextension of the lumbar spine, shoulder external rotation.
3. **Knee valgus:** the strongest injury-prevention signal in the literature I could verify (S2, landing task), and V2/V3 show MediaPipe/BlazePose can track frontal-plane change but with ~19 deg absolute offset vs VICON. Use change from standing baseline, never absolute angle. At a 30-45 deg camera angle the frontal projection is foreshortened, so valgus magnitude will be underestimated: expect to flag only large collapse.
4. **Camera view:** most trunk-lean, depth, hip-sag and bar-related faults need side view; valgus, asymmetry, shrug and flare need front view. A 45 deg setup gives partial credit on both. `camera_ok_at_45` is true for 16 lifts where the movement is largely elbow/knee flexion visible from a diagonal, false for 9 lifts where load, bench, or machine occludes the body.
5. **Rep phases:** each lift in the JSON names one joint angle (squat/lunge/leg press/step-up: knee flexion; hinge/bridge: hip; push/pull/curl: elbow; lateral raise: shoulder abduction; plank: none, use a hold timer). Direction of change defines eccentric/concentric. These are engineering definitions and the hysteresis bands (e.g. top >= ~165 deg) are unsourced defaults.

### Per-lift summary (details in JSON)
| Lift | Faults | Measurable | Key sourced context | camera_ok_at_45 |
|---|---|---|---|---|
| Bodyweight squat | 6 | 5 | S2 (valgus, landing) | yes |
| Goblet squat | 4 | 3 | S2 | yes |
| Barbell back squat | 5 | 4 | S1, S2, S12 | no (bar/plate occlusion) |
| Barbell front squat | 3 | 3 | S2 | no |
| Lunge | 5 | 5 | S2 | yes |
| Conventional deadlift | 5 | 3 | S3, S4 (verified lumbar flexion), S13 | no |
| Romanian deadlift | 4 | 2 | S4 (extrapolated) | no |
| Glute bridge / hip thrust | 4 | 3 | S10 | yes |
| Push-up | 4 | 3 | S11 | yes |
| Bench press | 4 | 2 | S6, S7 | no |
| Overhead press | 4 | 4 | none | yes |
| Bent-over row | 4 | 3 | S5 | no |
| Seated cable row | 3 | 3 | none | no |
| Pull-up / chin-up | 4 | 4 | S8 (context) | yes |
| Lat pulldown | 4 | 4 | none | yes |
| Biceps curl | 4 | 4 | none | yes |
| Triceps pushdown / dip | 3 | 3 | none | yes |
| Lateral raise | 4 | 3 | none | yes |
| Plank | 4 | 4 | none | yes |
| Leg press | 4 | 3 | none | no |
| Leg curl | 3 | 3 | none | no |
| Calf raise | 3 | 2 | none | yes |
| Crunch / sit-up | 4 | 4 | S9 (EMG context) | yes |
| Face pull | 3 | 2 | none | yes |
| Step-up | 4 | 3 | S2 | yes |

(Exact counts are in the JSON; this table is a readable index.)

### Findings that matter for thresholds
- Knee past toes (back squat): S12 is the classic citation that restricting forward knee travel shifts load to hip/low back. I could not read it; do not flag knee-over-toe as an error.
- Deadlift: S3 reports mean joint angles of elite lifters; these are descriptive. S4 supports "lumbar flexion grows with load", but a phone cannot measure it, so the app should phrase any back-position cue as a reminder, not a measured error.
- Push-up elbow flare "45 deg" circulates widely; I found no peer-reviewed source and kept `threshold: null`.
- Bench press, row, pull-up, curl, press, raise: only kinematic-cheat faults (swing, shrug, partial range) are measurable, with no published numeric limits found.

## Part 2 - Feasibility

### Accuracy evidence
- BlazePose design target is speed on-device (V1): >30 fps on a Pixel 2 for 33 keypoints; the paper accompanying the model is not an angle-validity study.
- V2: MediaPipe knee angles in single-leg landing differed from VICON by roughly 19 deg in absolute terms; **change from baseline** correlated and was reliable. Implication: report relative angles/changes (e.g. valgus increase from standing), not absolute degrees.
- V3: BlazePose frontal knee angle vs a manual 2D app reference: limits of agreement about -8.8 to +7.4 deg; this is 2D-vs-2D agreement, not 3D mocap validity. OpenPose agreed better (CCC 0.95 vs 0.89).
- V4: the only camera-placement study found. Diagonal and frontal views at mid distance gave the best detection and rep counting for squats and push-ups; pure side view at 90 cm failed for squats. It measures detection/counting, not joint-angle error.
- **Gap:** no peer-reviewed study found that quantifies MediaPipe joint-angle error vs optical mocap specifically at a 30-45 deg off-front phone placement for the 25 lifts. Say so in the product; do not claim a degree accuracy.

### Failure modes (what is documented vs expected)
| Failure mode | Evidence |
|---|---|
| Occlusion by camera angle/distance, self-occlusion | Documented in V4 (zero detection from a close side view) |
| Depth ambiguity / z-axis | MediaPipe z is relative; angles in image plane shrink with off-axis rotation (geometric fact; no cited study) |
| Barbell, plates, machines, loose clothing | Expected; **no citation found**. An IMU-referenced study snippet suggested tight clothing helped, unverified |
| Low light, camera tilt | Expected; **no citation found** |
| Absolute-angle bias | V2 |
| Landmark jitter at ankle/foot | Expected; no citation |

### Recommended thresholds (engineering defaults, not literature)
- MediaPipe's own confidence defaults are 0.5 for detection, presence and tracking (A1). Starting point: keep those and additionally require per-landmark `visibility` >= 0.5 for every landmark in the measured angle, or skip scoring that frame/rep. Raise to 0.6-0.7 if false alarms occur. Treat as tunable.
- Smoothing: apply a light low-pass or One-Euro filter on landmark coordinates before angle computation, and smooth angles again (3-5 frame median) for rep detection; require min rep duration and hysteresis. No source; validate on recorded clips.
- Gate coaching: score a rep only if all required landmarks passed visibility for >= ~80% of the rep frames (tunable). If failed, say "can't see you well" instead of guessing.
- Ask user to stay in full frame, wear fitted clothing, use even lighting, and phone height near hip/chest level.

### Safety copy and what not to claim
- Show a first-run notice: coaching is general fitness feedback from a phone camera, not medical advice; stop if pain; consult a professional for injury or medical conditions.
- Do **not** claim: injury diagnosis or prediction, "safe/unsafe" labels, ACL-risk scores, spine-health judgments, or accuracy figures in degrees. S2 links valgus loading to ACL risk in landing athletes; it does not make a 2D phone estimate a diagnostic test.
- Phrase unmeasurable faults (back rounding, scapular control) as reminders/cues, never as detections.
- Keep video on device; state this plainly.

## Part 3 - Low-power on-device guidance (Android)
- **Model:** PoseLandmarker offers Lite, Full, Heavy (A1). The docs page I fetched gives no latency figures; benchmark on target devices. Start with Lite on old phones.
- **Confidence knobs:** `minPoseDetectionConfidence`, `minPosePresenceConfidence`, `minTrackingConfidence` all default 0.5 (A1). In VIDEO/LIVE_STREAM mode the tracker reuses the previous frame to avoid re-running the detector each frame.
- **Delegate:** MediaPipe supports CPU and GPU delegates; the PoseLandmarker page does not describe their relative behavior. I did not find an official statement that GPU is faster or cooler on old phones: **benchmark both**, and keep a CPU fallback when GPU init fails.
- **CameraX analysis (A2, A3):** default image format is YUV_420_888; you can request RGBA_8888. Set either target resolution or aspect ratio, not both; actual output depends on hardware. Default backpressure is STRATEGY_KEEP_ONLY_LATEST, which drops frames while the analyzer is busy: good for slow devices. Docs say analyze within the frame-time budget (about 32 ms at 30 fps) or use a frame-dropping mode, and always call `ImageProxy.close()`. Use a modest analysis resolution (e.g. 640x480; the model input is 256x256 and 224x224 anyway) and set rotation with an OrientationEventListener.
- **Frame-rate throttling:** simple approach: in `analyze()`, return early (closing the proxy) when less than the target interval has elapsed; target about 15 fps normal, 10 fps when throttled. This is a practical pattern, not an Android-documented setting; reps are slow enough that 10-15 fps is a design hypothesis to test.
- **Thermal (A4, A5):** `PowerManager.getCurrentThermalStatus()` and `addThermalStatusListener(OnThermalStatusChangedListener)` were added in API 29; remove the listener in `removeThermalStatusListener`. Docs group statuses: NONE; LIGHT/MODERATE (some throttling, little impact); SEVERE/CRITICAL/EMERGENCY/SHUTDOWN (significant impact). `getThermalHeadroom(forecastSeconds)` (API 30) estimates headroom before severe throttling. Suggested policy: LIGHT/MODERATE -> drop fps/resolution; SEVERE+ -> pause analysis and tell the user. On API < 29 the listener does not exist; fall back to battery temperature from ACTION_BATTERY_CHANGED (EXTRA_TEMPERATURE; the Android docs page fetched covers charge state, so the temperature extra is not cited here).
- **Battery (A6, A4):** register `ACTION_BATTERY_CHANGED` as a sticky intent (`registerReceiver(null, filter)`) to read level and charging state (EXTRA_STATUS, EXTRA_PLUGGED); docs advise doing more work on AC, less on USB, least on battery. `PowerManager.isPowerSaveMode()` (API 21) reports battery saver; listen to `ACTION_POWER_SAVE_MODE_CHANGED`. In power save: Lite model, lower fps, shorter sessions.

## Summary
- Lifts: 25. Faults: 99. Measurable from landmarks: 82; not measurable: 17.
- Numeric thresholds with a citation: **0 of 99**; every `threshold` is null.
- Faults with any `source` (context citation): 17; of these, **1 is `verified: true`** (deadlift lumbar flexion, S4, and it is itself not measurable from landmarks); 98 are `verified: false`.
- **Top risks:**
  1. No citable numeric thresholds, so cutoffs will be project choices that need validation on labeled video and, ideally, review by a coach/physio.
  2. Absolute MediaPipe angle bias (~19 deg for knee valgus vs VICON, V2): use relative changes and avoid precise degree claims.
  3. 30-45 deg camera plane mixes views, foreshortens valgus and sagittal angles, and no study quantifies accuracy for it; machine/barbell lifts (9 of 25) are likely unusable.
  4. Highest-injury-relevance faults (lumbar rounding, scapular control, bar path) cannot be measured from landmarks.
  5. Over-claiming: do not present output as injury detection or medical advice.
  6. Old-phone thermal and battery behavior is unbenchmarked; GPU vs CPU benefit is unverified.
- Next steps: read NSCA/ACSM technique chapters to add sourced qualitative cues; record test clips of the 25 lifts at 30-45 deg and tune thresholds empirically; benchmark Lite/Full and CPU/GPU on 2-3 old devices.
