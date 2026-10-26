"use client";

import { useEffect, useRef } from "react";

const EVENT = "ristomanager:reservations-changed";

/**
 * Says that reservations were created or cancelled somewhere other than the screen that shows them (the assistant's
 * Confirm). Screens that list reservations listen with useOnReservationsChanged and load again.
 */
export function announceReservationsChanged(): void {
  window.dispatchEvent(new Event(EVENT));
}

/** Runs `onChange` whenever reservations were changed elsewhere (see announceReservationsChanged). */
export function useOnReservationsChanged(onChange: () => void): void {
  // The latest callback, so the listener is added once and never goes stale.
  const latest = useRef(onChange);
  useEffect(() => {
    latest.current = onChange;
  });
  useEffect(() => {
    const listener = () => latest.current();
    window.addEventListener(EVENT, listener);
    return () => window.removeEventListener(EVENT, listener);
  }, []);
}
