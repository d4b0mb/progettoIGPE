"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { describeAuthError, type FormProblem } from "./errors";

export function SignupForm() {
  const t = useTranslations("auth");
  const tc = useTranslations("common");
  const { signUp } = useSession();
  const router = useRouter();
  const [problem, setProblem] = useState<FormProblem | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setProblem(null);
    try {
      await signUp({
        restaurantName: String(form.get("restaurantName")),
        ownerName: String(form.get("ownerName")),
        email: String(form.get("email")),
        password: String(form.get("password")),
      });
      router.replace("/");
    } catch (error) {
      setProblem(describeAuthError(error, t, tc("somethingWentWrong")));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex w-full max-w-md flex-col gap-5 rounded-2xl border border-line bg-surface p-6 sm:p-9">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("signUp")}</h1>
        <p className="text-sm text-muted">{t("signUpSubtitle")}</p>
      </div>
      <Field label={t("restaurantName")} name="restaurantName" required maxLength={120} error={problem?.fields.restaurantName} />
      <Field label={t("ownerName")} name="ownerName" autoComplete="name" required maxLength={120} error={problem?.fields.ownerName} />
      <Field label={t("email")} name="email" type="email" autoComplete="username" required error={problem?.fields.email} />
      <Field
        label={t("password")}
        name="password"
        type="password"
        autoComplete="new-password"
        required
        minLength={10}
        maxLength={72}
        hint={t("passwordHint")}
        error={problem?.fields.password}
      />
      {problem?.message ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {problem.message}
        </p>
      ) : null}
      <Button type="submit" disabled={busy} className="min-h-12">
        {t("submitSignUp")}
      </Button>
      <p className="text-center text-sm text-muted">
        {t("haveAccount")}{" "}
        <Link href="/login" className="font-bold text-primary underline">
          {t("signIn")}
        </Link>
      </p>
    </form>
  );
}
