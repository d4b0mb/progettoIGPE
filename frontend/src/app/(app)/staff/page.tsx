"use client";

import { useTranslations } from "next-intl";
import { useEffect, useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { toApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import { useSession } from "@/api/session";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { expectOk } from "@/api/call";

type Member = components["schemas"]["UserView"];
const ROLES = ["MANAGER", "WAITER", "KITCHEN"] as const;

export default function StaffPage() {
  const t = useTranslations("staff");
  const tc = useTranslations("common");
  const tr = useTranslations("roles");
  const { state } = useSession();
  const [members, setMembers] = useState<Member[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [resetting, setResetting] = useState<Member | null>(null);
  const [resetPassword, setResetPassword] = useState("");
  const [resetError, setResetError] = useState<string | null>(null);

  // Bumped after every change so the list is fetched again.
  const [version, setVersion] = useState(0);
  const isOwner = state.status === "authenticated" && state.user.role === "OWNER";

  useEffect(() => {
    if (!isOwner) return;
    let cancelled = false;
    void api.GET("/api/v1/staff").then(({ data }) => {
      if (!cancelled && data) setMembers(data);
    });
    return () => {
      cancelled = true;
    };
  }, [isOwner, version]);

  if (state.status !== "authenticated") return null;
  if (state.user.role !== "OWNER") return <p className="text-muted">{t("ownerOnly")}</p>;
  const me = state.user;

  async function onAdd(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setError(null);
    setFieldErrors({});
    const res = await api.POST("/api/v1/staff", {
      body: {
        displayName: String(data.get("displayName")),
        email: String(data.get("email")),
        password: String(data.get("password")),
        role: String(data.get("role")) as (typeof ROLES)[number],
      },
    });
    if (!res.data) {
      const problem = toApiError(res.response.status, res.error);
      setFieldErrors(problem.fields);
      setError(Object.keys(problem.fields).length ? null : problem.message);
      return;
    }
    setNotice(t("created", { name: res.data.displayName }));
    form.reset();
    setVersion((v) => v + 1);
  }

  async function onReset(event: FormEvent) {
    event.preventDefault();
    if (!resetting) return;
    setResetError(null);
    try {
      await expectOk(api.PATCH("/api/v1/staff/{id}/password", { params: { path: { id: resetting.id } }, body: { newPassword: resetPassword } }));
      setNotice(t("resetDone", { name: resetting.displayName }));
      setResetting(null);
      setResetPassword("");
    } catch (e) {
      const problem = e as { fields?: Record<string, string>; message: string };
      setResetError(problem.fields?.newPassword ?? problem.message);
    }
  }

  async function onDeactivate(member: Member) {
    if (!window.confirm(t("deactivateConfirm", { name: member.displayName }))) return;
    const res = await api.DELETE("/api/v1/staff/{id}", { params: { path: { id: member.id } } });
    if (res.response.ok) setVersion((v) => v + 1);
    else setError(toApiError(res.response.status, res.error).message);
  }

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        <p className="text-sm text-muted">{t("subtitle")}</p>
      </div>

      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="rounded-lg bg-primary-soft px-3 py-2 text-sm font-semibold text-primary-strong">
          {notice}
        </p>
      ) : null}

      <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line bg-surface">
        {members?.length === 0 ? <li className="p-4 text-sm text-muted">{t("empty")}</li> : null}
        {members?.map((member) => (
          <li key={member.id} className="flex flex-wrap items-center justify-between gap-3 p-4">
            <div className="flex min-w-0 flex-col">
              <span className="truncate font-bold">{member.displayName}</span>
              <span className="truncate text-sm text-muted">{member.email}</span>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <Badge tone={member.role === "OWNER" ? "info" : "neutral"}>{tr(member.role)}</Badge>
              <Badge tone={member.active ? "good" : "danger"}>{member.active ? t("active") : t("inactive")}</Badge>
              {member.active && member.id !== me.id ? (
                <>
                  <Button variant="secondary" onClick={() => { setResetting(member); setResetPassword(""); setResetError(null); }}>
                    {t("resetPassword")}
                  </Button>
                  <Button variant="danger" onClick={() => void onDeactivate(member)}>
                    {t("deactivate")}
                  </Button>
                </>
              ) : null}
            </div>
          </li>
        ))}
      </ul>

      <form onSubmit={onAdd} className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-5">
        <h2 className="text-lg font-extrabold">{t("add")}</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("name")} name="displayName" required maxLength={120} error={fieldErrors.displayName} />
          <Field label="Email" name="email" type="email" required error={fieldErrors.email} />
          <Field label="Password" name="password" type="password" autoComplete="new-password" required minLength={10} maxLength={72} error={fieldErrors.password} />
          <div className="flex flex-col gap-1.5">
            <label htmlFor="role" className="text-sm font-bold">
              {t("role")}
            </label>
            <select id="role" name="role" defaultValue="WAITER" className="min-h-12 rounded-[10px] border border-line-strong bg-surface px-3 text-base">
              {ROLES.map((role) => (
                <option key={role} value={role}>
                  {tr(role)}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div>
          <Button type="submit">{tc("save")}</Button>
        </div>
      </form>
      <Dialog open={resetting !== null} onClose={() => setResetting(null)} title={resetting ? t("resetTitle", { name: resetting.displayName }) : ""}>
        <form onSubmit={onReset} className="flex flex-col gap-4" noValidate>
          <Field label={t("newPassword")} type="password" autoComplete="new-password" value={resetPassword} onChange={(e) => setResetPassword(e.target.value)} required minLength={10} maxLength={72} hint={t("resetHint")} error={resetError ?? undefined} />
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onClick={() => setResetting(null)}>
              {tc("cancel")}
            </Button>
            <Button type="submit">{tc("save")}</Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
