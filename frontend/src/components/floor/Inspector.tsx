"use client";

import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { ApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { SelectField } from "@/components/ui/Controls";
import { Field } from "@/components/ui/Field";

type Table = components["schemas"]["DiningTable"];
type Obstacle = components["schemas"]["Obstacle"];
type Live = components["schemas"]["TableView"]["liveStatus"];
type Service = Table["serviceStatus"];

const SHAPES: Table["shape"][] = ["ROUND", "SQUARE", "RECTANGLE"];
const SERVICE: Service[] = ["FREE", "TO_CLEAN", "OUT_OF_SERVICE"];
export const OBSTACLE_TYPES: Obstacle["type"][] = ["WALL", "COLUMN", "DOOR", "WINDOW", "STAIRS", "KITCHEN", "TOILETS", "BAR", "ENTRANCE", "STORAGE"];

const n = (s: string) => Number(s);

function Shell({ title, children }: { title: string; children: React.ReactNode }) {
  const t = useTranslations("floor");
  return (
    <aside className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-4 lg:w-80 lg:shrink-0">
      <div>
        <p className="text-xs font-bold tracking-wider text-muted uppercase">{t("selected")}</p>
        <h2 className="text-xl font-extrabold">{title}</h2>
      </div>
      {children}
    </aside>
  );
}

export function EmptyInspector() {
  const t = useTranslations("floor");
  return (
    <aside className="rounded-2xl border border-dashed border-line-strong p-4 text-sm text-muted lg:w-80 lg:shrink-0">{t("nothingSelected")}</aside>
  );
}

/** Details of the selected table. Staff can set its service status; owner and manager can change everything. */
export function TableInspector({ table, live, canEdit, onChanged, onDeleted }: { table: Table; live: Live; canEdit: boolean; onChanged: () => void; onDeleted: () => void }) {
  const t = useTranslations("floor");
  const [number, setNumber] = useState(String(table.number));
  const [name, setName] = useState(table.name);
  const [seats, setSeats] = useState(String(table.seats));
  const [shape, setShape] = useState(table.shape);
  const [width, setWidth] = useState(String(table.widthMeters));
  const [height, setHeight] = useState(String(table.heightMeters));
  const [rotation, setRotation] = useState(String(table.rotationDegrees));
  const [x, setX] = useState(String(table.xMeters));
  const [y, setY] = useState(String(table.yMeters));
  const [accessible, setAccessible] = useState(table.accessible);
  const [problem, setProblem] = useState<ApiError | null>(null);

  async function save(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      await unwrap(
        api.PUT("/api/v1/tables/{id}", {
          params: { path: { id: table.id } },
          body: { number: n(number), name, seats: n(seats), shape, widthMeters: n(width), heightMeters: n(height), rotationDegrees: n(rotation), xMeters: n(x), yMeters: n(y), accessible },
        }),
      );
      onChanged();
    } catch (e) {
      setProblem(e as ApiError);
    }
  }

  async function setService(serviceStatus: Service) {
    try {
      await unwrap(api.PATCH("/api/v1/tables/{id}/service-status", { params: { path: { id: table.id } }, body: { serviceStatus } }));
      onChanged();
    } catch (e) {
      setProblem(e as ApiError);
    }
  }

  async function remove() {
    try {
      await expectOk(api.DELETE("/api/v1/tables/{id}", { params: { path: { id: table.id } } }));
      onDeleted();
    } catch (e) {
      setProblem(e as ApiError);
    }
  }

  const fe = problem?.fields ?? {};
  const general = problem && Object.keys(fe).length === 0 ? problem.message : null;
  return (
    <Shell title={`${t("table")} ${table.number}`}>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-bold">{t("live")}:</span>
        <Badge tone={live === "OCCUPIED" ? "good" : live === "RESERVED" ? "warn" : live === "FREE" ? "neutral" : "info"}>{t(`live${live}`)}</Badge>
      </div>

      <div className="flex flex-col gap-2" role="group" aria-label={t("service")}>
        <span className="text-sm font-bold">{t("service")}</span>
        <div className="flex flex-wrap gap-2">
          {SERVICE.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={table.serviceStatus === s}
              onClick={() => void setService(s)}
              className={`min-h-11 rounded-full border px-3.5 text-sm font-semibold ${table.serviceStatus === s ? "border-primary bg-primary-soft text-primary-strong" : "border-line-strong bg-surface text-muted"}`}
            >
              {t(`service${s}`)}
            </button>
          ))}
        </div>
      </div>

      {canEdit ? (
        <form onSubmit={save} className="flex flex-col gap-3" noValidate>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("tableNumber")} type="number" inputMode="numeric" min={1} value={number} onChange={(e) => setNumber(e.target.value)} error={fe.number} />
            <Field label={t("seats")} type="number" inputMode="numeric" min={1} max={100} value={seats} onChange={(e) => setSeats(e.target.value)} error={fe.seats} />
          </div>
          <Field label={t("label")} value={name} onChange={(e) => setName(e.target.value)} maxLength={40} />
          <SelectField label={t("shape")} value={shape} onChange={(e) => setShape(e.target.value as Table["shape"])}>
            {SHAPES.map((s) => (
              <option key={s} value={s}>
                {t(`shape${s}`)}
              </option>
            ))}
          </SelectField>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("width")} type="number" inputMode="decimal" step="0.05" min={0.2} value={width} onChange={(e) => setWidth(e.target.value)} error={fe.widthMeters} />
            <Field label={t("height")} type="number" inputMode="decimal" step="0.05" min={0.2} value={height} onChange={(e) => setHeight(e.target.value)} error={fe.heightMeters} />
            <Field label={t("x")} type="number" inputMode="decimal" step="0.25" value={x} onChange={(e) => setX(e.target.value)} />
            <Field label={t("y")} type="number" inputMode="decimal" step="0.25" value={y} onChange={(e) => setY(e.target.value)} />
          </div>
          <Field label={t("rotation")} type="number" inputMode="decimal" step="5" min={-360} max={360} value={rotation} onChange={(e) => setRotation(e.target.value)} />
          <label className="flex min-h-11 items-center gap-3 text-sm font-bold">
            <input type="checkbox" checked={accessible} onChange={(e) => setAccessible(e.target.checked)} className="h-5 w-5 accent-primary" />
            {t("accessible")}
          </label>
          {general ? (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
              {general}
            </p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Button type="submit">{t("save")}</Button>
            <Button variant="danger" onClick={() => void remove()}>
              {t("deleteTable")}
            </Button>
          </div>
        </form>
      ) : (
        <p className="text-sm text-muted">
          {t("seatsShort", { count: table.seats })} · {t(`shape${table.shape}`)}
        </p>
      )}
    </Shell>
  );
}

/** Details of the selected non-bookable element (wall, door, bar...). Owner and manager only change these. */
export function ObstacleInspector({ obstacle, canEdit, onChanged, onDeleted }: { obstacle: Obstacle; canEdit: boolean; onChanged: () => void; onDeleted: () => void }) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const [type, setType] = useState(obstacle.type);
  const [name, setName] = useState(obstacle.name);
  const [width, setWidth] = useState(String(obstacle.widthMeters));
  const [height, setHeight] = useState(String(obstacle.heightMeters));
  const [rotation, setRotation] = useState(String(obstacle.rotationDegrees));
  const [x, setX] = useState(String(obstacle.xMeters));
  const [y, setY] = useState(String(obstacle.yMeters));
  const [problem, setProblem] = useState<ApiError | null>(null);

  async function save(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      await unwrap(
        api.PUT("/api/v1/obstacles/{id}", {
          params: { path: { id: obstacle.id } },
          body: { type, name, widthMeters: n(width), heightMeters: n(height), rotationDegrees: n(rotation), xMeters: n(x), yMeters: n(y) },
        }),
      );
      onChanged();
    } catch (e) {
      setProblem(e as ApiError);
    }
  }

  async function remove() {
    try {
      await expectOk(api.DELETE("/api/v1/obstacles/{id}", { params: { path: { id: obstacle.id } } }));
      onDeleted();
    } catch (e) {
      setProblem(e as ApiError);
    }
  }

  const fe = problem?.fields ?? {};
  return (
    <Shell title={obstacle.name || te(obstacle.type)}>
      {canEdit ? (
        <form onSubmit={save} className="flex flex-col gap-3" noValidate>
          <SelectField label={t("elementType")} value={type} onChange={(e) => setType(e.target.value as Obstacle["type"])}>
            {OBSTACLE_TYPES.map((o) => (
              <option key={o} value={o}>
                {te(o)}
              </option>
            ))}
          </SelectField>
          <Field label={t("label")} value={name} onChange={(e) => setName(e.target.value)} maxLength={40} />
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("width")} type="number" inputMode="decimal" step="0.05" min={0.05} value={width} onChange={(e) => setWidth(e.target.value)} error={fe.widthMeters} />
            <Field label={t("height")} type="number" inputMode="decimal" step="0.05" min={0.05} value={height} onChange={(e) => setHeight(e.target.value)} error={fe.heightMeters} />
            <Field label={t("x")} type="number" inputMode="decimal" step="0.25" value={x} onChange={(e) => setX(e.target.value)} />
            <Field label={t("y")} type="number" inputMode="decimal" step="0.25" value={y} onChange={(e) => setY(e.target.value)} />
          </div>
          <Field label={t("rotation")} type="number" inputMode="decimal" step="5" min={-360} max={360} value={rotation} onChange={(e) => setRotation(e.target.value)} />
          {problem && Object.keys(fe).length === 0 ? (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
              {problem.message}
            </p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Button type="submit">{t("save")}</Button>
            <Button variant="danger" onClick={() => void remove()}>
              {t("deleteElement")}
            </Button>
          </div>
        </form>
      ) : null}
    </Shell>
  );
}
