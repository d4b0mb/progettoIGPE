"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";

type Step = "email" | "answers" | "done";

/** An owner who forgot their password: email, then their own two questions, then a new password. */
export function RecoverFlow() {
  const t = useTranslations("recover");
  const tc = useTranslations("common");
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [questions, setQuestions] = useState<string[]>([]);
  const [a1, setA1] = useState("");
  const [a2, setA2] = useState("");
  const [password, setPassword] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function getQuestions(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await unwrap(api.POST("/api/v1/auth/recovery/questions", { body: { email } }));
      setQuestions(res.questions);
      setStep("answers");
    } catch {
      setError(tc("somethingWentWrong"));
    }
    setBusy(false);
  }

  async function reset(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      await expectOk(api.POST("/api/v1/auth/recovery/reset", { body: { email, answer1: a1, answer2: a2, newPassword: password } }));
      setStep("done");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setError(t("wrong"));
      else if (e instanceof ApiError && e.status === 429) setError(t("tooMany"));
      else if (e instanceof ApiError && Object.keys(e.fields).length > 0) setFields(e.fields);
      else setError(tc("somethingWentWrong"));
    }
    setBusy(false);
  }

  const frame = "flex w-full max-w-md flex-col gap-5 rounded-2xl border border-line bg-surface p-6 sm:p-9";
  const alert = error ? (
    <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
      {error}
    </p>
  ) : null;

  if (step === "done") {
    return (
      <div className={frame}>
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        <p role="status" className="rounded-lg bg-primary-soft px-3 py-2 text-sm font-bold text-primary-strong">
          {t("done")}
        </p>
        <Link href="/login" className="font-bold text-primary underline">
          {t("backToLogin")}
        </Link>
      </div>
    );
  }

  if (step === "answers") {
    return (
      <form onSubmit={reset} className={frame} noValidate>
        <h1 className="text-2xl font-extrabold tracking-tight">{t("answersTitle")}</h1>
        <Field label={questions[0]} value={a1} onChange={(e) => setA1(e.target.value)} required autoComplete="off" error={fields.answer1} />
        <Field label={questions[1]} value={a2} onChange={(e) => setA2(e.target.value)} required autoComplete="off" error={fields.answer2} />
        <Field label={t("newPassword")} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={10} maxLength={72} error={fields.newPassword} />
        {alert}
        <Button type="submit" disabled={busy} className="min-h-12">
          {t("submit")}
        </Button>
        <button type="button" onClick={() => { setStep("email"); setError(null); }} className="min-h-11 text-sm font-bold text-primary underline">
          {t("start")}
        </button>
      </form>
    );
  }

  return (
    <form onSubmit={getQuestions} className={frame}>
      <div className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        <p className="text-sm text-muted">{t("intro")}</p>
      </div>
      <Field label={t("email")} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <p className="text-xs text-muted">{t("notOwner")}</p>
      {alert}
      <Button type="submit" disabled={busy} className="min-h-12">
        {t("getQuestions")}
      </Button>
      <Link href="/login" className="text-center text-sm font-bold text-primary underline">
        {t("backToLogin")}
      </Link>
    </form>
  );
}
