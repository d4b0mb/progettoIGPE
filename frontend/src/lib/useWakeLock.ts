"use client";

import { useEffect } from "react";

/**
 * Keeps the screen on while a wall tablet shows the kitchen. Browsers only allow this on https or
 * localhost; on a plain-http network it does nothing and the tablet's own screen timeout applies.
 */
export function useWakeLock() {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null;
    let stopped = false;

    async function acquire() {
      if (stopped || document.visibilityState !== "visible" || !("wakeLock" in navigator)) return;
      try {
        lock = await navigator.wakeLock.request("screen");
      } catch {
        // Refused (low battery, not allowed): nothing to do.
      }
    }

    void acquire();
    document.addEventListener("visibilitychange", acquire);
    return () => {
      stopped = true;
      document.removeEventListener("visibilitychange", acquire);
      void lock?.release();
    };
  }, []);
}
