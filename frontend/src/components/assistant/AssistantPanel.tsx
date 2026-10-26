"use client";

import { useLocale, useTranslations } from "next-intl";
import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Icon } from "@/components/ui/Icon";
import { useAssistant } from "./AssistantProvider";
import {
  describeProposalDate,
  isExpired,
  MAX_MESSAGE_CHARS,
  proposalKind,
  suggestionsFor,
  type ChatMessage,
  type ProposalCard,
  type ProposalState,
} from "./chat";

/** How often a card is checked against the clock, so an unanswered proposal runs out while it is on screen. */
const CLOCK_MS = 30_000;
/** The counter of typed characters only appears close to the limit. */
const COUNTER_FROM = 900;

/**
 * The assistant as a sheet: it slides up on a phone and is a centred dialog on a big screen (the shared Dialog).
 * The text being typed lives here, outside the sheet's content, so closing the sheet by accident does not lose it.
 */
export function AssistantPanel() {
  const t = useTranslations("assistant");
  const { isOpen, close } = useAssistant();
  const [draft, setDraft] = useState("");
  return (
    <Dialog open={isOpen} onClose={close} title={t("title")}>
      <AssistantBody draft={draft} setDraft={setDraft} />
    </Dialog>
  );
}

/** The current time, refreshed every so often and whenever the tab comes back to the front. */
function useNow(everyMs: number): number {
  // Passed as a function (not called) so that rendering itself stays free of the clock.
  const [now, setNow] = useState<number>(Date.now);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const timer = window.setInterval(tick, everyMs);
    document.addEventListener("visibilitychange", tick);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [everyMs]);
  return now;
}

function AssistantBody({ draft, setDraft }: { draft: string; setDraft: (text: string) => void }) {
  const t = useTranslations("assistant");
  const { role, messages, busy, send, cancel, confirm, dismiss, clear } = useAssistant();
  const now = useNow(CLOCK_MS);
  const inputId = useId();
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Always show the newest message (and the "thinking" line under it).
  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages, busy]);

  // Focus the input when the sheet opens and again after every answer (a disabled field loses focus).
  // Deferred a tick: the sheet itself is opened by the Dialog just after this content first appears.
  useEffect(() => {
    if (busy) return;
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(timer);
  }, [busy]);

  // The field grows with what is typed, up to about four lines (the CSS caps it).
  useEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    input.style.height = "auto";
    input.style.height = `${input.scrollHeight + 2}px`;
  }, [draft]);

  function submit() {
    const text = draft.trim();
    if (text === "" || busy) return;
    setDraft("");
    void send(text);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends, Shift+Enter is a new line. Not while an input method is still composing a character.
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      submit();
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {messages.length > 0 ? (
        <div className="flex justify-end">
          <button type="button" onClick={clear} className="flex min-h-11 items-center px-2 text-sm font-bold text-muted underline hover:text-ink">
            {t("clear")}
          </button>
        </div>
      ) : null}

      {/* Everything shown here is plain text: what the assistant says can contain what a guest wrote. */}
      <div
        ref={listRef}
        role="log"
        aria-live="polite"
        aria-label={t("conversation")}
        tabIndex={0}
        className="flex max-h-[55dvh] min-h-24 flex-col gap-3 overflow-y-auto"
      >
        {messages.length === 0 ? (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-muted">{t("help")}</p>
            <ul aria-label={t("suggestionsLabel")} className="flex flex-wrap gap-2">
              {suggestionsFor(role).map((key) => (
                <li key={key}>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void send(t(`suggestions.${key}`))}
                    className="min-h-11 rounded-full border border-line-strong bg-surface px-4 py-2 text-left text-sm font-semibold hover:bg-canvas disabled:opacity-60"
                  >
                    {t(`suggestions.${key}`)}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          messages.map((message) => (
            <MessageView key={message.id} message={message} now={now} onConfirm={(id) => void confirm(id)} onDismiss={(id) => void dismiss(id)} />
          ))
        )}
      </div>

      {busy ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-info-soft px-3 py-2">
          <p role="status" className="text-sm font-semibold text-info-ink">
            {t("thinking")}
          </p>
          <Button variant="secondary" onClick={cancel}>
            {t("cancel")}
          </Button>
        </div>
      ) : null}

      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
        className="flex items-end gap-2"
      >
        <label htmlFor={inputId} className="sr-only">
          {t("inputLabel")}
        </label>
        <textarea
          id={inputId}
          ref={inputRef}
          rows={1}
          value={draft}
          maxLength={MAX_MESSAGE_CHARS}
          disabled={busy}
          placeholder={t("placeholder")}
          enterKeyHint="send"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          className="max-h-[7.4rem] min-h-12 min-w-0 flex-1 resize-none overflow-y-auto rounded-[10px] border border-line-strong bg-surface px-3.5 py-2.5 text-base leading-6 disabled:opacity-60"
        />
        <Button type="submit" disabled={busy || draft.trim() === ""} className="min-h-12 min-w-20">
          {t("send")}
        </Button>
      </form>
      {draft.length > COUNTER_FROM ? (
        <p className="-mt-1 text-right text-xs text-muted">{t("charCount", { count: draft.length, max: MAX_MESSAGE_CHARS })}</p>
      ) : null}
    </div>
  );
}

function MessageView({
  message,
  now,
  onConfirm,
  onDismiss,
}: {
  message: ChatMessage;
  now: number;
  onConfirm: (proposalId: string) => void;
  onDismiss: (proposalId: string) => void;
}) {
  const t = useTranslations("assistant");

  if (message.role === "user") {
    return (
      <div className="flex max-w-[88%] flex-col self-end">
        <span className="sr-only">{t("fromYou")}</span>
        <p className="rounded-2xl rounded-br-md bg-primary px-3.5 py-2 text-sm whitespace-pre-wrap break-words text-on-primary">{message.text}</p>
      </div>
    );
  }

  return (
    <div className="flex w-full max-w-[94%] flex-col gap-2 self-start">
      <span className="sr-only">{t("fromAssistant")}</span>
      {message.error ? (
        <p className="rounded-2xl rounded-bl-md bg-danger-soft px-3.5 py-2 text-sm font-semibold whitespace-pre-wrap break-words text-danger-ink">
          {message.text.trim() || t("error")}
        </p>
      ) : message.text.trim() !== "" ? (
        <p className="rounded-2xl rounded-bl-md border border-line bg-canvas px-3.5 py-2 text-sm whitespace-pre-wrap break-words">{message.text}</p>
      ) : null}
      {(message.proposals ?? []).map((card) => (
        <ProposalCardView key={card.proposal.id} card={card} now={now} onConfirm={onConfirm} onDismiss={onDismiss} />
      ))}
      {(message.notices ?? []).map((code) => (
        <p key={code} className="rounded-lg bg-info-soft px-3 py-2 text-sm font-semibold text-info-ink">
          {t(`notices.${code}`)}
        </p>
      ))}
    </div>
  );
}

/**
 * What the assistant proposes, as a card. Nothing has happened yet: the booking is only made or cancelled
 * when the person presses the Confirm button.
 */
function ProposalCardView({
  card,
  now,
  onConfirm,
  onDismiss,
}: {
  card: ProposalCard;
  now: number;
  onConfirm: (proposalId: string) => void;
  onDismiss: (proposalId: string) => void;
}) {
  const t = useTranslations("assistant");
  const locale = useLocale();
  const { proposal } = card;
  const kind = proposalKind(proposal.kind);
  if (kind === null) return null;

  // A card nobody answered runs out on screen, without waiting for anyone to press anything.
  const state: ProposalState = card.state.status === "pending" && isExpired(proposal.expiresAt, now) ? { status: "expired" } : card.state;

  switch (state.status) {
    case "dismissed":
      return <p className="px-1 text-sm text-muted">{t("dismissed")}</p>;
    case "expired":
      return <p className="rounded-lg border border-line px-3 py-2 text-sm text-muted">{t("expired")}</p>;
    case "done":
      return (
        <p role="status" className="rounded-lg bg-primary-soft px-3 py-2 text-sm font-semibold break-words text-primary-strong">
          {t(kind === "cancel" ? "doneCancel" : "doneCreate", {
            guest: state.result.guestName,
            when: describeProposalDate(state.result.date, state.result.time, locale),
          })}
        </p>
      );
    case "failed":
      return (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold break-words text-danger-ink">
          {state.reason === "gone"
            ? t("failedGone")
            : state.reason === "network"
              ? t("failedNetwork")
              : state.message?.trim() || t("failedGeneric")}
        </p>
      );
    case "pending":
    case "confirming": {
      const working = state.status === "confirming";
      const title = kind === "cancel" ? t("cardCancel") : t("cardCreate");
      return (
        <section aria-label={title} className="flex flex-col gap-3 rounded-xl border border-line-strong bg-surface p-3">
          <h3 className="flex items-center gap-2 text-sm font-extrabold text-primary">
            <Icon name={kind === "cancel" ? "calendarX" : "calendar"} />
            {title}
          </h3>
          <div className="flex flex-col gap-0.5">
            <p className="text-base font-bold break-words">{proposal.guestName}</p>
            <p className="text-sm text-muted">
              {t("guests", { count: proposal.partySize })} · {describeProposalDate(proposal.date, proposal.time, locale)}
            </p>
            <p className="text-sm text-muted">
              {proposal.tableNumber != null ? t("table", { number: proposal.tableNumber }) : t("noTable")}
            </p>
            {proposal.notes.trim() !== "" ? (
              <p className="text-sm whitespace-pre-wrap break-words">{t("notes", { notes: proposal.notes })}</p>
            ) : null}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button className="flex-1" disabled={working} onClick={() => onConfirm(proposal.id)}>
              {working ? t("working") : kind === "cancel" ? t("confirmCancel") : t("confirmCreate")}
            </Button>
            <Button variant="secondary" className="flex-1" disabled={working} onClick={() => onDismiss(proposal.id)}>
              {t("dismiss")}
            </Button>
          </div>
        </section>
      );
    }
  }
}
