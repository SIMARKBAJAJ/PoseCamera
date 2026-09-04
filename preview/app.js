const screens = {
  permission: document.querySelector("#permission-screen"),
  camera: document.querySelector("#camera-screen"),
  summary: document.querySelector("#summary-screen"),
};

const video = document.querySelector("#camera-video");
const poseBody = document.querySelector("#pose-body");
const fpsChip = document.querySelector("#fps-chip");
const cameraMessage = document.querySelector("#camera-message");
const grantCamera = document.querySelector("#grant-camera");
const endSet = document.querySelector("#end-set");
const switchCamera = document.querySelector("#switch-camera");
const startNewSet = document.querySelector("#start-new-set");

const formStates = [
  { color: "#9ca3af", squat: false, result: "unjudgeable" },
  { color: "#facc15", squat: true, result: "rejected" },
  { color: "#4ade80", squat: true, result: "clean" },
  { color: "#4ade80", squat: false, result: "clean" },
  { color: "#f87171", squat: true, result: "rejected" },
];

let stream = null;
let facingMode = "user";
let animationTimer = null;
let fpsTimer = null;
let stateIndex = 0;
let cleanFrames = 0;
let rejectedFrames = 0;

function showScreen(name) {
  Object.entries(screens).forEach(([key, screen]) => {
    screen.hidden = key !== name;
  });
}

function stopStream() {
  if (stream) {
    stream.getTracks().forEach((track) => track.stop());
    stream = null;
  }
  video.srcObject = null;
  video.classList.remove("live", "mirrored");
}

function stopSimulation() {
  window.clearInterval(animationTimer);
  window.clearInterval(fpsTimer);
  animationTimer = null;
  fpsTimer = null;
}

function updatePose() {
  const state = formStates[stateIndex % formStates.length];
  document.documentElement.style.setProperty("--form-color", state.color);
  poseBody.classList.toggle("squat", state.squat);

  if (state.result === "clean") cleanFrames += 1;
  if (state.result === "rejected") rejectedFrames += 1;
  stateIndex += 1;
}

function startSimulation() {
  stopSimulation();
  stateIndex = 0;
  cleanFrames = 0;
  rejectedFrames = 0;
  updatePose();
  animationTimer = window.setInterval(updatePose, 1700);
  fpsTimer = window.setInterval(() => {
    fpsChip.textContent = `${(24 + Math.random() * 5).toFixed(1)} FPS`;
  }, 850);
}

async function connectCamera() {
  stopStream();
  cameraMessage.hidden = true;

  if (!navigator.mediaDevices?.getUserMedia) {
    cameraMessage.textContent = "Camera unavailable. Showing the simulated preview feed.";
    cameraMessage.hidden = false;
    return;
  }

  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: facingMode } },
    });
    video.srcObject = stream;
    video.classList.add("live");
    video.classList.toggle("mirrored", facingMode === "user");
    await video.play();
  } catch (error) {
    cameraMessage.textContent = "Camera permission was not granted. Showing the simulated preview feed.";
    cameraMessage.hidden = false;
  }
}

async function beginSet() {
  showScreen("camera");
  startSimulation();
  await connectCamera();
}

function finishSet() {
  stopSimulation();
  stopStream();

  const attempted = Math.max(1, cleanFrames + rejectedFrames);
  const clean = cleanFrames;
  const rejected = Math.max(0, attempted - clean);
  const score = (clean / attempted) * 100;

  document.querySelector("#attempted-value").textContent = String(attempted);
  document.querySelector("#clean-value").textContent = String(clean);
  document.querySelector("#rejected-value").textContent = String(rejected);
  document.querySelector("#score-value").textContent = `${score.toFixed(1)}%`;
  document.querySelector("#reason-value").textContent = rejected > 0 ? "Squat depth" : "None";
  showScreen("summary");
}

grantCamera.addEventListener("click", beginSet);
endSet.addEventListener("click", finishSet);
startNewSet.addEventListener("click", beginSet);
switchCamera.addEventListener("click", async () => {
  facingMode = facingMode === "user" ? "environment" : "user";
  await connectCamera();
});

window.addEventListener("beforeunload", () => {
  stopSimulation();
  stopStream();
});
