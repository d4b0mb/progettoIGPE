"use client";

import { useCallback, useEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { ApiError } from "@/api/errors";
import { MAX_MESSAGE_CHARS, type ChatMessage } from "./chat";
import {
  MAX_RECORDING_MS,
  TICK_MS,
  buildSilentWav,
  clampSpeechText,
  createSilenceDetector,
  describeMicError,
  extensionFor,
  isHold,
  meterLevel,
  baseMimeType,
  parseFlag,
  pickRecordingType,
  rmsOf,
  shouldDiscard,
  voiceApiErrorFor,
  voiceErrorForMic,
  voiceLanguage,
  type SilenceDetector,
  type SilenceVerdict,
  type VoiceErrorCode,
} from "./voice";
import { fetchVoiceStatus, speakAudio, transcribeAudio, type VoiceStatus } from "./voiceApi";

/** Where the two switches are kept, so they survive a reload. */
export const AUTOSEND_KEY = "ristomanager.voice.autosend";
export const SPEAK_KEY = "ristomanager.voice.speak";

/**
 * What voice is doing. "thinking" is the assistant working on a question (it is the assistant's own busy flag, shown
 * here so one value tells the whole story); the others are the microphone and the speaker.
 */
export type VoiceState = "idle" | "listening" | "transcribing" | "thinking" | "speaking";

/** The same, plus "starting" (waiting for the microphone, e.g. for the person to answer the permission question). */
type Phase = "idle" | "starting" | "listening" | "transcribing" | "speaking";

export interface UseVoiceOptions {
  /** The assistant sheet is open. Voice is only looked for, and only active, while it is. */
  open: boolean;
  /** The language of the screen, which is the language to listen and speak in. */
  locale: string;
  /** Sends text to the assistant as an ordinary message (`spoken`: it was said, so the reply should be short and speakable). Voice never does anything else with what it heard. */
  send: (text: string, spoken?: boolean) => Promise<void>;
  /** The assistant is working on a question. */
  busy: boolean;
  /** The conversation, to find the replies to speak. */
  messages: readonly ChatMessage[];
  /** Puts what was heard in the text box (when "send automatically" is off) for the person to read and send. */
  onDraft: (text: string) => void;
}

export interface VoiceApi {
  /** The server offers voice (enabled and speech to text). Nothing of the voice UI shows otherwise. */
  available: boolean;
  /** ...and it can also speak. Only then is the "speak replies" switch offered. */
  canSpeak: boolean;
  /** False on a page that is not https (and not localhost): the browser will not give it the microphone. */
  secure: boolean;
  state: VoiceState;
  /** How loud it is now, 0..1, for the meter. */
  level: number;
  /** Why the last attempt failed, as a key in "assistant.voice"; null when nothing is wrong. */
  error: VoiceErrorCode | null;
  /** Nothing intelligible was heard in the last recording. */
  notHeard: boolean;
  /** Counts the transcripts put in the text box, so the screen can move focus there. */
  drafted: number;
  autoSend: boolean;
  speakReplies: boolean;
  setAutoSend: (on: boolean) => void;
  setSpeakReplies: (on: boolean) => void;
  /** Spread these on the microphone button: a tap starts and a second tap sends; holding talks until release. */
  micProps: {
    onPointerDown: (event: ReactPointerEvent<HTMLButtonElement>) => void;
    onPointerUp: (event: ReactPointerEvent<HTMLButtonElement>) => void;
    onPointerCancel: (event: ReactPointerEvent<HTMLButtonElement>) => void;
    onClick: () => void;
    onContextMenu: (event: ReactMouseEvent<HTMLButtonElement>) => void;
  };
  /** Stops the voice that is being played (does nothing otherwise). */
  stopSpeaking: () => void;
  /** Gets the audio player ready for sound. Call it from a tap or key press: iPhone Safari only allows sound then. */
  unlockAudio: () => void;
  /** Hides the error and the "did not catch that" line. */
  dismissNotice: () => void;
  /** Stops everything: recording, requests, playback. Closing the sheet and unmounting do this by themselves. */
  stopAll: () => void;
}

function readFlag(key: string, fallback: boolean): boolean {
  if (typeof window === "undefined") return fallback;
  try {
    return parseFlag(window.localStorage.getItem(key), fallback);
  } catch {
    // Storage can be unavailable (private mode, blocked): the default applies.
    return fallback;
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    window.localStorage.setItem(key, value ? "1" : "0");
  } catch {
    // Not saved; the switch still works until the page is reloaded.
  }
}

/** Which fixed line a failed voice call gets. The server's own words are never used. */
function errorFor(error: unknown): VoiceErrorCode {
  if (error instanceof ApiError) return voiceApiErrorFor(error.status);
  // The player refusing to start without a tap.
  if (typeof DOMException !== "undefined" && error instanceof DOMException) {
    return error.name === "NotAllowedError" ? "playbackBlocked" : "generic";
  }
  // fetch fails with a TypeError when no answer came back at all.
  if (error instanceof TypeError) return voiceApiErrorFor(null);
  return "generic";
}

/** Created inside the tap (an audio context made later stays asleep on iPhone Safari). */
function createAudioContext(): AudioContext | null {
  try {
    const Constructor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    return Constructor ? new Constructor() : null;
  } catch {
    return null;
  }
}

/** Feeds the microphone into an analyser, so its loudness can be read. Null when the browser cannot. */
function connectAnalyser(context: AudioContext | null, stream: MediaStream): AnalyserNode | null {
  if (!context) return null;
  try {
    const source = context.createMediaStreamSource(stream);
    const analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    // Not connected to the speakers: that would play the person's own voice back.
    source.connect(analyser);
    return analyser;
  } catch {
    return null;
  }
}

/** A recorder in the best format the browser has, or null when it has none the server understands. */
function buildRecorder(stream: MediaStream): { recorder: MediaRecorder; mimeType: string; extension: string } | null {
  const picked = pickRecordingType((mime) => typeof MediaRecorder.isTypeSupported === "function" && MediaRecorder.isTypeSupported(mime));
  const recorder = picked ? new MediaRecorder(stream, { mimeType: picked.mimeType }) : new MediaRecorder(stream);
  const mimeType = recorder.mimeType || picked?.mimeType || "";
  const extension = extensionFor(mimeType) ?? picked?.extension ?? null;
  return extension === null ? null : { recorder, mimeType, extension };
}

/** Everything that must be let go of when listening ends. */
interface Mic {
  stream: MediaStream;
  recorder: MediaRecorder;
  chunks: Blob[];
  mimeType: string;
  extension: string;
  detector: SilenceDetector;
  startedAt: number;
  timer: number | null;
}

/**
 * Talking to the assistant by voice: the microphone, the transcription, and the spoken replies, as one small
 * state machine (idle, listening, transcribing, thinking, speaking).
 *
 * The one rule that matters: voice never confirms anything. What was heard goes through the ordinary send() as a
 * plain message (or into the text box); only the Confirm button on a proposal card confirms.
 *
 * All the mutable state lives in refs and every asynchronous step checks an "epoch": stopping anything (the sheet
 * closing, Clear, a second tap, unmounting) bumps the epoch, so a step that was still on its way finds out it is
 * out of date and lets go of what it had.
 */
export function useVoice(options: UseVoiceOptions): VoiceApi {
  const { open, busy, messages } = options;
  const [info, setInfo] = useState<{ status: VoiceStatus; secure: boolean } | null>(null);
  const [state, setState] = useState<"idle" | "listening" | "transcribing" | "speaking">("idle");
  const [level, setLevel] = useState(0);
  const [error, setError] = useState<VoiceErrorCode | null>(null);
  const [notHeard, setNotHeard] = useState(false);
  const [drafted, setDrafted] = useState(0);
  const [autoSend, setAutoSendState] = useState(() => readFlag(AUTOSEND_KEY, true));
  const [speakReplies, setSpeakRepliesState] = useState(() => readFlag(SPEAK_KEY, false));

  // The latest props and settings, for steps that finish long after the render that started them.
  const latest = useRef({ ...options, autoSend, speakReplies });
  useEffect(() => {
    latest.current = { ...options, autoSend, speakReplies };
  });

  const phaseRef = useRef<Phase>("idle");
  const epochRef = useRef(0);
  const micRef = useRef<Mic | null>(null);
  const contextRef = useRef<AudioContext | null>(null);
  // The transcription or speech request in flight.
  const requestRef = useRef<AbortController | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioUrlRef = useRef<string | null>(null);
  const unlockedRef = useRef(false);
  const statusAbortRef = useRef<AbortController | null>(null);
  const statusRequestedRef = useRef(false);
  const pressRef = useRef<{ at: number } | null>(null);
  const suppressClickUntilRef = useRef(0);
  // The last message already dealt with (spoken or deliberately skipped), so no reply is spoken twice.
  const handledRef = useRef<string | null>(null);

  const setPhase = useCallback((next: Phase) => {
    phaseRef.current = next;
    setState(next === "starting" ? "listening" : next);
  }, []);

  /** Lets go of the microphone, the recorder and the audio context. */
  const releaseMic = useCallback(() => {
    const mic = micRef.current;
    micRef.current = null;
    const context = contextRef.current;
    contextRef.current = null;
    if (mic) {
      if (mic.timer !== null) window.clearInterval(mic.timer);
      // No handler may run for a recording nobody wants any more.
      mic.recorder.ondataavailable = null;
      mic.recorder.onstop = null;
      mic.recorder.onerror = null;
      try {
        if (mic.recorder.state !== "inactive") mic.recorder.stop();
      } catch {
        // Already stopped.
      }
      for (const track of mic.stream.getTracks()) track.stop();
    }
    if (context) {
      context.close().catch(() => undefined);
    }
  }, []);

  /** Stops the sound and lets go of the audio file. The player itself is kept, already unlocked, for the next reply. */
  const releasePlayback = useCallback(() => {
    const audio = audioRef.current;
    if (audio) {
      audio.onended = null;
      audio.onerror = null;
      try {
        audio.pause();
        audio.removeAttribute("src");
      } catch {
        // Nothing to stop.
      }
    }
    const url = audioUrlRef.current;
    audioUrlRef.current = null;
    if (url) URL.revokeObjectURL(url);
  }, []);

  /** Stops everything and forgets what was on its way. Does not touch React state: safe while unmounting. */
  const teardown = useCallback(() => {
    epochRef.current += 1;
    requestRef.current?.abort();
    requestRef.current = null;
    releaseMic();
    releasePlayback();
    phaseRef.current = "idle";
  }, [releaseMic, releasePlayback]);

  const cancelActivity = useCallback(() => {
    teardown();
    setState("idle");
    setLevel(0);
  }, [teardown]);

  const stopAll = useCallback(() => {
    cancelActivity();
    setError(null);
    setNotHeard(false);
  }, [cancelActivity]);

  /**
   * Plays a silent one-sample clip on the reusable audio element. Called from a tap or a key press, this is what makes
   * iPhone Safari allow the same element to play a reply later, when no tap is going on.
   */
  const unlockAudio = useCallback(() => {
    if (unlockedRef.current || typeof Audio === "undefined") return;
    unlockedRef.current = true;
    try {
      if (!audioRef.current) audioRef.current = new Audio();
      const audio = audioRef.current;
      const url = URL.createObjectURL(new Blob([buildSilentWav()], { type: "audio/wav" }));
      audio.src = url;
      const done = () => URL.revokeObjectURL(url);
      void Promise.resolve(audio.play()).then(done, () => {
        // Refused: try again at the next tap.
        unlockedRef.current = false;
        done();
      });
    } catch {
      unlockedRef.current = false;
    }
  }, []);

  /** Asks for the status once; a failure counts as "voice is not available" (and is tried again the next time the sheet opens). */
  const loadStatus = useCallback(async () => {
    const controller = new AbortController();
    statusAbortRef.current = controller;
    try {
      const status = await fetchVoiceStatus(controller.signal);
      if (controller.signal.aborted) return;
      setInfo({ status, secure: window.isSecureContext !== false });
    } catch {
      if (controller.signal.aborted) return;
      statusRequestedRef.current = false;
    }
  }, []);

  /** Speaks a reply: asks the server for the sound, then plays it on the one audio element. */
  const speakReply = useCallback(
    async (raw: string) => {
      const text = clampSpeechText(raw);
      // Not while the person is talking or a recording is being read.
      if (text === "" || phaseRef.current !== "idle") return;
      epochRef.current += 1;
      const epoch = epochRef.current;
      const stillMine = () => epochRef.current === epoch;
      const controller = new AbortController();
      requestRef.current = controller;
      setError(null);
      setPhase("speaking");
      try {
        const blob = await speakAudio({ text, language: voiceLanguage(latest.current.locale) }, controller.signal);
        if (!stillMine()) return;
        requestRef.current = null;
        if (!audioRef.current) audioRef.current = new Audio();
        const audio = audioRef.current;
        const url = URL.createObjectURL(blob);
        audioUrlRef.current = url;
        audio.onended = () => {
          if (!stillMine()) return;
          releasePlayback();
          setPhase("idle");
        };
        audio.onerror = () => {
          if (!stillMine()) return;
          releasePlayback();
          setPhase("idle");
          setError("generic");
        };
        audio.src = url;
        await audio.play();
      } catch (e) {
        if (!stillMine() || controller.signal.aborted) return;
        requestRef.current = null;
        releasePlayback();
        setPhase("idle");
        // Playing was interrupted by someone stopping it: not a failure.
        if (typeof DOMException !== "undefined" && e instanceof DOMException && e.name === "AbortError") return;
        setError(errorFor(e));
      }
    },
    [releasePlayback, setPhase],
  );

  // The status is asked for when the sheet opens (once). Closing the sheet stops everything.
  useEffect(() => {
    if (!open) return;
    if (!statusRequestedRef.current) {
      statusRequestedRef.current = true;
      void loadStatus();
    }
    return () => stopAll();
  }, [open, loadStatus, stopAll]);

  // Unmounting (another person signing in, the page going away) lets go of everything too.
  useEffect(
    () => () => {
      statusAbortRef.current?.abort();
      statusRequestedRef.current = false;
      teardown();
    },
    [teardown],
  );

  const available = info !== null && info.status.enabled && info.status.speechToText;
  const canSpeak = available && info.status.textToSpeech;

  // Speaks each new reply from the assistant, when asked to. Replies that arrive while the sheet is closed, or that were
  // there already, are never spoken. Errors and proposal cards are not spoken: only the words of a reply.
  useEffect(() => {
    const last = messages.length > 0 ? messages[messages.length - 1] : null;
    if (!open) {
      handledRef.current = last ? last.id : null;
      return;
    }
    if (!last || last.id === handledRef.current) return;
    const speakable = last.role === "assistant" && !last.error && last.text.trim() !== "";
    if (!speakable || !speakReplies || !canSpeak) {
      handledRef.current = last.id;
      return;
    }
    // A tick later, so that nothing is started in the middle of a render pass.
    const timer = window.setTimeout(() => {
      handledRef.current = last.id;
      void speakReply(last.text);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [messages, open, speakReplies, canSpeak, speakReply]);

  // ---- Everything below is only ever called from event handlers and timers, never while rendering. ----

  /** Ends listening with a failure: lets go of everything and shows the fixed line for it. */
  function fail(code: VoiceErrorCode) {
    releaseMic();
    setPhase("idle");
    setLevel(0);
    setError(code);
  }

  /** Delivers what was heard: sent as an ordinary message, or put in the text box when sending is manual (or the assistant is busy). */
  function deliver(text: string) {
    const { autoSend: automatic, busy: working, send, onDraft } = latest.current;
    if (automatic && !working) {
      void send(text, true);
    } else {
      onDraft(text);
      setDrafted((count) => count + 1);
    }
  }

  async function transcribeAndDeliver(blob: Blob, extension: string, epoch: number) {
    const controller = new AbortController();
    requestRef.current = controller;
    try {
      const raw = await transcribeAudio(blob, `speech.${extension}`, voiceLanguage(latest.current.locale), controller.signal);
      if (epochRef.current !== epoch) return;
      requestRef.current = null;
      setPhase("idle");
      const text = raw.trim().slice(0, MAX_MESSAGE_CHARS).trim();
      if (text === "") {
        setNotHeard(true);
        return;
      }
      deliver(text);
    } catch (e) {
      if (epochRef.current !== epoch) return;
      requestRef.current = null;
      setPhase("idle");
      setError(errorFor(e));
    }
  }

  /** Stops recording and sends what was said for transcription; a recording that is too short or has no speech is dropped silently. */
  function finishListening() {
    const mic = micRef.current;
    if (!mic || phaseRef.current !== "listening") return;
    const epoch = epochRef.current;
    const durationMs = performance.now() - mic.startedAt;
    // Without a working analyser nothing is known about the speech, so a long enough recording is given the benefit of the doubt.
    const analysing = contextRef.current?.state === "running";
    const heardSpeech = mic.detector.heardSpeech() || !analysing;
    if (shouldDiscard({ durationMs, heardSpeech })) {
      cancelActivity();
      return;
    }
    if (mic.timer !== null) {
      window.clearInterval(mic.timer);
      mic.timer = null;
    }
    setPhase("transcribing");
    setLevel(0);
    const { recorder, chunks } = mic;
    recorder.onstop = () => {
      if (epochRef.current !== epoch) return;
      const type = baseMimeType(recorder.mimeType || mic.mimeType);
      const extension = extensionFor(recorder.mimeType) ?? mic.extension;
      releaseMic();
      const blob = new Blob(chunks, { type });
      if (blob.size === 0) {
        setPhase("idle");
        return;
      }
      void transcribeAndDeliver(blob, extension, epoch);
    };
    try {
      recorder.stop();
    } catch {
      cancelActivity();
    }
  }

  async function startListening() {
    const phase = phaseRef.current;
    if ((phase !== "idle" && phase !== "speaking") || latest.current.busy) return;
    // Pressing the microphone while the assistant is talking stops it first, then listens.
    cancelActivity();
    unlockAudio();
    setError(null);
    setNotHeard(false);
    const epoch = epochRef.current;
    const stale = () => epochRef.current !== epoch;
    setPhase("starting");

    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== "function") {
      // A plain http page that is not localhost has no navigator.mediaDevices at all.
      fail(voiceErrorForMic(describeMicError(undefined, { mediaDevicesAvailable: false, secureContext: window.isSecureContext })));
      return;
    }
    if (typeof MediaRecorder === "undefined") {
      fail("unsupported");
      return;
    }

    const context = createAudioContext();
    contextRef.current = context;
    if (context) context.resume().catch(() => undefined);

    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (e) {
      if (stale()) return;
      fail(voiceErrorForMic(describeMicError(e)));
      return;
    }
    if (stale()) {
      // Cancelled while the browser was asking: the microphone must not stay on.
      for (const track of stream.getTracks()) track.stop();
      return;
    }

    let built: ReturnType<typeof buildRecorder>;
    try {
      built = buildRecorder(stream);
    } catch (e) {
      for (const track of stream.getTracks()) track.stop();
      fail(voiceErrorForMic(describeMicError(e)));
      return;
    }
    if (!built) {
      for (const track of stream.getTracks()) track.stop();
      fail("unsupported");
      return;
    }

    const { recorder, mimeType, extension } = built;
    const chunks: Blob[] = [];
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunks.push(event.data);
    };
    recorder.onerror = () => {
      if (stale()) return;
      cancelActivity();
      setError("generic");
    };
    const mic: Mic = {
      stream,
      recorder,
      chunks,
      mimeType,
      extension,
      detector: createSilenceDetector(),
      startedAt: performance.now(),
      timer: null,
    };
    micRef.current = mic;
    try {
      recorder.start();
    } catch (e) {
      fail(voiceErrorForMic(describeMicError(e)));
      return;
    }

    const analyser = connectAnalyser(context, stream);
    const buffer = new Float32Array(analyser ? analyser.fftSize : 0);
    mic.timer = window.setInterval(() => {
      if (stale() || micRef.current !== mic) return;
      const now = performance.now();
      let verdict: SilenceVerdict = "waiting";
      if (analyser) {
        analyser.getFloatTimeDomainData(buffer);
        const rms = rmsOf(buffer);
        // In steps of a tenth, so the screen is not redrawn twenty times a second for nothing.
        setLevel(Math.round(meterLevel(rms) * 10) / 10);
        verdict = mic.detector.feed(rms, now);
      }
      // Quiet after speech ends it; so does the hard limit.
      if (verdict === "ended" || now - mic.startedAt >= MAX_RECORDING_MS) finishListening();
    }, TICK_MS);
    setPhase("listening");
  }

  function onPointerDown(event: ReactPointerEvent<HTMLButtonElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const phase = phaseRef.current;
    // Only a press that starts the listening can be a "hold to talk". A press while listening is a tap to stop (see onClick).
    if ((phase !== "idle" && phase !== "speaking") || latest.current.busy) {
      pressRef.current = null;
      return;
    }
    pressRef.current = { at: performance.now() };
    try {
      // So the release is seen even when the finger slides off the button.
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Not every pointer can be captured; the release is still seen on the button.
    }
    void startListening();
  }

  function onPointerUp() {
    const press = pressRef.current;
    pressRef.current = null;
    if (!press) return;
    // The click that follows this release belongs to the press that started the listening: it must not stop it again.
    suppressClickUntilRef.current = performance.now() + 600;
    // On iPhone the audio context may only wake up from a tap or its release.
    contextRef.current?.resume().catch(() => undefined);
    if (isHold(performance.now() - press.at) && micRef.current && phaseRef.current === "listening") {
      // Held and released: the person has finished talking.
      finishListening();
    }
    // A short press leaves the recording running: it is the tap mode, stopped by silence or by tapping again.
  }

  function onPointerCancel() {
    const press = pressRef.current;
    pressRef.current = null;
    if (!press) return;
    suppressClickUntilRef.current = performance.now() + 600;
    // The browser took the press away (a scroll, a call): a long one is dropped rather than sent half said.
    if (isHold(performance.now() - press.at)) cancelActivity();
  }

  function onClick() {
    if (performance.now() < suppressClickUntilRef.current) {
      suppressClickUntilRef.current = 0;
      return;
    }
    switch (phaseRef.current) {
      case "starting":
      case "transcribing":
        // Cancel.
        cancelActivity();
        break;
      case "listening":
        finishListening();
        break;
      default:
        if (!latest.current.busy) void startListening();
    }
  }

  function stopSpeaking() {
    if (phaseRef.current === "speaking") cancelActivity();
  }

  function setAutoSend(on: boolean) {
    setAutoSendState(on);
    writeFlag(AUTOSEND_KEY, on);
  }

  function setSpeakReplies(on: boolean) {
    setSpeakRepliesState(on);
    writeFlag(SPEAK_KEY, on);
    // The switch is a tap: the moment to get the player ready. Switching off silences what is being said.
    if (on) unlockAudio();
    else stopSpeaking();
  }

  const publicState: VoiceState = state !== "idle" ? state : busy ? "thinking" : "idle";

  return {
    available,
    canSpeak,
    secure: info ? info.secure : true,
    state: publicState,
    level,
    error,
    notHeard,
    drafted,
    autoSend,
    speakReplies,
    setAutoSend,
    setSpeakReplies,
    micProps: {
      onPointerDown,
      onPointerUp,
      onPointerCancel,
      onClick,
      onContextMenu: (event) => event.preventDefault(),
    },
    stopSpeaking,
    unlockAudio,
    dismissNotice: () => {
      setError(null);
      setNotHeard(false);
    },
    stopAll,
  };
}
