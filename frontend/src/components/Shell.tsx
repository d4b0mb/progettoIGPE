"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { User } from "@/api/session";
import { useSession } from "@/api/session";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { Icon } from "@/components/ui/Icon";
import { navFor } from "./nav";

/**
 * The frame around every signed-in screen. On a desktop the navigation is a
 * sidebar; on a phone it becomes a bottom bar within thumb reach, which is also
 * where a waiter's hand already is.
 */
export function Shell({ user, children }: { user: User; children: React.ReactNode }) {
  const t = useTranslations("nav");
  const tc = useTranslations("common");
  const tr = useTranslations("roles");
  const { signOut } = useSession();
  const pathname = usePathname();
  const items = navFor(user.role);

  const linkClass = (active: boolean) =>
    active ? "bg-sidebar-active text-white" : "text-sidebar-text hover:bg-sidebar-active/60";

  return (
    <div className="flex min-h-dvh flex-col md:flex-row">
      {/* Desktop sidebar */}
      <aside className="hidden w-60 shrink-0 flex-col justify-between bg-sidebar p-4 text-sidebar-text md:flex">
        <div className="flex flex-col gap-6">
          <Link href="/" className="flex items-center gap-2.5 px-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-[9px] bg-accent font-extrabold text-sidebar">R</span>
            <span className="flex flex-col leading-tight">
              <span className="text-[15px] font-extrabold text-white">{tc("appName")}</span>
              <span className="text-xs text-sidebar-muted">{tr(user.role)}</span>
            </span>
          </Link>
          <nav aria-label={t("main")} className="flex flex-col gap-1">
            {items.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                aria-current={pathname.startsWith(item.href) ? "page" : undefined}
                className={`flex min-h-11 items-center gap-3 rounded-[10px] px-3 text-sm font-semibold ${linkClass(pathname.startsWith(item.href))}`}
              >
                <Icon name={item.icon} />
                {t(item.key)}
              </Link>
            ))}
          </nav>
        </div>
        <div className="flex flex-col gap-3">
          <p className="truncate px-2 text-sm text-sidebar-muted">{user.displayName}</p>
          <LanguageSwitch tone="dark" />
          <button
            type="button"
            onClick={() => void signOut()}
            className="flex min-h-11 items-center gap-3 rounded-[10px] px-3 text-sm font-semibold text-sidebar-text hover:bg-sidebar-active/60"
          >
            <Icon name="logout" />
            {tc("signOut")}
          </button>
        </div>
      </aside>

      {/* Phone top bar */}
      <header className="flex items-center justify-between gap-3 bg-sidebar px-4 py-2.5 text-white md:hidden">
        <Link href="/" className="flex items-center gap-2">
          <span className="flex h-8 w-8 items-center justify-center rounded-[9px] bg-accent font-extrabold text-sidebar">R</span>
          <span className="text-[15px] font-extrabold">{tc("appName")}</span>
        </Link>
        <div className="flex items-center gap-2">
          <LanguageSwitch tone="dark" />
          <button
            type="button"
            aria-label={tc("signOut")}
            onClick={() => void signOut()}
            className="flex min-h-11 min-w-11 items-center justify-center rounded-[10px] text-sidebar-text hover:bg-sidebar-active/60"
          >
            <Icon name="logout" />
          </button>
        </div>
      </header>

      <main className="min-w-0 flex-1 px-4 py-5 pb-24 sm:px-6 md:px-8 md:py-7 md:pb-8">{children}</main>

      {/* Phone bottom navigation */}
      {items.length > 0 ? (
        <nav
          aria-label={t("main")}
          className="fixed inset-x-0 bottom-0 z-10 flex overflow-x-auto border-t border-line bg-surface pb-[env(safe-area-inset-bottom)] md:hidden"
        >
          {items.map((item) => {
            const active = pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={`flex min-h-14 min-w-20 flex-1 flex-col items-center justify-center gap-0.5 px-2 text-[11px] font-bold ${
                  active ? "text-primary" : "text-muted"
                }`}
              >
                <Icon name={item.icon} />
                {t(item.key)}
              </Link>
            );
          })}
        </nav>
      ) : null}
    </div>
  );
}
