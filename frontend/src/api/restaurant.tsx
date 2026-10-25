"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api } from "./client";
import { unwrap } from "./call";
import type { components } from "./schema";

export type RestaurantView = components["schemas"]["RestaurantView"];
export type Theme = RestaurantView["theme"];

const STORAGE_KEY = "rm_theme";

type Value = { name: string | null; theme: Theme; setTheme: (theme: Theme) => Promise<void> };
const RestaurantContext = createContext<Value>({ name: null, theme: "CLASSIC", setTheme: async () => {} });

function applyTheme(theme: Theme) {
  const value = theme.toLowerCase();
  document.documentElement.dataset.theme = value;
  try {
    localStorage.setItem(STORAGE_KEY, value);
  } catch {
    // Private windows may refuse storage; the theme still applies for this visit.
  }
}

/** The signed-in restaurant's name and look. The look is remembered on this device so the next visit starts in it. */
export function RestaurantProvider({ children }: { children: React.ReactNode }) {
  const [restaurant, setRestaurant] = useState<RestaurantView | null>(null);

  useEffect(() => {
    let cancelled = false;
    unwrap(api.GET("/api/v1/restaurant"))
      .then((r) => {
        if (cancelled) return;
        setRestaurant(r);
        applyTheme(r.theme);
      })
      .catch(() => {
        // The app works without it: it just keeps the default look.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const setTheme = useCallback(async (theme: Theme) => {
    const updated = await unwrap(api.PUT("/api/v1/restaurant/theme", { body: { theme } }));
    setRestaurant(updated);
    applyTheme(updated.theme);
  }, []);

  const value = useMemo(() => ({ name: restaurant?.name ?? null, theme: restaurant?.theme ?? "CLASSIC", setTheme }), [restaurant, setTheme]);
  return <RestaurantContext.Provider value={value}>{children}</RestaurantContext.Provider>;
}

export const useRestaurant = () => useContext(RestaurantContext);

/** Runs before the page is painted so a returning visitor never sees the default look flash. */
export const EARLY_THEME_SCRIPT = `try{var t=localStorage.getItem("${STORAGE_KEY}");if(t==="duodecim")document.documentElement.dataset.theme=t}catch(e){}`;
