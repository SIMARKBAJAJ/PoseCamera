import {
  DrawingUtils,
  FilesetResolver,
  PoseLandmarker,
} from "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@1.0.1/+esm";

const WASM_ROOT = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@1.0.1/wasm";
const MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task";

const video = document.querySelector("#camera-video");
const canvas = document.querySelector("#pose-canvas");
const cameraStage = document.querySelector("#camera-stage");
const status = document.querySelector("#status");
const startButton = document.querySelector("#start-camera");
const canvasContext = canvas.getContext("2d");

let stream = null;
let poseLandmarker = null;
let drawingUtils = null;
let detectionLoopActive = false;
let isDetecting = false;
let videoFrameCallbackId = null;
let animationFrameId = null;
let lastVideoTime = -1;
let lastDetectionTimestamp = -Infinity;
let completedDetections = 0;
let fpsWindowStartedAt = 0;
let firstResultReported = false;

function setStatus(message, state = "working") {
  status.textContent = message;
  status.dataset.state = state;
}

function resizeCanvasToVideo() {
  if (video.videoWidth === 0 || video.videoHeight === 0) return;

  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  cameraStage.style.aspectRatio = `${video.videoWidth} / ${video.videoHeight}`;
  canvasContext.clearRect(0, 0, canvas.width, canvas.height);
}

async function startCamera() {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error("This browser does not support webcam access.");
  }

  setStatus("Requesting camera permission...");

  stream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: {
      facingMode: "user",
      width: { ideal: 640 },
      height: { ideal: 480 },
      frameRate: { ideal: 30, max: 30 },
    },
  });

  const metadataReady = new Promise((resolve, reject) => {
    video.addEventListener(
      "loadedmetadata",
      () => {
        resizeCanvasToVideo();
        resolve();
      },
      { once: true },
    );
    video.addEventListener("error", () => reject(new Error("The webcam stream could not be loaded.")), {
      once: true,
    });
  });

  video.srcObject = stream;
  await metadataReady;
  await video.play();

  console.info(`[SahiRep] Camera ready: ${video.videoWidth}x${video.videoHeight}`);
  setStatus("Camera ready. Loading pose model...");
}

async function loadPoseModel() {
  const vision = await FilesetResolver.forVisionTasks(WASM_ROOT);

  poseLandmarker = await PoseLandmarker.createFromOptions(vision, {
    baseOptions: {
      modelAssetPath: MODEL_URL,
    },
    runningMode: "VIDEO",
    numPoses: 1,
    minPoseDetectionConfidence: 0.5,
    minPosePresenceConfidence: 0.5,
    minTrackingConfidence: 0.5,
    outputSegmentationMasks: false,
  });

  drawingUtils = new DrawingUtils(canvasContext);
  console.info("[SahiRep] PoseLandmarker lite model loaded.");
}

function drawPoseResult(result) {
  canvasContext.clearRect(0, 0, canvas.width, canvas.height);

  const landmarks = result.landmarks[0];
  if (!landmarks) return;

  drawingUtils.drawConnectors(landmarks, PoseLandmarker.POSE_CONNECTIONS, {
    color: "#4ade80",
    lineWidth: 4,
  });
  drawingUtils.drawLandmarks(landmarks, {
    color: "#ffffff",
    fillColor: "#4ade80",
    lineWidth: 2,
    radius: 3,
  });
}

function updateDetectionRate() {
  completedDetections += 1;
  const now = performance.now();
  const elapsed = now - fpsWindowStartedAt;

  if (elapsed < 1000) return;

  const fps = (completedDetections * 1000) / elapsed;
  setStatus(`Detecting... ${fps.toFixed(1)} FPS`, "ready");
  completedDetections = 0;
  fpsWindowStartedAt = now;
}

function stopDetectionWithError(error) {
  detectionLoopActive = false;
  console.error("[SahiRep] Pose detection stopped:", error);
  setStatus(`Error: ${error.message}`, "error");
  startButton.disabled = false;
  startButton.textContent = "Retry camera";
}

function processCurrentFrame() {
  if (!detectionLoopActive || isDetecting || !poseLandmarker || video.readyState < 2) return;

  isDetecting = true;
  const timestamp = Math.max(performance.now(), lastDetectionTimestamp + 0.001);
  lastDetectionTimestamp = timestamp;

  try {
    const result = poseLandmarker.detectForVideo(video, timestamp);

    if (!detectionLoopActive) return;

    drawPoseResult(result);
    updateDetectionRate();

    if (!firstResultReported) {
      firstResultReported = true;
      console.info("[SahiRep] Pose detection is running.");
    }
  } catch (error) {
    stopDetectionWithError(error);
  } finally {
    isDetecting = false;
  }
}

function scheduleNextFrame() {
  if (!detectionLoopActive) return;

  if (typeof video.requestVideoFrameCallback === "function") {
    videoFrameCallbackId = video.requestVideoFrameCallback(handleVideoFrame);
    return;
  }

  animationFrameId = window.requestAnimationFrame(handleAnimationFrame);
}

function handleVideoFrame() {
  scheduleNextFrame();
  processCurrentFrame();
}

function handleAnimationFrame() {
  scheduleNextFrame();

  if (video.currentTime === lastVideoTime) return;

  lastVideoTime = video.currentTime;
  processCurrentFrame();
}

function startDetectionLoop() {
  detectionLoopActive = true;
  lastVideoTime = -1;
  lastDetectionTimestamp = -Infinity;
  completedDetections = 0;
  fpsWindowStartedAt = performance.now();
  firstResultReported = false;
  setStatus("Detecting...", "ready");
  scheduleNextFrame();
}

function stopDetectionLoop() {
  detectionLoopActive = false;

  if (videoFrameCallbackId !== null && typeof video.cancelVideoFrameCallback === "function") {
    video.cancelVideoFrameCallback(videoFrameCallbackId);
  }
  if (animationFrameId !== null) {
    window.cancelAnimationFrame(animationFrameId);
  }

  videoFrameCallbackId = null;
  animationFrameId = null;
}

function stopCamera() {
  stopDetectionLoop();
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  video.srcObject = null;
  canvasContext.clearRect(0, 0, canvas.width, canvas.height);
}

async function initializePreview() {
  startButton.disabled = true;
  startButton.textContent = "Starting...";

  try {
    await startCamera();
    await loadPoseModel();
    startButton.textContent = "Camera running";
    startDetectionLoop();
  } catch (error) {
    stopCamera();
    console.error("[SahiRep] Preview failed to start:", error);
    setStatus(`Error: ${error.message}`, "error");
    startButton.disabled = false;
    startButton.textContent = "Retry camera";
  }
}

startButton.addEventListener("click", initializePreview);
window.addEventListener("pagehide", () => {
  stopCamera();
  poseLandmarker?.close();
  poseLandmarker = null;
});
