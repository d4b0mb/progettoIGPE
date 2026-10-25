import { useRestaurant } from "@/api/restaurant";

/** A red pizza slice with a drip of cheese. Drawn here, so it works offline and in any size. */
export function SliceMark({ className = "h-8 w-8" }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" className={className} aria-hidden="true">
      <path d="M3 25 L33 7 A27 27 0 0 1 43 33 Z" fill="#e0261d" />
      <path d="M36 9.5 A27 27 0 0 1 42.5 29" fill="none" stroke="#f3e7d3" strokeWidth="1.6" strokeLinecap="round" opacity="0.55" />
      <circle cx="26" cy="19" r="3" fill="#f3e7d3" />
      <circle cx="33.5" cy="25.5" r="3.4" fill="#f3e7d3" />
      <circle cx="20" cy="26" r="1.9" fill="#f3e7d3" />
      <path d="M11 28 C11 33 9 34 9 38 A2.4 2.4 0 0 0 14 38 C14 34 15 33 15 27 Z" fill="#e0261d" />
    </svg>
  );
}

/** The mark and the name in the restaurant's own look. The classic look keeps the app's own badge. */
export function Brand({ fallbackName, subtitle }: { fallbackName: string; subtitle?: string }) {
  const { name, theme } = useRestaurant();
  if (theme === "DUODECIM") {
    return (
      <>
        <SliceMark className="h-9 w-9 shrink-0" />
        <span className="flex min-w-0 flex-col leading-tight">
          <span className="font-display text-[17px] leading-5 font-bold tracking-wide text-white uppercase">{name ?? fallbackName}</span>
          {subtitle ? <span className="text-xs text-sidebar-muted">{subtitle}</span> : null}
        </span>
      </>
    );
  }
  return (
    <>
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-[9px] bg-accent font-extrabold text-sidebar">R</span>
      <span className="flex min-w-0 flex-col leading-tight">
        <span className="truncate text-[15px] font-extrabold text-white">{name ?? fallbackName}</span>
        {subtitle ? <span className="text-xs text-sidebar-muted">{subtitle}</span> : null}
      </span>
    </>
  );
}
