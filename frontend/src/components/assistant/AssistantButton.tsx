"use client";

import { useTranslations } from "next-intl";
import { usePathname } from "next/navigation";
import { Icon } from "@/components/ui/Icon";
import { useAssistant } from "./AssistantProvider";
import { showsAssistantButton } from "./chat";

/**
 * The round button that opens the assistant, bottom right on every signed-in screen except the ones that
 * must stay clean (the kitchen wall tablet) and while the sheet is already open.
 *
 * On a phone it floats above the bottom navigation bar (56 px high, plus the safe area of notched phones).
 * The order screen has its own bar of controls just above that navigation, so there the button sits higher.
 */
export function AssistantButton() {
  const t = useTranslations("assistant");
  const pathname = usePathname();
  const { isOpen, open } = useAssistant();
  if (isOpen || !showsAssistantButton(pathname)) return null;

  const position = pathname.startsWith("/orders")
    ? "bottom-[calc(18rem_+_env(safe-area-inset-bottom))] md:bottom-52"
    : "bottom-[calc(4.75rem_+_env(safe-area-inset-bottom))] md:bottom-6";

  return (
    <button
      type="button"
      aria-label={t("open")}
      onClick={open}
      className={`fixed right-4 z-20 flex h-14 w-14 items-center justify-center rounded-full bg-primary text-on-primary shadow-lg hover:bg-primary-strong md:right-6 ${position}`}
    >
      <Icon name="sparkle" className="h-6 w-6" />
    </button>
  );
}
