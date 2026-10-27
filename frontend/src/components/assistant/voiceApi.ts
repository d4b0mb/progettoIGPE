import { authedFetch } from "@/api/client";
import { ApiError, toApiError } from "@/api/errors";
import { baseMimeType, type VoiceLanguage } from "./voice";

/*
 * The three voice endpoints, called by hand with authedFetch (the login token, and one retry after a token refresh).
 * They are not in the generated schema yet; once they are, this file can move to the generated client (`api` in
 * "@/api/client") and the types below can come from "@/api/schema".
 */

export interface VoiceStatus {
  /** Voice is switched on for this server. */
  enabled: boolean;
  /** Speech can be turned into text (the microphone). */
  speechToText: boolean;
  /** Text can be turned into speech (the speaker). */
  textToSpeech: boolean;
}

export interface SpeakRequest {
  /** At most 600 characters. */
  text: string;
  language?: VoiceLanguage;
  voice?: string;
}

/** The address on this site, absolute because a Request cannot be made from a bare path everywhere. */
function urlFor(path: string): string {
  return new URL(path, window.location.origin).toString();
}

/** Turns a failed answer into an ApiError (RFC 9457 body when there is one). */
async function failureOf(response: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    // Not JSON (a proxy's error page, for instance): the status alone is enough.
  }
  return toApiError(response.status, body);
}

/** One call. Throws an ApiError for any answer that is not a success; a network failure throws the fetch's own error. */
async function call(path: string, init: RequestInit): Promise<Response> {
  const response = await authedFetch(new Request(urlFor(path), init));
  if (!response.ok) throw await failureOf(response);
  return response;
}

/** What the server offers. Anything unexpected in the answer counts as "off". */
export async function fetchVoiceStatus(signal?: AbortSignal): Promise<VoiceStatus> {
  const response = await call("/api/v1/voice/status", { method: "GET", signal });
  const body = (await response.json()) as Partial<Record<keyof VoiceStatus, unknown>> | null;
  return {
    enabled: body?.enabled === true,
    speechToText: body?.speechToText === true,
    textToSpeech: body?.textToSpeech === true,
  };
}

/**
 * Sends a recording to be turned into text. The file name carries an extension that matches the type, and the type
 * goes without its codec parameters ("audio/webm", not "audio/webm;codecs=opus"), which is what servers expect.
 */
export async function transcribeAudio(
  audio: Blob,
  filename: string,
  language: VoiceLanguage | undefined,
  signal?: AbortSignal,
): Promise<string> {
  const form = new FormData();
  form.append("audio", new Blob([audio], { type: baseMimeType(audio.type) }), filename);
  if (language) form.append("language", language);
  // No Content-Type header: the browser adds multipart/form-data with its boundary. No Accept header either, so an
  // error body in any JSON flavour can still come back.
  const response = await call("/api/v1/voice/transcribe", { method: "POST", body: form, signal });
  const body = (await response.json()) as { text?: unknown } | null;
  return typeof body?.text === "string" ? body.text : "";
}

/** Asks for a text to be spoken and returns the audio. */
export async function speakAudio(request: SpeakRequest, signal?: AbortSignal): Promise<Blob> {
  const response = await call("/api/v1/voice/speak", {
    method: "POST",
    body: JSON.stringify(request),
    // No Accept header: with a narrow one, some servers cannot send their JSON error body back.
    headers: { "Content-Type": "application/json" },
    signal,
  });
  const blob = await response.blob();
  // An empty answer is not a sound the player could use.
  if (blob.size === 0) throw new ApiError(502, "Empty audio");
  // The player needs a type; use the answer's own Content-Type when the blob came without one.
  return blob.type !== "" ? blob : new Blob([blob], { type: response.headers.get("Content-Type") ?? "audio/mpeg" });
}
