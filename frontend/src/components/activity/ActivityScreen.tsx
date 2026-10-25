"use client";

import { useLocale, useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { SelectField } from "@/components/ui/Controls";
import { CATEGORIES, RANGES, describeDetails, filterEntries, rangeStart, type Category, type Entry, type Range } from "./activity";

const PAGE = 200;
/** Matches the server's default retention for the log (see app.retention.audit-days). */
const KEPT_DAYS = 365;

/** The owner's view of everything that happened: who, what, when, from where. Read-only by design. */
export function ActivityScreen() {
  const t = useTranslations("activity");
  const locale = useLocale();
  const [range, setRange] = useState<Range>("7d");
  const [actorId, setActorId] = useState("");
  const [category, setCategory] = useState<Category>("ALL");
  const [failuresOnly, setFailuresOnly] = useState(false);
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [people, setPeople] = useState<{ id: string; displayName: string }[]>([]);

  useEffect(() => {
    let cancelled = false;
    void unwrap(api.GET("/api/v1/staff")).then((list) => !cancelled && setPeople(list.map((m) => ({ id: m.id, displayName: m.displayName }))), () => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const query = useCallback(
    (before?: string) => ({ from: rangeStart(range, Date.now()), actorId: actorId || undefined, limit: PAGE, before }),
    [range, actorId],
  );

  // Fetch a fresh first page whenever the period or the person changes.
  useEffect(() => {
    let cancelled = false;
    void unwrap(api.GET("/api/v1/audit", { params: { query: query() } })).then(
      (page) => {
        if (cancelled) return;
        setEntries(page);
        setMore(page.length === PAGE);
        setError(null);
      },
      (e: Error) => !cancelled && setError(e.message),
    );
    return () => {
      cancelled = true;
    };
  }, [query]);

  async function loadOlder() {
    if (!entries || entries.length === 0) return;
    try {
      const page = await unwrap(api.GET("/api/v1/audit", { params: { query: query(entries[entries.length - 1].occurredAt) } }));
      setEntries([...entries, ...page]);
      setMore(page.length === PAGE);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const shown = entries ? filterEntries(entries, category, failuresOnly) : [];
  const when = new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "medium" });

  return (
    <div className="flex max-w-5xl flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        <p className="text-sm text-muted">{t("subtitle", { days: KEPT_DAYS })}</p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <SelectField label={t("range")} value={range} onChange={(e) => setRange(e.target.value as Range)}>
          {RANGES.map((r) => (
            <option key={r} value={r}>
              {t(`range${r}`)}
            </option>
          ))}
        </SelectField>
        <SelectField label={t("person")} value={actorId} onChange={(e) => setActorId(e.target.value)}>
          <option value="">{t("everyone")}</option>
          {people.map((p) => (
            <option key={p.id} value={p.id}>
              {p.displayName}
            </option>
          ))}
        </SelectField>
        <label className="flex min-h-12 items-center gap-3 self-end text-sm font-bold">
          <input type="checkbox" checked={failuresOnly} onChange={(e) => setFailuresOnly(e.target.checked)} className="h-5 w-5 accent-primary" />
          {t("failuresOnly")}
        </label>
      </div>

      <div className="flex flex-wrap gap-2" role="group" aria-label={t("category")}>
        {CATEGORIES.map((c) => (
          <button
            key={c}
            type="button"
            aria-pressed={category === c}
            onClick={() => setCategory(c)}
            className={`min-h-11 rounded-full border px-4 text-sm font-bold ${category === c ? "border-primary bg-primary-soft text-primary-strong" : "border-line bg-surface text-muted"}`}
          >
            {t(`categories.${c}`)}
          </button>
        ))}
      </div>

      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
      {entries && shown.length === 0 ? <p className="rounded-2xl border border-dashed border-line-strong p-8 text-center text-muted">{t("empty")}</p> : null}

      <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line bg-surface">
        {shown.map((e) => {
          const known = t.has(`actions.${e.action}`);
          const details = describeDetails(e.details);
          return (
            <li key={e.id} className="flex flex-col gap-1 p-4 sm:flex-row sm:items-start sm:gap-4">
              <time dateTime={e.occurredAt} className="text-sm font-semibold text-muted tabular-nums sm:w-52 sm:shrink-0">
                {when.format(new Date(e.occurredAt))}
              </time>
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-bold">{known ? t(`actions.${e.action}`) : e.action}</span>
                  {e.outcome === "FAILURE" ? <Badge tone="danger">{t("failed")}</Badge> : null}
                </div>
                <span className="text-sm text-muted">
                  {e.actorName || t("system")}
                  {e.ip ? ` · ${t("from", { ip: e.ip })}` : ""}
                </span>
                {details ? <span className="text-xs break-words text-muted">{details}</span> : null}
              </div>
            </li>
          );
        })}
      </ul>

      {more ? (
        <div>
          <Button variant="secondary" onClick={() => void loadOlder()}>
            {t("loadOlder")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}
