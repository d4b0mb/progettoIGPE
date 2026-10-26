"use client";

import { useLocale, useTranslations } from "next-intl";
import { useCallback, useState } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { useSession } from "@/api/session";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useRemote } from "@/lib/useRemote";
import { COURSES, MenuForm } from "./MenuForm";
import { ModifierManager } from "./ModifierManager";
import { formatCents } from "./money";
import { groupBySection } from "./sections";

type MenuItem = components["schemas"]["MenuItem"];

export function MenuScreen() {
  const t = useTranslations("menu");
  const tcourse = useTranslations("courses");
  const ta = useTranslations("allergens");
  const tc = useTranslations("common");
  const locale = useLocale();
  const { state } = useSession();
  const [editing, setEditing] = useState<MenuItem | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<"dishes" | "options">("dishes");

  const load = useCallback(() => unwrap(api.GET("/api/v1/menu/items")), []);
  const menu = useRemote(load);

  if (state.status !== "authenticated") return null;
  const canEdit = state.user.role === "OWNER" || state.user.role === "MANAGER";

  async function remove(item: MenuItem) {
    if (!window.confirm(t("deleteConfirm", { name: item.name }))) return;
    try {
      await expectOk(api.DELETE("/api/v1/menu/items/{id}", { params: { path: { id: item.id } } }));
      menu.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function saved() {
    setEditing(null);
    menu.reload();
  }

  const items = menu.data ?? [];
  return (
    <div className="flex max-w-4xl flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
          <p className="text-sm text-muted">{canEdit ? t("subtitle") : t("readOnly")}</p>
        </div>
        {canEdit && tab === "dishes" ? <Button onClick={() => setEditing("new")}>{t("add")}</Button> : null}
      </div>

      <div role="group" aria-label={t("title")} className="flex flex-wrap gap-2">
        {(["dishes", "options"] as const).map((k) => (
          <button
            key={k}
            type="button"
            aria-pressed={tab === k}
            onClick={() => setTab(k)}
            className={`min-h-11 rounded-full border px-4 text-sm font-bold ${tab === k ? "border-primary bg-primary text-on-primary" : "border-line-strong bg-surface text-ink"}`}
          >
            {k === "dishes" ? t("tabDishes") : t("tabOptions")}
          </button>
        ))}
      </div>

      {tab === "options" ? <ModifierManager canEdit={canEdit} /> : null}

      {tab === "dishes" && error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
      {tab === "dishes" && menu.data && items.length === 0 ? <p className="rounded-2xl border border-dashed border-line-strong p-8 text-center text-muted">{t("empty")}</p> : null}

      {(tab === "dishes" ? COURSES : []).map((course) => {
        const inCourse = items.filter((i) => i.course === course);
        if (inCourse.length === 0) return null;
        return (
          <section key={course} className="flex flex-col gap-2">
            <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">{tcourse(course)}</h2>
            {groupBySection(inCourse).map((group) => (
              <div key={group.section} className="flex flex-col gap-2">
                {group.section ? <h3 className="text-xs font-extrabold tracking-wide text-primary-strong uppercase">{group.section}</h3> : null}
                <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line bg-surface">
                  {group.items.map((item) => (
                    <li key={item.id} className={`flex flex-col gap-3 p-4 sm:flex-row sm:items-center ${item.available ? "" : "opacity-60"}`}>
                      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                        <div className="flex flex-wrap items-baseline gap-x-3">
                          <span className="font-bold">{item.name}</span>
                          {item.priceCents != null ? <span className="text-sm font-semibold text-muted tabular-nums">{formatCents(item.priceCents, locale)}</span> : null}
                          {!item.available ? <Badge tone="warn">{t("unavailable")}</Badge> : null}
                        </div>
                        {item.description ? <p className="text-sm text-muted">{item.description}</p> : null}
                        <div className="flex flex-wrap gap-1.5">
                          {item.allergens.length === 0 ? (
                            <span className="text-xs text-muted">{t("noAllergens")}</span>
                          ) : (
                            item.allergens.map((a) => (
                              <Badge key={a} tone="danger">
                                {ta(a)}
                              </Badge>
                            ))
                          )}
                        </div>
                      </div>
                      {canEdit ? (
                        <div className="flex gap-2">
                          <Button variant="secondary" onClick={() => setEditing(item)}>
                            {t("edit")}
                          </Button>
                          <Button variant="danger" onClick={() => void remove(item)}>
                            {tc("delete")}
                          </Button>
                        </div>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        );
      })}

      <Dialog open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? t("add") : t("edit")}>
        {editing !== null ? <MenuForm existing={editing === "new" ? undefined : editing} onSaved={saved} onCancel={() => setEditing(null)} /> : null}
      </Dialog>
    </div>
  );
}
