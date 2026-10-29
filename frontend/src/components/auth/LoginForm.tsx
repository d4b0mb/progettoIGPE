"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { describeAuthError, type FormProblem } from "./errors";

/** `sessionEnded` explains why the person is here: they were signed in and the server ended their session. */
export function LoginForm({ sessionEnded = false }: { sessionEnded?: boolean }) {
  const t = useTranslations("auth");
  const tc = useTranslations("common");
  const { signIn } = useSession();
  const router = useRouter();
  const [problem, setProblem] = useState<FormProblem | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setProblem(null);
    try {
      await signIn(String(form.get("email")), String(form.get("password")));
      router.replace("/");
    } catch (error) {
      setProblem(describeAuthError(error, t, tc("somethingWentWrong")));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex w-full max-w-md flex-col gap-5 rounded-2xl border border-line bg-surface p-6 sm:p-9">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("signIn")}</h1>
        <p className="text-sm text-muted">{t("signInSubtitle")}</p>
      </div>
      {sessionEnded ? (
        <p role="status" className="rounded-lg bg-warn-soft px-3 py-2 text-sm font-semibold text-warn-ink">
          {t("sessionEnded")}
        </p>
      ) : null}
      <Field label={t("email")} name="email" type="email" autoComplete="username" required error={problem?.fields.email} />
      <Field label={t("password")} name="password" type="password" autoComplete="current-password" required error={problem?.fields.password} />
      {problem?.message ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {problem.message}
        </p>
      ) : null}
      <Button type="submit" disabled={busy} className="min-h-12">
        {t("submitSignIn")}
      </Button>
      <Link href="/recover" className="-mt-2 text-center text-sm font-bold text-primary underline">
        {t("forgotPassword")}
      </Link>
      <p className="text-center text-sm text-muted">
        {t("noAccount")}{" "}
        <Link href="/signup" className="font-bold text-primary underline">
          {t("createAccount")}
        </Link>
      </p>
    </form>
  );
}
