# SahiRep Project History

## Actual Name Now

The app is now called **SahiRep**.

The GitHub repo and local project folder are still named **PoseCamera** because the project started as a camera-pose prototype. Treat `PoseCamera` as the codebase/repo name and **SahiRep** as the product/app name.

## Original Idea

SahiRep started as an AI form-check fitness app for Indian gym beginners. The goal was not just to count reps, but to count only clean reps and give real-time form correction through the phone camera.

The early product ideas included:

- CleanRep Mode
- GhostCoach
- FormScore
- Consistency Streak
- GymCircle

The first rough MVP was too large: Android camera preview, squat detection, skeleton overlay, red/yellow/green joint feedback, clean rep counter, ghost squat-depth overlay, Hinglish voice cues, FormScore, and streaks.

The deferred ideas were deliberately pushed out of v1:

- 20+ exercises
- Full community chat
- Spotify integration
- Gym integrations
- Trainer marketplace
- Meal plans
- Injury diagnosis

## Reality Check And Rescope

The MVP was cut down to the smallest useful version:

- Squats
- Push-ups
- Skeleton overlay
- Joint feedback
- Clean rep counting
- FormScore

Ghost overlay was moved to post-v1 because it is the hardest feature in the project. It is closer to a research-level computer-vision task than a normal app stage.

The biggest technical constraint discovered was latency: cloud pose inference does not work well enough for budget and mid-range Android phones. SahiRep therefore uses on-device pose inference only.

## Non-Negotiable Constraints

- Pose inference must always run on-device.
- No frames, videos, landmarks, or live pose data are sent to any server.
- The backend is only for post-workout sync: accounts, streaks, and set history.
- v1 supports only squats and push-ups.
- The app must target budget and mid-range Android phones.
- The pose pipeline must stay shared: one detection/drawing core plus per-exercise config modules.
- Do not duplicate the full detection pipeline per exercise.

## How The App Is Built

Simar acts as the architect and PM: writing staged prompts, checking scope, and controlling the build sequence.

AI coding agents generate most of the implementation, but the workflow is constrained by:

- `ponytail` at ultra intensity to avoid bloated code.
- `simar.md` for personal operating rules and communication style.
- `AGENTS.md` in the repo root so agents load the project rules.
- Staged implementation with explicit test gates.
- No moving to the next stage until the current stage passes.

## Architecture

SahiRep has two main surfaces:

1. **Android app**
   - CameraX camera preview
   - MediaPipe/BlazePose via TFLite
   - On-device landmark detection
   - Skeleton overlay, joint angles, rep state machines, FormScore

2. **Browser preview**
   - MediaPipe Tasks Vision PoseLandmarker
   - `pose_landmarker_lite` model
   - Used for fast prototyping and demos before porting logic to Android

The backend is Flask with JWT auth and SQLite via SQLAlchemy. It exists only for post-workout sync and never touches live camera or pose data.

Repo: `SIMARKBAJAJ/PoseCamera`

## What Has Been Built

### Browser Preview

The browser preview originally had a bad patch: it used a hardcoded mock skeleton animation instead of real pose inference.

That was caught and replaced with real MediaPipe PoseLandmarker detection, squat and push-up modes, red/yellow/green joint coloring, rep state machines, and live FormScore.

### Android Stages 1-3

The Android app was built in stages:

1. CameraX camera preview with skeleton overlay.
2. Squat joint angles with red/yellow/green feedback.
3. Squat rep state machine and FormScore.
4. Push-up support added using the same shared architecture.

### Backend Stage 4 Part A

The Flask backend was built with JWT auth, account support, streaks, set history, and tests.

### Android Stage 4 Part B

Android backend integration has been implemented with Retrofit, OkHttp, Gson, Android Keystore JWT storage, login/register flow, protected Bearer requests, completed-set upload, sync feedback, streak display, and recent set history.

Current remaining gate: physical-device or stable-emulator runtime QA for the full camera-to-sync flow.

## Current Status

SahiRep is no longer just a PoseCamera prototype. It is now an Android fitness form-check app with:

- On-device pose detection
- Squat tracking
- Push-up tracking
- Clean rep counting
- Attempted/bad-form rep tracking
- FormScore
- Flask post-workout sync
- JWT auth
- Streak and recent-set display

The project folder and repo are still named `PoseCamera`, but the actual app/product name is **SahiRep**.

## What Is Left

1. Verify end-to-end Android sync on a physical device or stable emulator.
2. Deploy the Flask backend publicly after LAN testing is solid.
3. Add Hinglish voice coaching.
4. Treat ghost overlay as its own later mini-project, not a normal quick feature.
5. Tune pose thresholds through real squat and push-up testing.

Simar still needs to understand the 33 MediaPipe landmark points and basic joint-angle math because threshold tuning cannot be fully delegated to an AI agent.
