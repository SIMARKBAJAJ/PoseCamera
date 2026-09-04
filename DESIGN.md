# PoseCamera interface

- The live camera fills the entire window on a black fallback surface.
- Skeleton landmarks and connections remain neutral white except for squat feedback.
- Squat feedback colors only the hip and knee dots plus the hip-to-hip, hip-to-knee, and knee-to-ankle lines: green `#4ADE80`, yellow `#FACC15`, red `#F87171`, or grey `#9CA3AF` when confidence is too low to judge.
- The completed-inference FPS appears in a compact 8 dp-radius, translucent black chip at the safe top-start edge.
- One 48 dp camera-switch control sits at the safe bottom-end edge with a spoken “Switch camera” label.
- A compact white “End set” action sits at the safe bottom-start edge, opposite the camera switch.
- Ending a set replaces the camera with a black summary screen showing attempted, clean, rejected, FormScore, and the most common rejection reason, followed by one “Start new set” action.
- Permission failure replaces the preview with a centered sentence and one explicit retry action.
- System-bar insets are respected. The same placements remain usable in portrait and landscape.
- There is no decorative motion; camera throughput, pose legibility, and readable set results take priority.
