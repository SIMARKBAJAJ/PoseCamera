# AGENTS.md — SahiRep / PoseCamera

## MANDATORY FIRST STEPS — do these before anything else, every session

1. Read `simar.md` in this repo root using your Read tool, in full, before
   responding to any task. Its "Interaction Rules" and "System Directive"
   sections govern how you communicate for the rest of this session —
   tone, pushback behavior, brevity, and the debugging response format.
   Do not summarize it back to me, just apply it silently from then on.
2. Activate ponytail at `ultra` intensity for this entire session
   (`/ponytail ultra` if your host supports the command; if not, apply the
   ponytail minimal-code ladder as strictly as possible anyway: don't
   build what doesn't need to exist, reuse what's already in the repo,
   prefer stdlib/native features, and never write more than the task
   needs). Never relax this without me explicitly saying so.

## Project summary

SahiRep (repo: PoseCamera) is a squat/push-up form-tracking app for Indian
gym-goers. Two surfaces:
- Android app: on-device pose detection (MediaPipe/BlazePose via TFLite),
  CameraX.
- Browser preview (`preview/`): MediaPipe Tasks Vision (PoseLandmarker,
  `pose_landmarker_lite`), for demoing the same pipeline in a browser.

## Hard constraints — never violate

- All pose inference is on-device / client-side. No frame or video data
  ever leaves the device or browser to any server or cloud API.
- Target hardware is budget/mid-range Android phones — stay lightweight,
  never assume a flagship GPU.
- The Flask backend exists only for post-workout sync (accounts, streaks,
  set history via JWT + Retrofit). It never touches video/pose data.
- Exercise scope is squats + push-ups only. Do not add further exercises,
  ghost-overlay, or new cloud dependencies without explicit instruction.
- Shared core, not forked code: one pose-detection/drawing core, with a
  per-exercise config module (joint angles, thresholds, FormScore rules)
  for each exercise. Never duplicate the whole pipeline per exercise.

## Build process rules

- Work in small, testable stages (see Android Stage 1–4 plan and browser
  equivalents already agreed with me). Confirm which stage we're on
  before writing code for it.
- After any change, tell me exactly what to run/click and what result
  confirms it worked.
- If a change touches more than 2 files, list every file and give full
  corrected file contents, not partial diffs, unless I ask for a diff.
- Never silently swap libraries, models, or architecture — propose and
  explain tradeoffs first.
- No placeholder/pseudo-code in anything presented as ready to run.
