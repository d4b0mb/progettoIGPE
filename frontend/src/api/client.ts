import createClient from "openapi-fetch";
import type { paths } from "./schema";
import { announceSessionEnded } from "./sessionEvents";
import { token } from "./token";

const AUTH_PATHS = ["/api/v1/auth/"];

function isAuthRequest(url: string): boolean {
  const path = new URL(url, "http://local").pathname;
  return AUTH_PATHS.some((prefix) => path.startsWith(prefix));
}

let refreshInFlight: Promise<boolean> | null = null;

/**
 * Exchanges the refresh cookie for a new access token. Returns false if the user
 * is no longer signed in.
 *
 * Refresh tokens rotate: each one works once, and using an old one is treated as
 * theft and signs the user out everywhere. So two refreshes must never run at the
 * same time. Inside one tab they share a single request; across tabs the Web Locks
 * API makes them take turns, and the second tab then simply uses the new cookie.
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= withLock(doRefresh).finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function withLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return (await navigator.locks.request("ristomanager-refresh", task)) as T;
  }
  return task();
}

async function doRefresh(): Promise<boolean> {
  // Only someone who holds a token was signed in. A visitor's first-load attempt, a refresh after
  // the session already ended, or the login page's own attempt has no token, so it stays silent.
  // Clearing the token below is also what keeps a burst of failing requests down to one event:
  // later refreshes of the same burst find no token.
  const wasSignedIn = token.get() !== null;
  const res = await fetch("/api/v1/auth/refresh", { method: "POST", credentials: "same-origin" });
  if (!res.ok) {
    token.set(null);
    if (wasSignedIn) announceSessionEnded();
    return false;
  }
  const body = (await res.json()) as { accessToken: string };
  token.set(body.accessToken);
  return true;
}

/**
 * fetch with the login token attached. If the server says the token has expired,
 * refresh once and repeat the request; if that fails the caller sees the 401, and the
 * session provider is told (see sessionEvents.ts) that the person is signed out.
 */
export async function authedFetch(request: Request): Promise<Response> {
  const retry = request.clone();
  const current = token.get();
  if (current) {
    request.headers.set("Authorization", `Bearer ${current}`);
  }
  const response = await fetch(request);
  if (response.status !== 401 || !current || isAuthRequest(request.url)) {
    return response;
  }
  if (!(await refreshSession())) {
    return response;
  }
  retry.headers.set("Authorization", `Bearer ${token.get()}`);
  return fetch(retry);
}

/** The typed API client. Paths, parameters and responses come from the backend's OpenAPI contract. */
export const api = createClient<paths>({
  baseUrl: typeof window === "undefined" ? "http://localhost" : window.location.origin,
  fetch: authedFetch,
});
