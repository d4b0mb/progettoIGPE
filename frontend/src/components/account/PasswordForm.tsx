"use client";

import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { ApiError } from "@/api/errors";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";

/** Change your own password. Other sessions of yours are signed out; this one carries on. */
export function PasswordForm() {
  const t = useTranslations("account");
  const tc = useTranslations("common");
  const { changePassword } = useSession();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    setFields({});
    if (next !== repeat) {
      setFields({ repeat: t("passwordsDiffer") });
      return;
    }
    setBusy(true);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setRepeat("");
      setMessage({ kind: "ok", text: t("passwordChanged") });
    } catch (error) {
      if (error instanceof ApiError && Object.keys(error.fields).length > 0) setFields(error.fields);
      else setMessage({ kind: "error", text: tc("somethingWentWrong") });
    }
    setBusy(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex max-w-xl flex-col gap-4 rounded-2xl border border-line bg-surface p-5" noValidate>
      <h2 className="text-lg font-extrabold">{t("passwordTitle")}</h2>
      <Field label={t("currentPassword")} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required error={fields.currentPassword} />
      <Field label={t("newPassword")} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={10} maxLength={72} hint={t("passwordHint")} error={fields.newPassword} />
      <Field label={t("repeatPassword")} type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} required error={fields.repeat} />
      {message ? (
        <p role={message.kind === "ok" ? "status" : "alert"} className={`rounded-lg px-3 py-2 text-sm font-semibold ${message.kind === "ok" ? "bg-primary-soft text-primary-strong" : "bg-danger-soft text-danger-ink"}`}>
          {message.text}
        </p>
      ) : null}
      <div>
        <Button type="submit" disabled={busy}>
          {tc("save")}
        </Button>
      </div>
    </form>
  );
}
