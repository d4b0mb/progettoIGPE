"use client";

import { useTranslations } from "next-intl";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { draftItems, fitWithin, selectionToRequests, type CreateRequest, type DraftItem, type FloorDraft } from "./draft";

type Room = components["schemas"]["Room"];

/** The longest side of the photo sent to the server, in pixels. Enough to read a plan, small enough to upload fast. */
const MAX_SIDE = 1600;
const JPEG_QUALITY = 0.85;

type Step = "choose" | "working" | "review" | "adding";

/**
 * Shrinks a photo in the browser and turns it into a JPEG. This also converts photos the server could not read
 * (an iPhone's HEIC, for instance) as long as the browser itself can show them. Throws if the browser cannot decode it.
 */
async function shrinkToJpeg(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  try {
    const { width, height } = fitWithin(bitmap.width, bitmap.height, MAX_SIDE);
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("No canvas");
    // A transparent PNG would turn black as a JPEG: paint white paper first.
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0, width, height);
    return await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("Could not encode the image"))), "image/jpeg", JPEG_QUALITY);
    });
  } finally {
    bitmap.close();
  }
}

/** Import a draft of the room from a photo: choose the photo, check what was found, then add it to the room. */
export function FloorDraftDialog({ open, onClose, room, onApplied }: { open: boolean; onClose: () => void; room: Room; onApplied: () => void }) {
  const t = useTranslations("floor");
  // The dialog only renders its content while open, so closing it throws away every bit of state below.
  return (
    <Dialog open={open} onClose={onClose} title={t("draftTitle")}>
      <DraftFlow room={room} onClose={onClose} onApplied={onApplied} />
    </Dialog>
  );
}

function DraftFlow({ room, onClose, onApplied }: { room: Room; onClose: () => void; onApplied: () => void }) {
  const t = useTranslations("floor");
  const tc = useTranslations("common");
  const [step, setStep] = useState<Step>("choose");
  const [file, setFile] = useState<File | null>(null);
  const [widthText, setWidthText] = useState(String(room.widthMeters));
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<FloorDraft | null>(null);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [progress, setProgress] = useState({ current: 0, total: 0 });
  // The request in flight (or the adding loop) that closing the dialog or pressing Cancel must stop.
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const width = Number(widthText);
  const widthValid = widthText.trim() !== "" && Number.isFinite(width) && width >= 1 && width <= 200;

  async function createDraft(event: FormEvent) {
    event.preventDefault();
    if (!file || !widthValid) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setError(null);
    setStep("working");

    let photo: Blob;
    try {
      photo = await shrinkToJpeg(file);
    } catch {
      if (controller.signal.aborted) return;
      setError(t("draftUnreadable"));
      setStep("choose");
      return;
    }
    if (controller.signal.aborted) return;

    const form = new FormData();
    form.append("image", photo, "plan.jpg");
    try {
      const result = await unwrap(
        api.POST("/api/v1/rooms/{roomId}/floor-draft", {
          params: { path: { roomId: room.id }, query: { roomWidthMeters: width } },
          // The generated type says the body is { image: string }, but the real payload is the FormData built above.
          // The placeholder only satisfies the type; the serializer sends the form instead. This is the one place for that.
          body: { image: "" },
          bodySerializer: () => form,
          signal: controller.signal,
        }),
      );
      if (controller.signal.aborted) return;
      setDraft(result);
      setChosen(new Set(draftItems(result).map((item) => item.id)));
      setStep("review");
    } catch (e) {
      if (controller.signal.aborted) return;
      setError((e as Error).message);
      setStep("choose");
    }
  }

  function stopWorking() {
    abortRef.current?.abort();
    setStep("choose");
  }

  function anotherPhoto() {
    setDraft(null);
    setFile(null);
    setChosen(new Set());
    setError(null);
    setStep("choose");
  }

  function toggle(id: string) {
    setChosen((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  async function addChosen() {
    if (!draft) return;
    const requests = selectionToRequests(draft, chosen, room);
    const controller = new AbortController();
    abortRef.current = controller;
    setError(null);
    setStep("adding");
    const added = new Set<string>();
    let failure: Error | null = null;
    // One after another, never in parallel, so the server numbers the tables in the order of the list.
    for (const [index, request] of requests.entries()) {
      if (controller.signal.aborted) break;
      setProgress({ current: index + 1, total: requests.length });
      try {
        await create(request);
        added.add(request.id);
      } catch (e) {
        failure = e as Error;
        break;
      }
    }
    // Closed while adding: stop quietly, but still show whatever was added.
    if (controller.signal.aborted) {
      if (added.size > 0) onApplied();
      return;
    }
    if (failure) {
      // Keep the dialog open. What was added is unticked, so pressing the button again adds only the rest.
      setChosen((current) => new Set([...current].filter((id) => !added.has(id))));
      setError(t("draftPartial", { done: added.size, total: requests.length, message: failure.message }));
      setStep("review");
      if (added.size > 0) onApplied();
      return;
    }
    onApplied();
    onClose();
  }

  /** One call to the same endpoints the editor uses. */
  function create(request: CreateRequest) {
    const params = { path: { roomId: room.id } };
    return request.kind === "table"
      ? unwrap(api.POST("/api/v1/rooms/{roomId}/tables", { params, body: request.body }))
      : unwrap(api.POST("/api/v1/rooms/{roomId}/obstacles", { params, body: request.body }));
  }

  const items = draft ? draftItems(draft) : [];
  const problem = error ? (
    <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
      {error}
    </p>
  ) : null;

  if (step === "choose" || step === "working") {
    const working = step === "working";
    // The form stays mounted (only hidden) while the photo is read, so the chosen file is still there if it has to be tried again.
    return (
      <div className="flex flex-col gap-4">
        {working ? (
          <>
            <p role="status" className="rounded-lg bg-info-soft px-3 py-3 text-sm font-semibold text-info-ink">
              {t("draftWorking")}
            </p>
            <div className="flex flex-wrap justify-end gap-3">
              <Button variant="secondary" onClick={stopWorking}>
                {t("draftCancel")}
              </Button>
            </div>
          </>
        ) : null}
        <form onSubmit={(event) => void createDraft(event)} className={`${working ? "hidden" : "flex"} flex-col gap-4`} noValidate>
          <p className="text-sm text-muted">{t("draftHelp")}</p>
          <Field
            label={t("draftFile")}
            type="file"
            accept="image/*"
            className="py-2 file:mr-3 file:min-h-8 file:rounded-md file:border-0 file:bg-primary-soft file:px-3 file:text-sm file:font-bold file:text-primary-strong"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <Field
            label={t("draftWidth")}
            type="number"
            inputMode="decimal"
            min={1}
            max={200}
            step="0.5"
            value={widthText}
            onChange={(event) => setWidthText(event.target.value)}
            hint={t("draftWidthHint")}
            error={widthValid ? undefined : t("draftWidthInvalid")}
          />
          {problem}
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onClick={onClose}>
              {tc("cancel")}
            </Button>
            <Button type="submit" disabled={!file || !widthValid}>
              {t("draftCreate")}
            </Button>
          </div>
        </form>
      </div>
    );
  }

  // Review and adding share one screen; while adding, the controls are locked and the progress is shown.
  const adding = step === "adding";
  const requests = draft ? selectionToRequests(draft, chosen, room) : [];
  const numbers = new Map<string, number>(items.map((item, index): [string, number] => [item.id, index + 1]));
  return (
    <div className="flex flex-col gap-4">
      {draft ? <DraftPreview draft={draft} requests={requests} numbers={numbers} /> : null}

      {draft && draft.warnings.length > 0 ? (
        <section className="flex flex-col gap-1 rounded-lg bg-info-soft px-3 py-2 text-sm text-info-ink" aria-label={t("draftWarnings")}>
          <h3 className="font-extrabold">{t("draftWarnings")}</h3>
          <ul className="list-disc pl-5">
            {draft.warnings.map((warning, index) => (
              <li key={index}>{warning}</li>
            ))}
          </ul>
        </section>
      ) : null}

      {items.length === 0 ? (
        <p className="text-sm text-muted">{t("draftNoItems")}</p>
      ) : (
        <section className="flex flex-col gap-2" aria-label={t("draftItems")}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-extrabold">{t("draftItems")}</h3>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={adding} onClick={() => setChosen(new Set(items.map((item) => item.id)))}>
                {t("draftSelectAll")}
              </Button>
              <Button variant="secondary" disabled={adding} onClick={() => setChosen(new Set())}>
                {t("draftSelectNone")}
              </Button>
            </div>
          </div>
          <ul className="max-h-64 overflow-y-auto rounded-lg border border-line">
            {items.map((item) => (
              <li key={item.id} className="border-b border-line last:border-b-0">
                <label className="flex min-h-11 cursor-pointer items-center gap-3 px-3 py-1.5 text-sm">
                  <input type="checkbox" className="h-5 w-5 shrink-0 accent-primary" checked={chosen.has(item.id)} disabled={adding} onChange={() => toggle(item.id)} />
                  <span className="w-6 shrink-0 text-right text-xs font-extrabold text-muted">{numbers.get(item.id)}</span>
                  <ItemLabel item={item} />
                </label>
              </li>
            ))}
          </ul>
        </section>
      )}

      {problem}

      <p className="text-sm font-semibold">{t("draftAddNote")}</p>
      {adding ? (
        <p role="status" className="text-sm font-semibold text-muted">
          {t("draftAdding", { current: progress.current, total: progress.total })}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={onClose}>
          {tc("cancel")}
        </Button>
        <Button variant="secondary" disabled={adding} onClick={anotherPhoto}>
          {t("draftRetry")}
        </Button>
        <Button disabled={adding || requests.length === 0} onClick={() => void addChosen()}>
          {t("draftAdd", { count: requests.length })}
        </Button>
      </div>
    </div>
  );
}

/** "Round table, 4 seats" or "Bar". */
function ItemLabel({ item }: { item: DraftItem }) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  if (item.kind === "element") return <span>{te(item.type)}</span>;
  const shape = item.shape === "ROUND" ? t("tableRound") : item.shape === "SQUARE" ? t("tableSquare") : t("tableRectangle");
  return (
    <span>
      {shape}, {t("seatsShort", { count: item.seats })}
    </span>
  );
}

/** What is drawn for one ticked item, taken from the request that would create it, so the picture shows what will be added. */
function DraftPreview({ draft, requests, numbers }: { draft: FloorDraft; requests: CreateRequest[]; numbers: Map<string, number> }) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const w = Math.max(draft.widthMeters, 1);
  const h = Math.max(draft.heightMeters, 1);
  const margin = Math.max(w, h) / 60;
  // Text sized to the room, so a small room and a big hall both read well. Outlines keep one width on screen.
  const font = Math.max(0.25, Math.max(w, h) / 45);
  const line = { vectorEffect: "non-scaling-stroke" as const, strokeWidth: 1.5 };
  const textProps = { fontSize: font, fontWeight: 800, textAnchor: "middle" as const, dominantBaseline: "central" as const, paintOrder: "stroke", stroke: "var(--color-surface)", strokeWidth: font * 0.25 };

  return (
    <svg
      role="img"
      aria-label={t("draftPreviewLabel", { count: requests.length })}
      viewBox={`${-margin} ${-margin} ${w + 2 * margin} ${h + 2 * margin}`}
      preserveAspectRatio="xMidYMid meet"
      className="block w-full rounded-xl border border-line bg-surface select-none"
      style={{ maxHeight: "50dvh", aspectRatio: `${w + 2 * margin} / ${h + 2 * margin}` }}
    >
      <rect x="0" y="0" width={w} height={h} fill="none" stroke="var(--plan-room-line)" strokeDasharray="6 4" {...line} />
      {requests.map((request) => {
        const { xMeters: x, yMeters: y, widthMeters: bw, heightMeters: bh } = request.body;
        const rotation = request.body.rotationDegrees ?? 0;
        const number = numbers.get(request.id);
        const isTable = request.kind === "table";
        const round = request.kind === "table" ? request.body.shape === "ROUND" : request.body.type === "COLUMN";
        const style = isTable
          ? { fill: "var(--color-primary-soft)", stroke: "var(--color-primary)", ink: "var(--color-ink)" }
          : { fill: "var(--plan-obstacle-fill)", stroke: "var(--plan-obstacle-line)", ink: "var(--plan-text)" };
        // Name next to the number only where it fits: measured across the item as it is turned on the page.
        const radians = (rotation * Math.PI) / 180;
        const across = Math.abs(bw * Math.cos(radians)) + Math.abs(bh * Math.sin(radians));
        const name = request.kind === "obstacle" ? te(request.body.type) : "";
        const label = name && across >= font * (String(number).length + 1 + name.length) * 0.65 ? `${number} ${name}` : String(number ?? "");
        return (
          <g key={request.id}>
            <g transform={`translate(${x} ${y}) rotate(${rotation})`}>
              {round ? (
                <ellipse rx={bw / 2} ry={bh / 2} fill={style.fill} stroke={style.stroke} {...line} />
              ) : (
                <rect x={-bw / 2} y={-bh / 2} width={bw} height={bh} rx={Math.min(bw, bh) * 0.08} fill={style.fill} stroke={style.stroke} {...line} />
              )}
            </g>
            <text x={x} y={y} fill={style.ink} {...textProps} style={{ pointerEvents: "none" }}>
              {label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
