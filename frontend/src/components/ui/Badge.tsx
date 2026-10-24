import type { ReactNode } from "react";

type Tone = "neutral" | "good" | "warn" | "danger" | "info";

const tones: Record<Tone, string> = {
  neutral: "bg-line text-ink",
  good: "bg-primary-soft text-primary-strong",
  warn: "bg-warn-soft text-warn-ink",
  danger: "bg-danger-soft text-danger-ink",
  info: "bg-info-soft text-info-ink",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-block rounded-full px-2.5 py-1 text-xs font-bold ${tones[tone]}`}>{children}</span>;
}
