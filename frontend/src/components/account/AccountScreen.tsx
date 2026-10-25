"use client";

import { useTranslations } from "next-intl";
import { useSession } from "@/api/session";
import { PasswordForm } from "./PasswordForm";
import { RecoverySetup } from "./RecoverySetup";

export function AccountScreen() {
  const t = useTranslations("account");
  const tr = useTranslations("roles");
  const { state } = useSession();
  if (state.status !== "authenticated") return null;
  const { user } = state;
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        <p className="text-sm text-muted">{t("signedInAs", { name: user.displayName, role: tr(user.role) })}</p>
        <p className="text-sm text-muted">{user.email}</p>
      </div>
      <PasswordForm />
      {user.role === "OWNER" ? <RecoverySetup /> : null}
    </div>
  );
}
