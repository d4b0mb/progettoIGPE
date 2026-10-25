"use client";

import { useTranslations } from "next-intl";
import { useCallback, useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { useRemote } from "@/lib/useRemote";

/**
 * The owner writes two questions and answers. Answers are stored only as hashes, so nobody, including
 * us, can read them back; to change them the owner enters the current password again.
 */
export function RecoverySetup() {
  const t = useTranslations("account");
  const tc = useTranslations("common");
  const load = useCallback(() => unwrap(api.GET("/api/v1/me/recovery")), []);
  const status = useRemote(load);

  const [q1, setQ1] = useState("");
  const [a1, setA1] = useState("");
  const [q2, setQ2] = useState("");
  const [a2, setA2] = useState("");
  const [password, setPassword] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    setFields({});
    setBusy(true);
    try {
      await expectOk(
        api.PUT("/api/v1/me/recovery", {
          body: { currentPassword: password, question1: q1, answer1: a1, question2: q2, answer2: a2 },
        }),
      );
      setQ1("");
      setA1("");
      setQ2("");
      setA2("");
      setPassword("");
      setMessage({ kind: "ok", text: t("recoverySaved") });
      status.reload();
    } catch (error) {
      if (error instanceof ApiError && Object.keys(error.fields).length > 0) setFields(error.fields);
      else setMessage({ kind: "error", text: tc("somethingWentWrong") });
    }
    setBusy(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex max-w-xl flex-col gap-4 rounded-2xl border border-line bg-surface p-5" noValidate>
      <h2 className="text-lg font-extrabold">{t("recoveryTitle")}</h2>
      <p className="text-sm text-muted">{t("recoveryIntro")}</p>
      {status.data ? (
        status.data.configured ? (
          <div className="flex flex-col gap-1 rounded-lg bg-primary-soft px-3 py-2 text-sm text-primary-strong">
            <span className="font-bold">{t("recoveryOn")}</span>
            <span>1. {status.data.question1}</span>
            <span>2. {status.data.question2}</span>
          </div>
        ) : (
          <p className="rounded-lg bg-warn-soft px-3 py-2 text-sm font-bold text-warn-ink">{t("recoveryOff")}</p>
        )
      ) : null}
      <p className="text-xs text-muted">{t("questionHint")}</p>
      <Field label={t("question1")} value={q1} onChange={(e) => setQ1(e.target.value)} required maxLength={200} error={fields.question1} />
      <Field label={t("answer1")} value={a1} onChange={(e) => setA1(e.target.value)} required maxLength={200} autoComplete="off" error={fields.answer1} />
      <Field label={t("question2")} value={q2} onChange={(e) => setQ2(e.target.value)} required maxLength={200} error={fields.question2} />
      <Field label={t("answer2")} value={a2} onChange={(e) => setA2(e.target.value)} required maxLength={200} autoComplete="off" error={fields.answer2} />
      <Field label={t("confirmWithPassword")} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required error={fields.currentPassword} />
      {message ? (
        <p role={message.kind === "ok" ? "status" : "alert"} className={`rounded-lg px-3 py-2 text-sm font-semibold ${message.kind === "ok" ? "bg-primary-soft text-primary-strong" : "bg-danger-soft text-danger-ink"}`}>
          {message.text}
        </p>
      ) : null}
      <div>
        <Button type="submit" disabled={busy}>
          {t("saveRecovery")}
        </Button>
      </div>
    </form>
  );
}
