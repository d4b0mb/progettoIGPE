/**
 * The pure part of talking to the assistant: which recording format to ask the browser for, when someone has
 * stopped speaking, which language to listen in, what a microphone error means, and how a reply is shortened
 * for the speaker. No React and no browser objects here, so all of it is easy to test.
 *
 * Voice only turns speech into text (which is then sent like any typed message) and the assistant's text into sound.
 * Nothing said or transcribed ever confirms a proposal: only the Confirm button on a card does.
 */

/** A press on the microphone button longer than this is "hold to talk"; a shorter one is a tap. */
export const HOLD_MS = 400;
/** A recording shorter than this is a slip of the finger, never sent. */
export const MIN_RECORDING_MS = 400;
/** A recording is stopped and sent after this long even if nobody tapped and the room never went quiet. */
export const MAX_RECORDING_MS = 30_000;
/** How often the microphone level is looked at while listening. */
export const TICK_MS = 50;
/** The server accepts at most this many characters to speak. */
export const MAX_SPEECH_CHARS = 600;

/** Louder than this (root mean square of the samples, 0..1) counts as speech. Echo cancelling and noise suppression are on. */
export const SPEECH_RMS_THRESHOLD = 0.02;
/** After speech, this much quiet means the sentence is over. */
export const SILENCE_MS = 1200;
/** Less speech than this is a click or a cough, not a sentence. */
export const MIN_SPEECH_MS = 200;

export type VoiceLanguage = "it" | "en";

// ---------------------------------------------------------------------------------------------------------------
// Recording format

export interface RecordingType {
  mimeType: string;
  /** The file extension that matches the type, for the name of the uploaded file. */
  extension: string;
}

/** In order of preference. Safari and iPhone record mp4 (AAC), Chrome and Firefox record webm or ogg (Opus). */
const RECORDING_TYPES: readonly RecordingType[] = [
  { mimeType: "audio/webm;codecs=opus", extension: "webm" },
  { mimeType: "audio/webm", extension: "webm" },
  { mimeType: "audio/ogg;codecs=opus", extension: "ogg" },
  { mimeType: "audio/mp4", extension: "mp4" },
  { mimeType: "audio/mp4;codecs=mp4a.40.2", extension: "mp4" },
];

/**
 * The best recording format the browser can make (`isSupported` is MediaRecorder.isTypeSupported), or null if it
 * can make none of the formats the server understands. A check that throws counts as "not supported".
 */
export function pickRecordingType(isSupported: (mime: string) => boolean): RecordingType | null {
  for (const type of RECORDING_TYPES) {
    let supported = false;
    try {
      supported = isSupported(type.mimeType) === true;
    } catch {
      supported = false;
    }
    if (supported) return { ...type };
  }
  return null;
}

/** "audio/webm;codecs=opus" -> "audio/webm": the type without its parameters, in lower case. */
export function baseMimeType(mime: string): string {
  return mime.split(";")[0].trim().toLowerCase();
}

/** The file extension for a recording's type, or null for a type this screen does not know. */
export function extensionFor(mime: string): string | null {
  switch (baseMimeType(mime)) {
    case "audio/webm":
      return "webm";
    case "audio/ogg":
      return "ogg";
    case "audio/mp4":
    case "audio/x-m4a":
    case "audio/m4a":
      return "mp4";
    case "audio/aac":
      return "aac";
    case "audio/mpeg":
    case "audio/mp3":
      return "mp3";
    case "audio/wav":
    case "audio/x-wav":
    case "audio/wave":
      return "wav";
    default:
      return null;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Loudness and silence

/** The loudness of a block of samples (each between -1 and 1): the root mean square. 0 for an empty block. */
export function rmsOf(samples: Float32Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    sum += samples[i] * samples[i];
  }
  const rms = Math.sqrt(sum / samples.length);
  return Number.isFinite(rms) ? rms : 0;
}

/** A loudness (rms) as a 0..1 height for the level meter. A square root, because ears hear loudness on a curve. */
export function meterLevel(rms: number): number {
  if (!Number.isFinite(rms) || rms <= 0) return 0;
  return Math.min(1, Math.sqrt(rms) * 1.6);
}

export interface SilenceDetectorOptions {
  /** Loudness (rms) from which a moment counts as speech. */
  threshold: number;
  /** How long it must be quiet after speech for the recording to be over. */
  silenceMs: number;
  /** The least speech that counts. A shorter noise followed by quiet is forgotten and the wait starts again. */
  minSpeechMs: number;
}

/** "waiting": no speech yet. "speaking": speech has started (also during a short pause). "ended": speech, then enough silence. */
export type SilenceVerdict = "waiting" | "speaking" | "ended";

export interface SilenceDetector {
  /** Gives the loudness measured at a moment (any clock, in milliseconds) and says where the recording stands. */
  feed(rms: number, nowMs: number): SilenceVerdict;
  /** True once any moment was loud enough to count as speech (stays true, also for a noise that was forgotten). */
  heardSpeech(): boolean;
}

/**
 * Decides when someone has finished speaking. It only knows the numbers it is fed, so a given sequence always
 * gives the same answers.
 *
 * - It never ends before speech started: a quiet room stays "waiting".
 * - It never ends before `minSpeechMs` of speech: a shorter noise followed by `silenceMs` of quiet is forgotten
 *   (back to "waiting") instead of ending the recording or hanging until the time limit.
 * - After speech of at least `minSpeechMs`, `silenceMs` without a loud moment ends it. Once "ended", always "ended".
 */
export function createSilenceDetector(options: Partial<SilenceDetectorOptions> = {}): SilenceDetector {
  const { threshold = SPEECH_RMS_THRESHOLD, silenceMs = SILENCE_MS, minSpeechMs = MIN_SPEECH_MS } = options;
  let speechStart: number | null = null;
  let lastSpeech = 0;
  let heard = false;
  let ended = false;

  return {
    feed(rms, nowMs) {
      if (ended) return "ended";
      if (rms >= threshold) {
        if (speechStart === null) speechStart = nowMs;
        lastSpeech = nowMs;
        heard = true;
        return "speaking";
      }
      if (speechStart === null) return "waiting";
      if (nowMs - lastSpeech < silenceMs) return "speaking";
      if (lastSpeech - speechStart >= minSpeechMs) {
        ended = true;
        return "ended";
      }
      speechStart = null;
      return "waiting";
    },
    heardSpeech: () => heard,
  };
}

/** A recording that is too short or in which nobody spoke is dropped without a word. */
export function shouldDiscard(recording: { durationMs: number; heardSpeech: boolean }): boolean {
  return recording.durationMs < MIN_RECORDING_MS || !recording.heardSpeech;
}

/** Whether a press of this length (in milliseconds) on the microphone button was a "hold to talk". */
export function isHold(pressMs: number): boolean {
  return pressMs > HOLD_MS;
}

// ---------------------------------------------------------------------------------------------------------------
// Language

/** The language to listen and speak in, from the language of the screen ("it", "it-IT" -> Italian; anything else English). */
export function voiceLanguage(locale: string): VoiceLanguage {
  const primary = locale.trim().toLowerCase().split(/[-_]/)[0];
  return primary === "it" ? "it" : "en";
}

// ---------------------------------------------------------------------------------------------------------------
// Errors

/**
 * Why the microphone could not be used, as a key in the "assistant.voice" translations.
 * "insecure": the page is not on https (browsers only give the microphone to secure pages). "denied": the person
 * (or the browser's settings) said no. "notFound": no microphone. "busy": another app holds it. "unsupported":
 * this browser cannot record. "unknown": anything else.
 */
export type MicErrorCode = "insecure" | "denied" | "notFound" | "busy" | "unsupported" | "unknown";

/** What the page knows about its surroundings, to tell the cases apart when there is no error object at all. */
export interface MicContext {
  /** False when navigator.mediaDevices does not exist (a plain http:// page that is not localhost). */
  mediaDevicesAvailable?: boolean;
  /** window.isSecureContext. */
  secureContext?: boolean;
}

function nameOf(error: unknown): string {
  if (typeof error === "object" && error !== null && "name" in error) {
    const name = (error as { name: unknown }).name;
    return typeof name === "string" ? name : "";
  }
  return "";
}

function messageOf(error: unknown): string {
  if (typeof error === "object" && error !== null && "message" in error) {
    const message = (error as { message: unknown }).message;
    return typeof message === "string" ? message : "";
  }
  return "";
}

/**
 * Maps what getUserMedia and MediaRecorder throw (DOMException names) to a fixed code for a translated line.
 * Without `navigator.mediaDevices` (what a plain http page gets) the answer is "insecure", unless the page is
 * known to be secure, in which case the browser is just too old.
 */
export function describeMicError(error: unknown, context: MicContext = {}): MicErrorCode {
  if (context.mediaDevicesAvailable === false) {
    return context.secureContext === true ? "unsupported" : "insecure";
  }
  switch (nameOf(error)) {
    case "NotAllowedError":
    case "PermissionDeniedError":
      return "denied";
    case "NotFoundError":
    case "DevicesNotFoundError":
    // No microphone satisfies the constraints: for the person it is the same as having none.
    case "OverconstrainedError":
    case "ConstraintNotSatisfiedError":
      return "notFound";
    case "NotReadableError":
    case "TrackStartError":
    case "AbortError":
      return "busy";
    case "SecurityError":
      return "insecure";
    case "NotSupportedError":
      return "unsupported";
    case "TypeError":
      // Reading getUserMedia of an undefined navigator.mediaDevices.
      return /mediaDevices|getUserMedia/i.test(messageOf(error)) ? "insecure" : "unknown";
    default:
      return "unknown";
  }
}

/**
 * Every voice failure the screen has a fixed line for (a key in "assistant.voice"): the microphone ones, plus what
 * the voice endpoints can answer. "tooLarge": 413. "unsupportedType": 415. "tooManyRequests": 429. "unavailable":
 * 502, 503 or 504 (the engine is off, unreachable or slow). "connectionLost": no answer at all.
 * "playbackBlocked": the browser refused to play the sound.
 */
export type VoiceErrorCode =
  | Exclude<MicErrorCode, "unknown">
  | "tooLarge"
  | "unsupportedType"
  | "tooManyRequests"
  | "unavailable"
  | "connectionLost"
  | "playbackBlocked"
  | "generic";

/** Maps the HTTP status of a failed voice call (null: no answer at all) to its fixed line. The server's own words are never shown. */
export function voiceApiErrorFor(status: number | null): VoiceErrorCode {
  switch (status) {
    case null:
      return "connectionLost";
    case 413:
      return "tooLarge";
    case 415:
      return "unsupportedType";
    case 429:
      return "tooManyRequests";
    case 502:
    case 503:
    case 504:
      return "unavailable";
    default:
      return "generic";
  }
}

/** A microphone error as a line the screen has: the one thing nobody can name is the general line. */
export function voiceErrorForMic(code: MicErrorCode): VoiceErrorCode {
  return code === "unknown" ? "generic" : code;
}

// ---------------------------------------------------------------------------------------------------------------
// Speaking

/** Markdown emphasis marks mean nothing to a speaker (and would be read out as "asterisk"). */
function stripEmphasis(text: string): string {
  return (
    text
      .replace(/[*`]+/g, "")
      // Underscores around a word are emphasis; the one inside snake_case is not touched.
      .replace(/(^|[^A-Za-z0-9_À-ÿ])_+|_+(?=[^A-Za-z0-9_À-ÿ]|$)/g, (_match, lead: string | undefined) => lead ?? "")
  );
}

/**
 * A reply made ready for the speaker: trimmed, white space collapsed, emphasis marks (asterisks, backticks,
 * underscores) removed, and cut to `max` characters. A long reply is cut at the last sentence end under the limit
 * (when that keeps at least a third of it) so the voice does not stop in the middle of a sentence; otherwise exactly at the limit.
 */
export function clampSpeechText(text: string, max: number = MAX_SPEECH_CHARS): string {
  if (max <= 0) return "";
  const plain = stripEmphasis(text).replace(/\s+/g, " ").trim();
  if (plain.length <= max) return plain;

  const floor = Math.ceil(max / 3);
  for (let end = max; end >= floor; end--) {
    const ch = plain[end - 1];
    if ((ch === "." || ch === "!" || ch === "?" || ch === "…") && plain[end] === " ") {
      return plain.slice(0, end);
    }
  }

  let cut = plain.slice(0, max);
  // Never leave half of an emoji (a lone first half of a surrogate pair) at the end.
  const last = cut.charCodeAt(cut.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) cut = cut.slice(0, -1);
  return cut.trimEnd();
}

// ---------------------------------------------------------------------------------------------------------------
// Small helpers

/** Reads a saved on/off setting ("1"/"0", "true"/"false"); anything else, or nothing saved, gives the default. */
export function parseFlag(raw: string | null | undefined, fallback: boolean): boolean {
  if (raw === "1" || raw === "true") return true;
  if (raw === "0" || raw === "false") return false;
  return fallback;
}

/**
 * A WAV file of silence (16-bit mono; a single sample by default). Playing it from a tap is what unlocks an
 * audio element for later playback on iPhone Safari, which only lets a page play sound that a tap started.
 */
export function buildSilentWav(samples = 1, sampleRate = 8000) {
  const dataBytes = samples * 2;
  const bytes = new Uint8Array(44 + dataBytes);
  const view = new DataView(bytes.buffer);
  const text = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i));
  };
  text(0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  text(8, "WAVE");
  text(12, "fmt ");
  view.setUint32(16, 16, true); // the size of this block
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // one channel
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // bytes per second
  view.setUint16(32, 2, true); // bytes per sample frame
  view.setUint16(34, 16, true); // bits per sample
  text(36, "data");
  view.setUint32(40, dataBytes, true);
  return bytes;
}
