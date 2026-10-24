"use client";

import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import type { ApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";

type Room = components["schemas"]["Room"];

export function RoomForm({ existing, onSaved, onCancel }: { existing?: Room; onSaved: (room: Room) => void; onCancel: () => void }) {
  const t = useTranslations("floor");
  const tc = useTranslations("common");
  const [name, setName] = useState(existing?.name ?? "");
  const [width, setWidth] = useState(String(existing?.widthMeters ?? 12));
  const [height, setHeight] = useState(String(existing?.heightMeters ?? 9));
  const [problem, setProblem] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    const body = { name, widthMeters: Number(width), heightMeters: Number(height) };
    try {
      const room = existing
        ? await unwrap(api.PUT("/api/v1/rooms/{id}", { params: { path: { id: existing.id } }, body }))
        : await unwrap(api.POST("/api/v1/rooms", { body }));
      onSaved(room);
    } catch (error) {
      setProblem(error as ApiError);
      setBusy(false);
    }
  }

  const fe = problem?.fields ?? {};
  const general = problem && Object.keys(fe).length === 0 ? problem.message : null;
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      <Field label={t("roomName")} value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} error={fe.name} />
      <div className="grid grid-cols-2 gap-4">
        <Field label={t("width")} type="number" inputMode="decimal" min={1} max={200} step="0.5" value={width} onChange={(e) => setWidth(e.target.value)} error={fe.widthMeters} />
        <Field label={t("height")} type="number" inputMode="decimal" min={1} max={200} step="0.5" value={height} onChange={(e) => setHeight(e.target.value)} error={fe.heightMeters} />
      </div>
      {general ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {general}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={onCancel}>
          {tc("cancel")}
        </Button>
        <Button type="submit" disabled={busy}>
          {existing ? tc("save") : t("createRoom")}
        </Button>
      </div>
    </form>
  );
}
