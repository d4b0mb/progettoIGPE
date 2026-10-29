/**
 * A tiny publish/subscribe channel for "the signed-in person's session ended".
 *
 * The API client (client.ts) knows when a refresh fails; the session provider
 * (session.tsx) owns what the screen does about it. The provider imports the client,
 * so the client cannot import the provider without a cycle. This module sits between
 * the two and imports nothing.
 */
type Listener = () => void;

const listeners = new Set<Listener>();

/** Calls `listener` whenever a signed-in session ends without the person asking for it. Returns the unsubscribe function. */
export function onSessionEnded(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Tells every subscriber that the session ended. One failing subscriber never stops the others or the caller. */
export function announceSessionEnded(): void {
  for (const listener of [...listeners]) {
    try {
      listener();
    } catch (error) {
      // Surface it as an uncaught error without breaking the request that found out.
      queueMicrotask(() => {
        throw error;
      });
    }
  }
}
