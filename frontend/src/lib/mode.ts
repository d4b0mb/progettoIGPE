"use client";

import { useSyncExternalStore } from "react";
import { MODE_STORAGE_KEY } from "./early-scripts";

export type Mode = "light" | "dark";


function subscribe(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-mode"] });
  return () => observer.disconnect();
}

const current = (): Mode => (document.documentElement.dataset.mode === "dark" ? "dark" : "light");

/** The current light/dark mode, and a function that switches it and remembers the choice on this device. */
export function useMode(): [Mode, () => void] {
  const mode = useSyncExternalStore(subscribe, current, () => "light" as Mode);
  function toggle() {
    const next: Mode = current() === "dark" ? "light" : "dark";
    document.documentElement.dataset.mode = next;
    try {
      localStorage.setItem(MODE_STORAGE_KEY, next);
    } catch {
      // Private windows may refuse storage; the choice still applies until the tab closes.
    }
  }
  return [mode, toggle];
}
