"use client";

import { useCallback, useEffect, useState } from "react";

export interface Remote<T> {
  data?: T;
  error?: Error;
  loading: boolean;
  /** Fetch again (after a change). The old data stays on screen until the new data arrives. */
  reload: () => void;
}

/**
 * Loads data when a screen opens and again on demand. Pass a stable `load`
 * (wrap it in useCallback with what it depends on); when `load` changes, the
 * data is fetched again. Give the component a React `key` when the old data
 * must disappear immediately, for example when the day changes.
 */
export function useRemote<T>(load: () => Promise<T>): Remote<T> {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let cancelled = false;
    load().then(
      (data) => {
        if (!cancelled) setState({ data, loading: false });
      },
      (error: unknown) => {
        if (!cancelled) setState((s) => ({ data: s.data, error: error as Error, loading: false }));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [load, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { ...state, reload };
}
