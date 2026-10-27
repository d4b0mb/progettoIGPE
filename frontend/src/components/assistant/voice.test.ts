import { describe, expect, it } from "vitest";
import {
  HOLD_MS,
  MAX_SPEECH_CHARS,
  MIN_RECORDING_MS,
  baseMimeType,
  buildSilentWav,
  clampSpeechText,
  createSilenceDetector,
  describeMicError,
  extensionFor,
  isHold,
  meterLevel,
  parseFlag,
  pickRecordingType,
  rmsOf,
  shouldDiscard,
  voiceApiErrorFor,
  voiceErrorForMic,
  voiceLanguage,
} from "./voice";

const supportsOnly = (...types: string[]) => (mime: string) => types.includes(mime);

describe("pickRecordingType", () => {
  it("prefers webm with opus when everything is supported", () => {
    expect(pickRecordingType(() => true)).toEqual({ mimeType: "audio/webm;codecs=opus", extension: "webm" });
  });

  it("falls back in the agreed order", () => {
    expect(pickRecordingType(supportsOnly("audio/webm", "audio/ogg;codecs=opus", "audio/mp4"))).toEqual({
      mimeType: "audio/webm",
      extension: "webm",
    });
    expect(pickRecordingType(supportsOnly("audio/ogg;codecs=opus", "audio/mp4"))).toEqual({
      mimeType: "audio/ogg;codecs=opus",
      extension: "ogg",
    });
    expect(pickRecordingType(supportsOnly("audio/mp4", "audio/mp4;codecs=mp4a.40.2"))).toEqual({
      mimeType: "audio/mp4",
      extension: "mp4",
    });
  });

  it("finds mp4 as Safari and iPhone offer it", () => {
    expect(pickRecordingType(supportsOnly("audio/mp4"))).toEqual({ mimeType: "audio/mp4", extension: "mp4" });
  });

  it("tries the mp4 with an explicit AAC codec last", () => {
    expect(pickRecordingType(supportsOnly("audio/mp4;codecs=mp4a.40.2"))).toEqual({
      mimeType: "audio/mp4;codecs=mp4a.40.2",
      extension: "mp4",
    });
  });

  it("is null when nothing is supported", () => {
    expect(pickRecordingType(() => false)).toBeNull();
    expect(pickRecordingType(supportsOnly("audio/flac", "video/webm"))).toBeNull();
  });

  it("counts a check that throws as not supported and goes on", () => {
    const flaky = (mime: string) => {
      if (mime === "audio/webm;codecs=opus") throw new Error("boom");
      return mime === "audio/webm";
    };
    expect(pickRecordingType(flaky)).toEqual({ mimeType: "audio/webm", extension: "webm" });
  });

  it("returns a copy, so callers cannot change the table", () => {
    const first = pickRecordingType(() => true)!;
    first.extension = "xxx";
    expect(pickRecordingType(() => true)!.extension).toBe("webm");
  });
});

describe("baseMimeType and extensionFor", () => {
  it("drops the parameters and lower-cases", () => {
    expect(baseMimeType("audio/webm;codecs=opus")).toBe("audio/webm");
    expect(baseMimeType(" Audio/MP4 ; codecs=mp4a.40.2")).toBe("audio/mp4");
    expect(baseMimeType("")).toBe("");
  });

  it("gives the extension that matches a type", () => {
    expect(extensionFor("audio/webm;codecs=opus")).toBe("webm");
    expect(extensionFor("audio/ogg;codecs=opus")).toBe("ogg");
    expect(extensionFor("audio/mp4")).toBe("mp4");
    expect(extensionFor("audio/mpeg")).toBe("mp3");
    expect(extensionFor("audio/wav")).toBe("wav");
  });

  it("says nothing for a type it does not know", () => {
    expect(extensionFor("")).toBeNull();
    expect(extensionFor("video/webm")).toBeNull();
    expect(extensionFor("audio/flac")).toBeNull();
  });
});

describe("rmsOf", () => {
  it("is 0 for silence and for no samples", () => {
    expect(rmsOf(new Float32Array(256))).toBe(0);
    expect(rmsOf(new Float32Array(0))).toBe(0);
  });

  it("is the amplitude of a constant signal, whatever its sign", () => {
    expect(rmsOf(new Float32Array([0.5, 0.5, 0.5, 0.5]))).toBeCloseTo(0.5, 6);
    expect(rmsOf(new Float32Array([-0.5, -0.5]))).toBeCloseTo(0.5, 6);
  });

  it("is the amplitude over the square root of two for a sine wave", () => {
    const samples = new Float32Array(1000);
    for (let i = 0; i < samples.length; i++) samples[i] = 0.8 * Math.sin((2 * Math.PI * i * 10) / samples.length);
    expect(rmsOf(samples)).toBeCloseTo(0.8 / Math.SQRT2, 3);
  });

  it("is 1 for a full-scale square wave", () => {
    expect(rmsOf(new Float32Array([1, -1, 1, -1]))).toBeCloseTo(1, 6);
  });

  it("never returns NaN", () => {
    expect(rmsOf(new Float32Array([NaN, 0.5]))).toBe(0);
  });
});

describe("meterLevel", () => {
  it("is 0 for silence and for nonsense", () => {
    expect(meterLevel(0)).toBe(0);
    expect(meterLevel(-1)).toBe(0);
    expect(meterLevel(NaN)).toBe(0);
  });

  it("rises with loudness and never leaves 0..1", () => {
    expect(meterLevel(0.01)).toBeLessThan(meterLevel(0.1));
    expect(meterLevel(0.1)).toBeLessThan(meterLevel(0.3));
    expect(meterLevel(1)).toBe(1);
    expect(meterLevel(50)).toBe(1);
  });
});

describe("createSilenceDetector", () => {
  const options = { threshold: 0.05, silenceMs: 1000, minSpeechMs: 300 };
  const LOUD = 0.2;
  const QUIET = 0.001;

  /** Feeds the same loudness every 50 ms from `from` to `to` (both included); returns the last answer. */
  function run(detector: ReturnType<typeof createSilenceDetector>, rms: number, from: number, to: number) {
    let last = detector.feed(rms, from);
    for (let t = from + 50; t <= to; t += 50) last = detector.feed(rms, t);
    return last;
  }

  it("waits while nobody has spoken, however long it is quiet", () => {
    const detector = createSilenceDetector(options);
    expect(run(detector, QUIET, 0, 60_000)).toBe("waiting");
    expect(detector.heardSpeech()).toBe(false);
  });

  it("says speaking as soon as it is loud", () => {
    const detector = createSilenceDetector(options);
    expect(detector.feed(QUIET, 0)).toBe("waiting");
    expect(detector.feed(LOUD, 50)).toBe("speaking");
    expect(detector.heardSpeech()).toBe(true);
  });

  it("counts exactly the threshold as speech", () => {
    const detector = createSilenceDetector(options);
    expect(detector.feed(0.05, 0)).toBe("speaking");
  });

  it("does not end during a short pause", () => {
    const detector = createSilenceDetector(options);
    run(detector, LOUD, 0, 500);
    expect(detector.feed(QUIET, 600)).toBe("speaking");
    expect(detector.feed(QUIET, 1499)).toBe("speaking"); // 999 ms after the last loud moment
  });

  it("ends after speech followed by silenceMs of silence", () => {
    const detector = createSilenceDetector(options);
    run(detector, LOUD, 0, 500);
    expect(detector.feed(QUIET, 1499)).toBe("speaking");
    expect(detector.feed(QUIET, 1500)).toBe("ended");
  });

  it("stays ended afterwards, even if it gets loud again", () => {
    const detector = createSilenceDetector(options);
    run(detector, LOUD, 0, 500);
    expect(run(detector, QUIET, 550, 1500)).toBe("ended");
    expect(detector.feed(LOUD, 1600)).toBe("ended");
    expect(detector.feed(QUIET, 9000)).toBe("ended");
  });

  it("starts the silence over when speech resumes after a pause", () => {
    const detector = createSilenceDetector(options);
    run(detector, LOUD, 0, 500);
    run(detector, QUIET, 550, 1200); // a pause of 700 ms
    expect(detector.feed(LOUD, 1250)).toBe("speaking");
    expect(detector.feed(QUIET, 2200)).toBe("speaking"); // 950 ms after 1250
    expect(detector.feed(QUIET, 2250)).toBe("ended");
  });

  it("never ends before minSpeechMs of speech: a short noise is forgotten and the wait starts again", () => {
    const detector = createSilenceDetector(options);
    expect(detector.feed(LOUD, 0)).toBe("speaking");
    expect(detector.feed(LOUD, 100)).toBe("speaking"); // 100 ms of noise, under the 300 ms minimum
    expect(detector.feed(QUIET, 1099)).toBe("speaking");
    expect(detector.feed(QUIET, 1100)).toBe("waiting"); // silence long enough, but not a sentence
    expect(detector.feed(QUIET, 30_000)).toBe("waiting");
    // It still remembers that something loud happened.
    expect(detector.heardSpeech()).toBe(true);
  });

  it("then ends normally when real speech follows the forgotten noise", () => {
    const detector = createSilenceDetector(options);
    detector.feed(LOUD, 0);
    detector.feed(QUIET, 1100); // forgotten
    run(detector, LOUD, 2000, 2400); // 400 ms of speech
    expect(detector.feed(QUIET, 3399)).toBe("speaking");
    expect(detector.feed(QUIET, 3400)).toBe("ended");
  });

  it("accepts speech of exactly minSpeechMs", () => {
    const detector = createSilenceDetector(options);
    detector.feed(LOUD, 0);
    detector.feed(LOUD, 300);
    expect(detector.feed(QUIET, 1300)).toBe("ended");
  });

  it("defaults to 1200 ms of silence", () => {
    const detector = createSilenceDetector({ threshold: 0.05, minSpeechMs: 100 });
    run(detector, LOUD, 0, 400);
    expect(detector.feed(QUIET, 1599)).toBe("speaking");
    expect(detector.feed(QUIET, 1600)).toBe("ended");
  });

  it("works with no options at all", () => {
    const detector = createSilenceDetector();
    expect(detector.feed(0, 0)).toBe("waiting");
    run(detector, 0.5, 100, 600);
    expect(detector.feed(0, 600 + 1199)).toBe("speaking");
    expect(detector.feed(0, 600 + 1200)).toBe("ended");
  });

  it("gives the same answers for the same inputs", () => {
    const timeline: [number, number][] = [];
    for (let t = 0; t <= 4000; t += 50) timeline.push([t >= 500 && t <= 1500 ? LOUD : QUIET, t]);
    const answers = () => {
      const detector = createSilenceDetector(options);
      return timeline.map(([rms, t]) => detector.feed(rms, t));
    };
    expect(answers()).toEqual(answers());
    expect(answers().at(-1)).toBe("ended");
    expect(answers()[0]).toBe("waiting");
  });
});

describe("shouldDiscard", () => {
  it("drops a recording shorter than the minimum", () => {
    expect(shouldDiscard({ durationMs: MIN_RECORDING_MS - 1, heardSpeech: true })).toBe(true);
    expect(shouldDiscard({ durationMs: 0, heardSpeech: true })).toBe(true);
  });

  it("keeps one that is long enough and has speech", () => {
    expect(shouldDiscard({ durationMs: MIN_RECORDING_MS, heardSpeech: true })).toBe(false);
    expect(shouldDiscard({ durationMs: 12_000, heardSpeech: true })).toBe(false);
  });

  it("drops a recording without speech, however long", () => {
    expect(shouldDiscard({ durationMs: 12_000, heardSpeech: false })).toBe(true);
  });
});

describe("isHold", () => {
  it("is a hold only for a press longer than 400 ms", () => {
    expect(isHold(HOLD_MS)).toBe(false);
    expect(isHold(HOLD_MS + 1)).toBe(true);
    expect(isHold(120)).toBe(false);
    expect(isHold(2000)).toBe(true);
  });
});

describe("voiceLanguage", () => {
  it("is Italian for the Italian locale in any form", () => {
    expect(voiceLanguage("it")).toBe("it");
    expect(voiceLanguage("it-IT")).toBe("it");
    expect(voiceLanguage("it_CH")).toBe("it");
    expect(voiceLanguage("IT")).toBe("it");
  });

  it("is English for English and for anything else", () => {
    expect(voiceLanguage("en")).toBe("en");
    expect(voiceLanguage("en-GB")).toBe("en");
    expect(voiceLanguage("de")).toBe("en");
    expect(voiceLanguage("")).toBe("en");
  });

  it("does not take a language that merely starts with it", () => {
    expect(voiceLanguage("ita")).toBe("en");
    expect(voiceLanguage("iu")).toBe("en");
  });
});

describe("describeMicError", () => {
  const named = (name: string) => Object.assign(new Error(name), { name });

  it("maps the names of the errors getUserMedia and MediaRecorder throw", () => {
    expect(describeMicError(named("NotAllowedError"))).toBe("denied");
    expect(describeMicError(named("PermissionDeniedError"))).toBe("denied");
    expect(describeMicError(named("NotFoundError"))).toBe("notFound");
    expect(describeMicError(named("OverconstrainedError"))).toBe("notFound");
    expect(describeMicError(named("NotReadableError"))).toBe("busy");
    expect(describeMicError(named("AbortError"))).toBe("busy");
    expect(describeMicError(named("SecurityError"))).toBe("insecure");
    expect(describeMicError(named("NotSupportedError"))).toBe("unsupported");
  });

  it("reads real DOMExceptions", () => {
    expect(describeMicError(new DOMException("blocked", "NotAllowedError"))).toBe("denied");
    expect(describeMicError(new DOMException("none", "NotFoundError"))).toBe("notFound");
    expect(describeMicError(new DOMException("in use", "NotReadableError"))).toBe("busy");
  });

  it("reads an error-like object that is not an Error", () => {
    expect(describeMicError({ name: "NotAllowedError" })).toBe("denied");
  });

  it("calls a missing navigator.mediaDevices insecure (a plain http page)", () => {
    expect(describeMicError(undefined, { mediaDevicesAvailable: false })).toBe("insecure");
    expect(describeMicError(null, { mediaDevicesAvailable: false, secureContext: false })).toBe("insecure");
  });

  it("calls a missing navigator.mediaDevices on a secure page an unsupported browser", () => {
    expect(describeMicError(undefined, { mediaDevicesAvailable: false, secureContext: true })).toBe("unsupported");
  });

  it("recognises the TypeError raised by reading getUserMedia of an undefined mediaDevices", () => {
    const error = new TypeError("Cannot read properties of undefined (reading 'getUserMedia')");
    expect(describeMicError(error)).toBe("insecure");
    expect(describeMicError(new TypeError("navigator.mediaDevices is undefined"))).toBe("insecure");
  });

  it("does not blame the connection for any other TypeError", () => {
    expect(describeMicError(new TypeError("x is not a function"))).toBe("unknown");
  });

  it("is unknown for anything it cannot name", () => {
    expect(describeMicError(undefined)).toBe("unknown");
    expect(describeMicError(null)).toBe("unknown");
    expect(describeMicError("NotAllowedError")).toBe("unknown");
    expect(describeMicError(42)).toBe("unknown");
    expect(describeMicError(new Error("strange"))).toBe("unknown");
    expect(describeMicError({ name: 7 })).toBe("unknown");
  });

  it("lets the context win over a name when the mediaDevices are missing", () => {
    expect(describeMicError(named("NotAllowedError"), { mediaDevicesAvailable: false })).toBe("insecure");
  });
});

describe("voiceApiErrorFor", () => {
  it("gives each failure of the voice endpoints its own fixed line", () => {
    expect(voiceApiErrorFor(413)).toBe("tooLarge");
    expect(voiceApiErrorFor(415)).toBe("unsupportedType");
    expect(voiceApiErrorFor(429)).toBe("tooManyRequests");
    expect(voiceApiErrorFor(502)).toBe("unavailable");
    expect(voiceApiErrorFor(503)).toBe("unavailable");
    expect(voiceApiErrorFor(504)).toBe("unavailable");
  });

  it("tells no answer at all from an answer that was an error", () => {
    expect(voiceApiErrorFor(null)).toBe("connectionLost");
    expect(voiceApiErrorFor(400)).toBe("generic");
    expect(voiceApiErrorFor(401)).toBe("generic");
    expect(voiceApiErrorFor(500)).toBe("generic");
  });
});

describe("voiceErrorForMic", () => {
  it("keeps the known codes and turns unknown into the general line", () => {
    expect(voiceErrorForMic("denied")).toBe("denied");
    expect(voiceErrorForMic("insecure")).toBe("insecure");
    expect(voiceErrorForMic("unknown")).toBe("generic");
  });
});

describe("clampSpeechText", () => {
  it("trims and collapses white space", () => {
    expect(clampSpeechText("  Ciao \n\n  a   tutti \t ")).toBe("Ciao a tutti");
    expect(clampSpeechText("   ")).toBe("");
    expect(clampSpeechText("")).toBe("");
  });

  it("removes emphasis marks", () => {
    expect(clampSpeechText("**Rossi** alle `21:00`")).toBe("Rossi alle 21:00");
    expect(clampSpeechText("Il tavolo _4_ e libero")).toBe("Il tavolo 4 e libero");
    expect(clampSpeechText("__importante__")).toBe("importante");
    expect(clampSpeechText("*uno* e **due** e ***tre***")).toBe("uno e due e tre");
  });

  it("keeps an underscore inside a word", () => {
    expect(clampSpeechText("il campo guest_name")).toBe("il campo guest_name");
  });

  it("leaves a short text alone, also at exactly the limit", () => {
    expect(clampSpeechText("Ok.")).toBe("Ok.");
    const exact = "a".repeat(MAX_SPEECH_CHARS);
    expect(clampSpeechText(exact)).toBe(exact);
  });

  it("measures the length after cleaning", () => {
    const text = `**${"a".repeat(MAX_SPEECH_CHARS)}**`;
    expect(clampSpeechText(text)).toBe("a".repeat(MAX_SPEECH_CHARS));
  });

  it("cuts a long text at the last sentence end under the limit", () => {
    const first = "Questa e la prima frase della risposta.";
    const second = "Questa e la seconda frase, un poco piu lunga della prima.";
    const text = `${first} ${second} ${"parola ".repeat(30)}`;
    const result = clampSpeechText(text, 100);
    expect(result).toBe(`${first} ${second}`);
    expect(result.length).toBeLessThanOrEqual(100);
  });

  it("counts a question mark, an exclamation mark and an ellipsis as sentence ends", () => {
    const base = "Va bene cosi";
    const filler = " e poi continua ancora a lungo senza fermarsi mai davvero".repeat(3);
    for (const mark of ["?", "!", "…"]) {
      const result = clampSpeechText(`${base}${mark} ${filler}`, 30);
      expect(result).toBe(`${base}${mark}`);
    }
  });

  it("does not cut inside a number such as 21.30", () => {
    const text = `Rossi alle 21.30 e Bianchi alle 22.00 e poi altri ancora ancora ancora`;
    const result = clampSpeechText(text, 25);
    expect(result).toBe(text.slice(0, 25));
  });

  it("cuts exactly at the limit when there is no sentence end to use", () => {
    const text = "parola ".repeat(200);
    const result = clampSpeechText(text, 50);
    expect(result).toBe(text.trim().slice(0, 50).trimEnd());
    expect(result.length).toBeLessThanOrEqual(50);
  });

  it("does not keep a sentence end that leaves almost nothing", () => {
    const text = `Ok. ${"a".repeat(300)}`;
    const result = clampSpeechText(text, 100);
    expect(result).toHaveLength(100);
    expect(result.startsWith("Ok. aaa")).toBe(true);
  });

  it("uses a sentence end that falls exactly on the limit", () => {
    const sentence = "a".repeat(49) + ".";
    const text = `${sentence} ${"b".repeat(80)}`;
    expect(clampSpeechText(text, 50)).toBe(sentence);
  });

  it("defaults to 600 characters", () => {
    const result = clampSpeechText("x".repeat(2000));
    expect(result).toHaveLength(600);
  });

  it("never leaves half of an emoji at the end", () => {
    const text = "a".repeat(9) + "\u{1F600}".repeat(10);
    const result = clampSpeechText(text, 10);
    expect(result).toBe("a".repeat(9));
  });

  it("returns nothing for a limit of zero", () => {
    expect(clampSpeechText("ciao", 0)).toBe("");
  });
});

describe("parseFlag", () => {
  it("reads what was saved", () => {
    expect(parseFlag("1", false)).toBe(true);
    expect(parseFlag("true", false)).toBe(true);
    expect(parseFlag("0", true)).toBe(false);
    expect(parseFlag("false", true)).toBe(false);
  });

  it("uses the default when nothing sensible was saved", () => {
    expect(parseFlag(null, true)).toBe(true);
    expect(parseFlag(undefined, false)).toBe(false);
    expect(parseFlag("", true)).toBe(true);
    expect(parseFlag("maybe", false)).toBe(false);
  });
});

describe("buildSilentWav", () => {
  it("is a valid 16-bit mono PCM header with one silent sample", () => {
    const bytes = buildSilentWav();
    const view = new DataView(bytes.buffer);
    const ascii = (offset: number, length: number) => String.fromCharCode(...bytes.slice(offset, offset + length));
    expect(bytes.length).toBe(46);
    expect(ascii(0, 4)).toBe("RIFF");
    expect(view.getUint32(4, true)).toBe(bytes.length - 8);
    expect(ascii(8, 4)).toBe("WAVE");
    expect(ascii(12, 4)).toBe("fmt ");
    expect(view.getUint16(20, true)).toBe(1);
    expect(view.getUint16(22, true)).toBe(1);
    expect(view.getUint32(24, true)).toBe(8000);
    expect(view.getUint16(34, true)).toBe(16);
    expect(ascii(36, 4)).toBe("data");
    expect(view.getUint32(40, true)).toBe(2);
    expect(view.getInt16(44, true)).toBe(0);
  });

  it("can be longer", () => {
    const bytes = buildSilentWav(800, 8000);
    expect(bytes.length).toBe(44 + 1600);
    expect(new DataView(bytes.buffer).getUint32(40, true)).toBe(1600);
  });
});
