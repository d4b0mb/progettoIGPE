"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { components } from "./schema";
import { api, refreshSession } from "./client";
import { toApiError } from "./errors";
import { onSessionEnded } from "./sessionEvents";
import { token } from "./token";

export type User = components["schemas"]["UserView"];
type TokenResponse = components["schemas"]["TokenResponse"];

type SessionState =
  | { status: "loading" }
  /** `reason: "expired"` means the person was signed in and the server ended the session (as opposed to a visitor or a sign-out). */
  | { status: "anonymous"; reason?: "expired" }
  | { status: "authenticated"; user: User };

interface SessionApi {
  state: SessionState;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (input: components["schemas"]["SignupRequest"]) => Promise<void>;
  signOut: () => Promise<void>;
  /** Changes the signed-in person's password. Their other sessions end; this one carries on. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
}

const SessionContext = createContext<SessionApi | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: "loading" });

  const accept = useCallback((res: TokenResponse) => {
    token.set(res.accessToken);
    setState({ status: "authenticated", user: res.user });
  }, []);

  // On first load, try to resume a session from the refresh cookie.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const resumed = await refreshSession();
      if (cancelled) return;
      if (!resumed) {
        setState({ status: "anonymous" });
        return;
      }
      const { data } = await api.GET("/api/v1/me");
      if (cancelled) return;
      setState(data ? { status: "authenticated", user: data } : { status: "anonymous" });
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // The one place that reacts to a session the server ended (refresh failed for a signed-in
  // person): they become anonymous, which unmounts everything of theirs, and the layouts send
  // them to the sign-in page with the reason. The state check makes repeats harmless.
  useEffect(
    () =>
      onSessionEnded(() => {
        token.set(null);
        setState((current) => (current.status === "authenticated" ? { status: "anonymous", reason: "expired" } : current));
      }),
    [],
  );

  const signIn = useCallback(
    async (email: string, password: string) => {
      const { data, error, response } = await api.POST("/api/v1/auth/login", { body: { email, password } });
      if (!data) throw toApiError(response.status, error);
      accept(data);
    },
    [accept],
  );

  const signUp = useCallback(
    async (input: components["schemas"]["SignupRequest"]) => {
      const { data, error, response } = await api.POST("/api/v1/auth/signup", { body: input });
      if (!data) throw toApiError(response.status, error);
      accept(data);
    },
    [accept],
  );

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      const { data, error, response } = await api.POST("/api/v1/me/password", { body: { currentPassword, newPassword } });
      if (!data) throw toApiError(response.status, error);
      accept(data);
    },
    [accept],
  );

  const signOut = useCallback(async () => {
    await api.POST("/api/v1/auth/logout");
    token.set(null);
    setState({ status: "anonymous" });
  }, []);

  const value = useMemo(() => ({ state, signIn, signUp, signOut, changePassword }), [state, signIn, signUp, signOut, changePassword]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionApi {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession must be used inside <SessionProvider>");
  return ctx;
}
