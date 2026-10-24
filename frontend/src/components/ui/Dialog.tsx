"use client";

import { useEffect, useRef, type ReactNode } from "react";

/**
 * A modal built on the browser's own <dialog>: it traps focus, closes on Escape
 * and is announced properly by screen readers. On a phone it slides up as a sheet.
 */
export function Dialog({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      onClose={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
      className="m-0 mt-auto max-h-[92dvh] w-full max-w-none overflow-y-auto rounded-t-2xl bg-surface p-0 text-ink backdrop:bg-black/50 sm:m-auto sm:max-w-xl sm:rounded-2xl"
    >
      {open ? (
        <div className="flex flex-col gap-5 px-5 pb-5 sm:px-6 sm:pb-6">
          {/* Stays visible while a long form scrolls underneath. */}
          <h2 id="dialog-title" className="sticky top-0 z-10 -mx-5 bg-surface px-5 pt-5 pb-3 text-xl font-extrabold tracking-tight sm:-mx-6 sm:px-6 sm:pt-6">
            {title}
          </h2>
          {children}
        </div>
      ) : null}
    </dialog>
  );
}
