"use client";

import { useLocale, useTranslations } from "next-intl";
import { useCallback, useState } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useRemote } from "@/lib/useRemote";
import { COURSES } from "./MenuForm";
import { ModifierForm } from "./ModifierForm";
import { formatCents } from "./money";
import { groupBySection } from "./sections";

type Modifier = components["schemas"]["Modifier"];

/** The options guests can add to dishes (toppings, removals, variants), grouped by course. Owner and manager edit them. */
export function ModifierManager({ canEdit }: { canEdit: boolean }) {
  const t = useTranslations("menu");
  const tcourse = useTranslations("courses");
  const ta = useTranslations("allergens");
  const tc = useTranslations("common");
  const locale = useLocale();
  const [editing, setEditing] = useState<Modifier | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => unwrap(api.GET("/api/v1/menu/modifiers")), []);
  const options = useRemote(load);
  const list = options.data ?? [];

  async function remove(option: Modifier) {
    if (!window.confirm(t("deleteOptionConfirm", { name: option.name }))) return;
    try {
      await expectOk(api.DELETE("/api/v1/menu/modifiers/{id}", { params: { path: { id: option.id } } }));
      options.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-sm text-muted">{canEdit ? t("optionsHelp") : t("readOnly")}</p>
        {canEdit ? <Button onClick={() => setEditing("new")}>{t("addOption")}</Button> : null}
      </div>

      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
      {options.data && list.length === 0 ? <p className="rounded-2xl border border-dashed border-line-strong p-8 text-center text-muted">{t("optionsEmpty")}</p> : null}

      {COURSES.map((course) => {
        const inCourse = list.filter((o) => o.course === course);
        if (inCourse.length === 0) return null;
        return (
          <section key={course} className="flex flex-col gap-3" aria-label={tcourse(course)}>
            <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">{tcourse(course)}</h2>
            {groupBySection(inCourse).map((group) => (
              <div key={group.section} className="flex flex-col gap-2">
                {group.section ? <h3 className="text-xs font-extrabold tracking-wide text-primary-strong uppercase">{group.section}</h3> : null}
                <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line bg-surface">
                  {group.items.map((o) => (
                    <li key={o.id} className={`flex flex-col gap-2 p-3 sm:flex-row sm:items-center ${o.available ? "" : "opacity-60"}`}>
                      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">
                        <span className="font-bold">{o.name}</span>
                        <span className="text-sm font-semibold text-muted tabular-nums">{o.priceCents > 0 ? `+${formatCents(o.priceCents, locale)}` : t("free")}</span>
                        {!o.available ? <Badge tone="warn">{t("unavailable")}</Badge> : null}
                        {o.allergens.map((a) => (
                          <Badge key={a} tone="danger">
                            {ta(a)}
                          </Badge>
                        ))}
                      </div>
                      {canEdit ? (
                        <div className="flex gap-2">
                          <Button variant="secondary" onClick={() => setEditing(o)}>
                            {t("edit")}
                          </Button>
                          <Button variant="danger" onClick={() => void remove(o)}>
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

      <Dialog open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? t("addOption") : t("editOption")}>
        {editing !== null ? (
          <ModifierForm
            existing={editing === "new" ? undefined : editing}
            onSaved={() => {
              setEditing(null);
              options.reload();
            }}
            onCancel={() => setEditing(null)}
          />
        ) : null}
      </Dialog>
    </div>
  );
}
