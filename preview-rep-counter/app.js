import { PoseLandmarker, FilesetResolver } from "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14";

const video = document.getElementById("webcam");
const canvas = document.getElementById("overlay");
const ctx = canvas.getContext("2d");
const statusEl = document.getElementById("status");
const hintEl = document.getElementById("hint");
const statClean = document.getElementById("stat-clean");
const statAttempted = document.getElementById("stat-attempted");
const statFormScore = document.getElementById("stat-formscore");
const modeBtns = document.querySelectorAll(".mode-btn");

let poseLandmarker = null;
let isDetecting = false;
let currentMode = "squat";

const CONNECTIONS = [
  [0, 1], [1, 2], [2, 3], [3, 7], [0, 4], [4, 5], [5, 6], [6, 8],
  [9, 10], [11, 12], [11, 13], [13, 15], [15, 17], [15, 19], [15, 21], [17, 19],
  [12, 14], [14, 16], [16, 18], [16, 20], [16, 22], [18, 20],
  [11, 23], [12, 24], [23, 24], [23, 25], [24, 26], [25, 27], [26, 28], [27, 29], [28, 30], [29, 31], [30, 32]
];

function angle(a, b, c) {
  const ba = { x: a.x - b.x, y: a.y - b.y, z: a.z - b.z };
  const bc = { x: c.x - b.x, y: c.y - b.y, z: c.z - b.z };
  const dot = ba.x * bc.x + ba.y * bc.y + ba.z * bc.z;
  const magBA = Math.hypot(ba.x, ba.y, ba.z);
  const magBC = Math.hypot(bc.x, bc.y, bc.z);
  const cos = Math.max(-1, Math.min(1, dot / (magBA * magBC)));
  return Math.round(Math.acos(cos) * 180 / Math.PI);
}

function visibility(lm, idx) {
  return lm[idx]?.visibility ?? 0;
}

function pickSide(lm, jointTriplets) {
  let bestScore = -1, best = null;
  for (const [name, [a, b, c]] of Object.entries(jointTriplets)) {
    const score = Math.min(visibility(lm, a), visibility(lm, b), visibility(lm, c));
    if (score > bestScore) { bestScore = score; best = { name, joints: [a, b, c] }; }
  }
  return best;
}

const EXERCISES = {
  squat: {
    name: "Squat",
    hint: "Face the camera, feet shoulder-width",
    primaryJoints: { knee: [[23, 25, 27], [24, 26, 28]] },
    secondaryJoints: { hip: [[11, 23, 25], [12, 24, 26]] },
    downThreshold: 100,
    upThreshold: 160,
    bodyLineTolerance: 25,
    evalRep: (lm, state) => {
      const primary = pickSide(lm, state.primaryJoints);
      if (!primary || primary.joints.some(i => !lm[i])) return null;
      const kneeAngle = angle(lm[primary.joints[0]], lm[primary.joints[1]], lm[primary.joints[2]]);
      const hipAngle = angle(lm[11], lm[23], lm[25]);
      const bodyStraight = Math.abs(180 - hipAngle);
      return { primaryAngle: kneeAngle, bodyDeviation: bodyStraight, side: primary.name };
    },
    drawJoints: (lm, state) => {
      const primary = pickSide(lm, state.primaryJoints);
      if (!primary) return [];
      return primary.joints;
    }
  },
  pushup: {
    name: "Push-up",
    hint: "Position camera to the SIDE, body in frame",
    primaryJoints: { elbow: [[11, 13, 15], [12, 14, 16]] },
    secondaryJoints: { bodyLine: [[11, 23, 27], [12, 24, 28]] },
    downThreshold: 90,
    upThreshold: 160,
    bodyLineTolerance: 20,
    evalRep: (lm, state) => {
      const primary = pickSide(lm, state.primaryJoints);
      if (!primary || primary.joints.some(i => !lm[i])) return null;
      const elbowAngle = angle(lm[primary.joints[0]], lm[primary.joints[1]], lm[primary.joints[2]]);
      const bodyAngle = angle(lm[11], lm[23], lm[27]);
      const bodyDeviation = Math.abs(180 - bodyAngle);
      return { primaryAngle: elbowAngle, bodyDeviation, side: primary.name };
    },
    drawJoints: (lm, state) => {
      const primary = pickSide(lm, state.primaryJoints);
      if (!primary) return [];
      return [...primary.joints, 11, 12, 23, 24];
    }
  }
};

let exercise = EXERCISES.squat;
let repState = "up";
let cleanReps = 0, attemptedReps = 0;
let currentRepClean = true;
let bodyLineBroken = false;

function getJointColor(deviation, tolerance) {
  if (deviation <= tolerance * 0.5) return "#00ff88";
  if (deviation <= tolerance) return "#ffaa00";
  return "#ff3344";
}

async function init() {
  try {
    statusEl.textContent = "Requesting camera…";
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" }
    });
    video.srcObject = stream;

    await new Promise(resolve => video.onloadedmetadata = resolve);

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;

    statusEl.textContent = "Loading model…";
    const vision = await FilesetResolver.forVisionTasks(
      "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm"
    );
    poseLandmarker = await PoseLandmarker.createFromOptions(vision, {
      baseOptions: {
        modelAssetPath: "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
        delegate: "GPU"
      },
      runningMode: "VIDEO",
      numPoses: 1,
      minPoseDetectionConfidence: 0.5,
      minPosePresenceConfidence: 0.5,
      minTrackingConfidence: 0.5
    });

    statusEl.textContent = "Detecting…";
    detectLoop();
  } catch (err) {
    console.error(err);
    statusEl.textContent = "Error: " + err.message;
  }
}

function detectLoop() {
  if (!video.requestVideoFrameCallback) {
    requestAnimationFrame(detectLoop);
    return;
  }
  video.requestVideoFrameCallback(onFrame);
}

function onFrame(now, metadata) {
  if (!poseLandmarker || isDetecting) {
    detectLoop();
    return;
  }
  isDetecting = true;

  const ts = metadata?.presentationTime ?? performance.now();
  const result = poseLandmarker.detectForVideo(video, ts);

  processFrame(result);

  isDetecting = false;
  detectLoop();
}

function processFrame(result) {
  draw(result);
  if (!result.landmarks?.[0]) return;

  const lm = result.landmarks[0];
  const evalResult = exercise.evalRep(lm, exercise);

  if (!evalResult) return;

  const { primaryAngle, bodyDeviation, side } = evalResult;
  const tolerance = exercise.bodyLineTolerance;

  if (bodyDeviation > tolerance) bodyLineBroken = true;

  if (repState === "up" && primaryAngle < exercise.downThreshold) {
    repState = "down";
    currentRepClean = true;
    bodyLineBroken = false;
  } else if (repState === "down" && primaryAngle > exercise.upThreshold) {
    repState = "up";
    if (currentRepClean && !bodyLineBroken) cleanReps++;
    else attemptedReps++;
    updateStats();
  }

  if (bodyDeviation > tolerance) currentRepClean = false;
}

function draw(result) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (!result.landmarks?.[0]) {
    hintEl.hidden = true;
    return;
  }

  const lm = result.landmarks[0];
  hintEl.hidden = true;

  ctx.lineWidth = 2;
  ctx.strokeStyle = "#00ff88";
  ctx.beginPath();
  for (const [a, b] of CONNECTIONS) {
    const pa = lm[a], pb = lm[b];
    if (pa && pb) {
      ctx.moveTo(pa.x * canvas.width, pa.y * canvas.height);
      ctx.lineTo(pb.x * canvas.width, pb.y * canvas.height);
    }
  }
  ctx.stroke();

  const highlightJoints = exercise.drawJoints(lm, exercise);
  for (const idx of highlightJoints) {
    const p = lm[idx];
    if (!p) continue;
    let color = "#00ff88";
    if (idx === 13 || idx === 14 || idx === 15 || idx === 16) {
      const evalResult = exercise.evalRep(lm, exercise);
      if (evalResult) color = getJointColor(evalResult.bodyDeviation, exercise.bodyLineTolerance);
    }
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(p.x * canvas.width, p.y * canvas.height, 6, 0, Math.PI * 2);
    ctx.fill();
  }

  if (exercise.name === "Push-up" && !hintEl.hidden) {
    hintEl.textContent = exercise.hint;
    hintEl.hidden = false;
  }
}

function updateStats() {
  statClean.textContent = cleanReps;
  statAttempted.textContent = attemptedReps;
  const total = cleanReps + attemptedReps;
  statFormScore.textContent = total ? Math.round((cleanReps / total) * 100) + "%" : "—";
}

function switchMode(mode) {
  currentMode = mode;
  exercise = EXERCISES[mode];
  repState = "up";
  cleanReps = 0; attemptedReps = 0; bodyLineBroken = false; currentRepClean = true;
  updateStats();
  modeBtns.forEach(btn => {
    btn.classList.toggle("active", btn.dataset.mode === mode);
    btn.setAttribute("aria-pressed", btn.dataset.mode === mode);
  });
  hintEl.textContent = exercise.hint;
  hintEl.hidden = false;
}

modeBtns.forEach(btn => btn.addEventListener("click", () => switchMode(btn.dataset.mode)));

init();