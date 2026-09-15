# PoseCamera interface

- The live camera fills the entire window on a black fallback surface.
- Skeleton landmarks and connections remain neutral white except for the active exercise landmarks and connections.
- Active exercise feedback uses green `#4ADE80`, yellow `#FACC15`, red `#F87171`, or grey `#9CA3AF` when confidence is too low to judge.
- A safe top-center Squat/Push-up selector uses a translucent black 8 dp-radius chip; the selected option has a white surface with black text and selected semantics, while the unselected option remains transparent with white text.
- Changing exercise keeps the live camera and pose engine bound, applies the selected exercise config, starts a fresh rep counter, and clears the prior unsaved frame, set, and UI error.
- Push-up mode alone shows `Position phone to your side with your full body in frame.` in a compact translucent black guidance chip below the selector.
- The completed-inference FPS appears in a compact 8 dp-radius, translucent black chip at the safe top-start edge.
- One 48 dp camera-switch control sits at the safe bottom-end edge with a spoken “Switch camera” label.
- A compact white “End set” action sits at the safe bottom-start edge, opposite the camera switch.
- Ending a set replaces the camera with a black summary screen naming the exercise and showing attempted, clean, rejected, FormScore, and the most common rejection reason, followed by one “Start new set” action.
- Signed-out users see a centered white-on-black account screen with username/password fields, one primary login or registration action, and inline loading/error feedback; camera permission is requested only after authentication.
- A completed set uploads automatically from the summary screen. The screen shows syncing, synced, or failed status explicitly and offers retry or re-authentication instead of dropping failures silently.
- After a successful upload, the summary shows the current streak and the three most recent aggregate set records. No camera frames, video, landmarks, or pose data are represented in network UI or payloads.
- Permission failure replaces the preview with a centered sentence and one explicit retry action.
- System-bar insets are respected. The same placements remain usable in portrait and landscape.
- There is no decorative motion; camera throughput, pose legibility, and readable set results take priority.
