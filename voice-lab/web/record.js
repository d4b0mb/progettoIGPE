// Records the "stt" phrases of phrases.json one by one and saves each kept take on this computer through
// serve.mjs (POST /save): <id>.wav (16 kHz, mono, 16-bit PCM) and <id>.json (the reference text and notes).
import { encodeWav } from "/lib/wav.mjs";

const TARGET_RATE = 16000;
const TAIL_MS = 250; // keep recording a moment after Stop is pressed so the last word is not cut off
const NOISE_NOTES = ["quiet room", "kitchen noise", "music", "outdoors"];

const $ = (id) => document.getElementById(id);
const els = {
  noise: $("noise"), progress: $("progress"), progressText: $("progressText"), meta: $("meta"), phrase: $("phrase"),
  meterBar: $("meterBar"), recordBtn: $("recordBtn"), redoBtn: $("redoBtn"), keepBtn: $("keepBtn"), player: $("player"),
  status: $("status"), prevBtn: $("prevBtn"), nextBtn: $("nextBtn"), jump: $("jump"),
};

const state = {
  phrases: [],
  index: 0,
  done: new Set(), // ids that have both a .wav and a .json on disk
  stream: null,
  meter: null, // { context, analyser, data }
  recorder: null,
  chunks: [],
  recording: false,
  busy: false, // decoding or saving
  take: null, // { wav: Uint8Array, durationMs, peak, url }
};

function setStatus(text, isError = false) {
  els.status.textContent = text;
  els.status.classList.toggle("error", isError);
}

// ---- noise note: set once per session (survives a page reload, not a new browser session)
els.noise.value = NOISE_NOTES.includes(sessionStorage.getItem("voiceLabNoise")) ? sessionStorage.getItem("voiceLabNoise") : "quiet room";
els.noise.addEventListener("change", () => {
  sessionStorage.setItem("voiceLabNoise", els.noise.value);
  els.noise.blur();
});

// ---- what is already on disk (resume)
async function refreshDone() {
  const response = await fetch("/list?dir=recordings");
  if (!response.ok) throw new Error(`/list answered HTTP ${response.status}`);
  const { files } = await response.json();
  const names = new Set(files.map((f) => f.name));
  state.done = new Set(state.phrases.filter((p) => names.has(`${p.id}.wav`) && names.has(`${p.id}.json`)).map((p) => p.id));
}

function nextUndoneFrom(start) {
  for (let step = 0; step < state.phrases.length; step++) {
    const i = (start + step) % state.phrases.length;
    if (!state.done.has(state.phrases[i].id)) return i;
  }
  return -1;
}

// ---- drawing
function discardTake() {
  if (state.take?.url) URL.revokeObjectURL(state.take.url);
  state.take = null;
}

function render() {
  const phrase = state.phrases[state.index];
  if (!phrase) return;
  els.phrase.textContent = phrase.text;
  const tags = [`<span class="tag">${phrase.lang === "it" ? "Italiano" : "English"}</span>`, `<span class="tag">${phrase.id}</span>`];
  if (phrase.notes?.startsWith("HARD")) tags.push(`<span class="tag hard">hard one</span>`);
  if (state.done.has(phrase.id)) tags.push(`<span class="tag done">already recorded (recording again replaces it)</span>`);
  els.meta.innerHTML = tags.join("");

  els.progress.max = state.phrases.length;
  els.progress.value = state.done.size;
  els.progressText.textContent = `Phrase ${state.index + 1} of ${state.phrases.length} - ${state.done.size} recorded, ${state.phrases.length - state.done.size} to go`;

  els.recordBtn.textContent = state.recording ? "Stop" : state.take ? "Record again" : "Record";
  els.recordBtn.classList.toggle("recording", state.recording);
  els.recordBtn.disabled = state.busy;
  els.keepBtn.disabled = !state.take || state.recording || state.busy;
  els.redoBtn.disabled = !state.take || state.recording || state.busy;
  els.prevBtn.disabled = els.nextBtn.disabled = state.recording || state.busy;

  if (state.take) {
    els.player.hidden = false;
    if (els.player.src !== state.take.url) els.player.src = state.take.url;
  } else if (state.done.has(phrase.id)) {
    els.player.hidden = false;
    const url = new URL(`/out/recordings/${phrase.id}.wav`, location.href).href;
    if (els.player.src.split("?")[0] !== url) els.player.src = `${url}?t=${Date.now()}`; // t= avoids a stale cached copy
  } else {
    els.player.hidden = true;
    els.player.removeAttribute("src");
  }

  els.jump.innerHTML = "";
  state.phrases.forEach((p, i) => {
    const option = document.createElement("option");
    option.value = String(i);
    option.textContent = `${state.done.has(p.id) ? "✓" : "•"} ${i + 1}. ${p.text.slice(0, 60)}`;
    els.jump.appendChild(option);
  });
  els.jump.value = String(state.index);
}

function goTo(index) {
  if (state.recording || state.busy) return;
  state.index = (index + state.phrases.length) % state.phrases.length;
  discardTake();
  setStatus("");
  render();
}

// ---- microphone
async function ensureStream() {
  if (state.stream) return state.stream;
  if (!navigator.mediaDevices?.getUserMedia) throw new Error("This browser cannot use the microphone here. Open the page at http://127.0.0.1:8099 (not another address).");
  state.stream = await navigator.mediaDevices.getUserMedia({ audio: true }); // plain defaults, like a web app would get
  const context = new AudioContext();
  const analyser = context.createAnalyser();
  analyser.fftSize = 1024;
  context.createMediaStreamSource(state.stream).connect(analyser);
  state.meter = { context, analyser, data: new Float32Array(analyser.fftSize) };
  requestAnimationFrame(drawMeter);
  return state.stream;
}

function drawMeter() {
  const { analyser, data } = state.meter;
  analyser.getFloatTimeDomainData(data);
  let peak = 0;
  for (const v of data) peak = Math.max(peak, Math.abs(v));
  els.meterBar.style.width = `${Math.min(100, Math.round(peak * 140))}%`;
  requestAnimationFrame(drawMeter);
}

function pickMimeType() {
  const wanted = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"];
  return wanted.find((type) => window.MediaRecorder?.isTypeSupported?.(type));
}

async function startRecording() {
  try {
    await ensureStream();
  } catch (error) {
    setStatus(`Could not open the microphone: ${error.message}`, true);
    return;
  }
  if (state.meter.context.state === "suspended") await state.meter.context.resume();
  discardTake();
  const mimeType = pickMimeType();
  state.chunks = [];
  state.recorder = new MediaRecorder(state.stream, mimeType ? { mimeType } : undefined);
  state.recorder.ondataavailable = (event) => { if (event.data.size > 0) state.chunks.push(event.data); };
  state.recorder.onstop = () => { processTake().catch((error) => { state.busy = false; setStatus(`Could not process the recording: ${error.message}`, true); render(); }); };
  state.recorder.start();
  state.recording = true;
  setStatus("Recording... say the phrase, then press Space or Stop.");
  render();
}

function stopRecording() {
  if (!state.recording) return;
  state.recording = false;
  state.busy = true;
  setStatus("Finishing...");
  render();
  setTimeout(() => state.recorder.stop(), TAIL_MS);
}

/** Decodes the browser's recording, resamples it to 16 kHz mono and wraps it as a WAV file. */
async function processTake() {
  const blob = new Blob(state.chunks, { type: state.recorder.mimeType || "audio/webm" });
  const context = new AudioContext();
  let decoded;
  try {
    decoded = await context.decodeAudioData(await blob.arrayBuffer());
  } finally {
    context.close();
  }
  const offline = new OfflineAudioContext(1, Math.max(1, Math.ceil(decoded.duration * TARGET_RATE)), TARGET_RATE);
  const source = offline.createBufferSource();
  source.buffer = decoded;
  source.connect(offline.destination); // a mono destination mixes the channels down
  source.start();
  const rendered = await offline.startRendering();
  const samples = rendered.getChannelData(0);
  let peak = 0;
  for (const v of samples) peak = Math.max(peak, Math.abs(v));
  const wav = encodeWav(samples, TARGET_RATE);
  const durationMs = Math.round((samples.length / TARGET_RATE) * 1000);
  state.take = { wav, durationMs, peak, url: URL.createObjectURL(new Blob([wav], { type: "audio/wav" })) };
  state.busy = false;
  const warnings = [];
  if (peak < 0.02) warnings.push("It sounds almost silent: check the microphone.");
  if (durationMs < 500) warnings.push("Very short: did you say the whole phrase?");
  setStatus(`Take of ${(durationMs / 1000).toFixed(1)} s. ${warnings.join(" ")} Press P to listen, Enter to keep, R to redo.`.trim(), warnings.length > 0);
  render();
  els.player.play().catch(() => {});
}

async function post(name, body, type) {
  const response = await fetch(`/save?name=${encodeURIComponent(name)}`, { method: "POST", headers: { "Content-Type": type }, body });
  if (!response.ok) {
    const detail = await response.json().then((j) => j.error).catch(() => "");
    throw new Error(`HTTP ${response.status} ${detail}`.trim());
  }
}

async function keepTake() {
  if (!state.take || state.busy || state.recording) return;
  const phrase = state.phrases[state.index];
  const meta = { id: phrase.id, lang: phrase.lang, text: phrase.text, durationMs: state.take.durationMs, takenAt: new Date().toISOString(), noiseNote: els.noise.value };
  state.busy = true;
  setStatus("Saving...");
  render();
  try {
    await post(`recordings/${phrase.id}.wav`, state.take.wav, "audio/wav");
    await post(`recordings/${phrase.id}.json`, JSON.stringify(meta, null, 2), "application/json");
  } catch (error) {
    state.busy = false;
    setStatus(`Could not save (is serve.mjs still running?): ${error.message}`, true);
    render();
    return;
  }
  state.busy = false;
  state.done.add(phrase.id);
  discardTake();
  const next = nextUndoneFrom(state.index + 1);
  if (next === -1) {
    setStatus(`Saved ${phrase.id}. All ${state.phrases.length} phrases are recorded. Thank you!`);
    render();
  } else {
    state.index = next;
    setStatus(`Saved ${phrase.id}.`);
    render();
  }
}

function redo() {
  if (!state.take || state.busy || state.recording) return;
  discardTake();
  setStatus("Take thrown away. Record again when ready.");
  render();
}

function toggleRecording() {
  if (state.busy) return;
  if (state.recording) stopRecording();
  else startRecording();
}

// ---- buttons and keys
els.recordBtn.addEventListener("click", toggleRecording);
els.keepBtn.addEventListener("click", keepTake);
els.redoBtn.addEventListener("click", redo);
els.prevBtn.addEventListener("click", () => goTo(state.index - 1));
els.nextBtn.addEventListener("click", () => goTo(state.index + 1));
els.jump.addEventListener("change", () => { goTo(Number(els.jump.value)); els.jump.blur(); });

document.addEventListener("keydown", (event) => {
  if (event.target.tagName === "SELECT" || event.target === els.player || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.repeat) return;
  const key = event.key.toLowerCase();
  if (event.code === "Space") {
    event.preventDefault(); // otherwise a focused button would also "click"
    document.activeElement?.blur?.();
    toggleRecording();
  } else if (key === "enter") {
    event.preventDefault();
    keepTake();
  } else if (key === "r") {
    redo();
  } else if (key === "p") {
    if (!els.player.hidden) { els.player.currentTime = 0; els.player.play().catch(() => {}); }
  } else if (key === "arrowleft") {
    goTo(state.index - 1);
  } else if (key === "arrowright") {
    goTo(state.index + 1);
  }
});

window.addEventListener("beforeunload", () => state.stream?.getTracks().forEach((t) => t.stop()));

// ---- start
(async () => {
  try {
    const response = await fetch("/phrases.json");
    if (!response.ok) throw new Error(`/phrases.json answered HTTP ${response.status}`);
    state.phrases = (await response.json()).stt;
    await refreshDone();
  } catch (error) {
    // The helper is not running (or this page was opened from disk): say so in big letters, with a Retry button.
    els.phrase.textContent = "Could not load the phrases";
    setStatus(`${error.message}. Is node voice-lab/serve.mjs running?`, true);
    window.showHelperMessage?.();
    return;
  }
  const first = nextUndoneFrom(0);
  state.index = first === -1 ? 0 : first;
  setStatus(first === -1 ? "All phrases are already recorded. You can browse and record again." : state.done.size > 0 ? `Continuing where you left off (${state.done.size} already recorded).` : "Press Space or the Record button to start.");
  render();
})();
