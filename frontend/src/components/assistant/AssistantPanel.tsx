"use client";

import { useLocale, useTranslations } from "next-intl";
import { useEffect, useId, useRef, useState, type KeyboardEvent, type RefObject } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Icon } from "@/components/ui/Icon";
import { useAssistant } from "./AssistantProvider";
import { useVoice, type VoiceApi } from "./useVoice";
import {
  canConfirm,
  describeProposalDate,
  isExpired,
  listMaxHeight,
  MAX_MESSAGE_CHARS,
  proposalKind,
  suggestionsFor,
  type ChatMessage,
  type ConfirmFailure,
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
  const locale = useLocale();
  const { isOpen, close, send, busy, messages } = useAssistant();
  const [draft, setDraft] = useState("");
  // Voice lives here, next to the draft and outside the sheet's content, so it can stop everything when the sheet closes.
  // What it hears is sent through the ordinary send(), or put in the text box: it never confirms anything.
  const voice = useVoice({
    open: isOpen,
    locale,
    send,
    busy,
    messages,
    onDraft: (text) => setDraft((current) => (current.trim() === "" ? text : `${current.trimEnd()} ${text}`).slice(0, MAX_MESSAGE_CHARS)),
  });
  return (
    <Dialog open={isOpen} onClose={close} title={t("title")}>
      <AssistantBody draft={draft} setDraft={setDraft} voice={voice} />
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

/**
 * The height of what is really visible, which on a phone is less than the window while the on-screen keyboard is up
 * (the keyboard covers the window instead of shrinking it, so dvh units do not change).
 */
function useVisibleHeight(): number {
  const [height, setHeight] = useState<number>(() => window.visualViewport?.height ?? window.innerHeight);
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => setHeight(viewport.height);
    viewport.addEventListener("resize", update);
    return () => viewport.removeEventListener("resize", update);
  }, []);
  return height;
}

function AssistantBody({ draft, setDraft, voice }: { draft: string; setDraft: (text: string) => void; voice: VoiceApi }) {
  const t = useTranslations("assistant");
  const { role, messages, busy, send, cancel, confirm, dismiss, clear } = useAssistant();
  const now = useNow(CLOCK_MS);
  const visibleHeight = useVisibleHeight();
  const inputId = useId();
  const hintId = useId();
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const micRef = useRef<HTMLButtonElement>(null);

  // Always show the newest message (and the "thinking" line under it), also when the keyboard changes the list's height.
  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages, busy, visibleHeight]);

  // When the sheet is opened again the list is first drawn while the dialog is still closed (it has no height yet),
  // so scroll once more a tick later, when it is on screen.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      const list = listRef.current;
      if (list) list.scrollTop = list.scrollHeight;
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  // With the keyboard up, keep the input in view.
  useEffect(() => {
    inputRef.current?.scrollIntoView({ block: "nearest" });
  }, [visibleHeight]);

  // Focus the input when the sheet opens and again after every answer (a disabled field loses focus).
  // Deferred a tick: the sheet itself is opened by the Dialog just after this content first appears.
  useEffect(() => {
    if (busy) return;
    const timer = window.setTimeout(() => {
      // Someone talking to the assistant stays on the microphone button: the text box would only raise the keyboard.
      if (document.activeElement === micRef.current) return;
      inputRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [busy]);

  // A transcript put in the text box (when sending is manual) is there to be read and corrected: move to it.
  useEffect(() => {
    if (voice.drafted > 0) inputRef.current?.focus();
  }, [voice.drafted]);

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
    ask(text, () => setDraft(""));
  }

  /** Sends typed or chosen text. Whatever voice was doing stops first (a typed message is also a tap that readies the speaker). */
  function ask(text: string, beforeSend?: () => void) {
    voice.stopAll();
    voice.unlockAudio();
    beforeSend?.();
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
          <button
            type="button"
            onClick={() => {
              // Forgetting the conversation also stops any recording, request or voice that belongs to it.
              voice.stopAll();
              clear();
            }}
            className="flex min-h-11 items-center px-2 text-sm font-bold text-muted underline hover:text-ink">
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
        style={{ maxHeight: listMaxHeight(visibleHeight) }}
        className="flex min-h-24 flex-col gap-3 overflow-y-auto"
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
                    onClick={() => ask(t(`suggestions.${key}`))}
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

      {voice.available ? <VoiceNotices voice={voice} /> : null}

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
        {voice.available && voice.secure ? <MicButton voice={voice} buttonRef={micRef} hintId={hintId} /> : null}
        <Button type="submit" disabled={busy || draft.trim() === ""} className="min-h-12 min-w-20">
          {t("send")}
        </Button>
      </form>
      {draft.length > COUNTER_FROM ? (
        <p className="-mt-1 text-right text-xs text-muted">{t("charCount", { count: draft.length, max: MAX_MESSAGE_CHARS })}</p>
      ) : null}

      {voice.available && !voice.secure ? (
        // The browser only gives the microphone to https pages: say so calmly instead of showing a button that cannot work.
        <p className="rounded-lg bg-info-soft px-3 py-2 text-sm font-semibold text-info-ink">{t("voice.insecure")}</p>
      ) : null}

      {voice.available ? <VoiceSettings voice={voice} hintId={hintId} /> : null}
    </div>
  );
}

/** The notices about voice: what it is doing right now, and why it failed. A live region, so it is read out as it changes. */
function VoiceNotices({ voice }: { voice: VoiceApi }) {
  const t = useTranslations("assistant.voice");
  const td = useTranslations("assistant");
  return (
    <>
      <div role="status" className="flex flex-col empty:hidden">
        {voice.state === "listening" ? (
          <div className="flex items-center justify-between gap-3 rounded-lg bg-info-soft px-3 py-2">
            <p className="min-w-0 text-sm font-semibold text-info-ink">{t("listening")}</p>
            <LevelMeter level={voice.level} />
          </div>
        ) : voice.state === "transcribing" ? (
          <p className="rounded-lg bg-info-soft px-3 py-2 text-sm font-semibold text-info-ink">{t("transcribing")}</p>
        ) : voice.state === "speaking" ? (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-info-soft px-3 py-2">
            <p className="text-sm font-semibold text-info-ink">{t("speaking")}</p>
            <Button variant="secondary" onClick={voice.stopSpeaking}>
              {t("stopSpeaking")}
            </Button>
          </div>
        ) : voice.notHeard ? (
          <p className="rounded-lg bg-info-soft px-3 py-2 text-sm font-semibold text-info-ink">{t("notHeard")}</p>
        ) : null}
      </div>
      {voice.error ? (
        // A fixed line for the kind of failure; what the server said is never shown.
        <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-danger-soft px-3 py-2">
          <p className="min-w-0 text-sm font-semibold text-danger-ink">{t(voice.error)}</p>
          <Button variant="secondary" onClick={voice.dismissNotice}>
            {td("dismiss")}
          </Button>
        </div>
      ) : null}
    </>
  );
}

/** How loud it is now, as a bar. Decoration only: the status line says what is going on. */
function LevelMeter({ level }: { level: number }) {
  return (
    <div aria-hidden="true" className="h-2 w-20 shrink-0 overflow-hidden rounded-full bg-surface">
      <div
        className="h-full rounded-full bg-primary transition-[width] duration-100 motion-reduce:transition-none"
        style={{ width: `${Math.round(Math.min(1, Math.max(0, level)) * 100)}%` }}
      />
    </div>
  );
}

/** A small spinner; it only turns for people who have not asked for less motion. */
function Spinner() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" aria-hidden="true" className="h-5 w-5 motion-safe:animate-spin">
      <circle cx="12" cy="12" r="9" className="opacity-25" />
      <path d="M21 12a9 9 0 0 0-9-9" />
    </svg>
  );
}

/**
 * The microphone button, next to Send. Tap to start (it stops by itself after you stop talking, or tap again to send),
 * or hold it down while talking and let go to send. It is never really disabled while the assistant is thinking
 * (aria-disabled instead), so the focus does not jump away from it.
 */
function MicButton({ voice, buttonRef, hintId }: { voice: VoiceApi; buttonRef: RefObject<HTMLButtonElement | null>; hintId: string }) {
  const t = useTranslations("assistant.voice");
  const listening = voice.state === "listening";
  const working = voice.state === "transcribing";
  const blocked = voice.state === "thinking";
  const name = listening ? t("stopAndSend") : working ? t("cancel") : t("talk");
  // Pulses while listening (not for people who asked for less motion). touch-none: holding the button must not
  // scroll or select anything on a phone.
  const look = listening
    ? "border-danger bg-danger text-on-primary motion-safe:animate-pulse"
    : "border-line-strong bg-surface text-ink hover:bg-canvas";
  return (
    <button
      ref={buttonRef}
      type="button"
      aria-label={name}
      aria-describedby={hintId}
      aria-disabled={blocked ? true : undefined}
      {...voice.micProps}
      className={`flex h-12 w-12 shrink-0 touch-none items-center justify-center rounded-full border select-none transition-colors aria-disabled:opacity-60 ${look}`}
    >
      {working ? <Spinner /> : <Icon name={listening ? "stop" : "mic"} className="h-6 w-6" />}
    </button>
  );
}

/** The two switches, saved on this device. The speaker switch only exists when the server can also speak. */
function VoiceSettings({ voice, hintId }: { voice: VoiceApi; hintId: string }) {
  const t = useTranslations("assistant.voice");
  return (
    <div role="group" aria-label={t("settings")} className="flex flex-col gap-0.5">
      {voice.secure ? (
        <p id={hintId} className="text-xs text-muted">
          {t("hint")}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-x-5">
        <VoiceSwitch label={t("autoSend")} checked={voice.autoSend} onChange={voice.setAutoSend} />
        {voice.canSpeak ? <VoiceSwitch label={t("speakReplies")} checked={voice.speakReplies} onChange={voice.setSpeakReplies} /> : null}
      </div>
    </div>
  );
}

function VoiceSwitch({ label, checked, onChange }: { label: string; checked: boolean; onChange: (on: boolean) => void }) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-semibold">
      <input type="checkbox" role="switch" checked={checked} onChange={(event) => onChange(event.target.checked)} className="h-5 w-5 shrink-0 accent-primary" />
      {label}
    </label>
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
        // A fixed notice for the kind of failure; what the server said is never shown.
        <p className="rounded-2xl rounded-bl-md bg-danger-soft px-3.5 py-2 text-sm font-semibold break-words text-danger-ink">
          {t(`errors.${message.errorCode ?? "generic"}`)}
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

/** The fixed line (a key in "assistant") for each way a Confirm can fail. */
const FAILED_LINE = {
  gone: "failedGone",
  forbidden: "failedForbidden",
  conflict: "failedConflict",
  refused: "failedRefused",
  network: "failedNetwork",
  error: "failedGeneric",
} as const satisfies Record<ConfirmFailure, string>;

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
  const { role } = useAssistant();
  const mayConfirm = canConfirm(role);
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
          {t(FAILED_LINE[state.reason])}
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
          {/* The server only lets the owner and managers confirm: everyone else is told so instead of getting a button that always fails. */}
          {mayConfirm ? null : (
            <p className="rounded-lg bg-info-soft px-3 py-2 text-sm font-semibold text-info-ink">{t("onlyManagersConfirm")}</p>
          )}
          <div className="flex flex-wrap gap-2">
            {mayConfirm ? (
              <Button className="flex-1" disabled={working} onClick={() => onConfirm(proposal.id)}>
                {working ? t("working") : kind === "cancel" ? t("confirmCancel") : t("confirmCreate")}
              </Button>
            ) : null}
            <Button variant="secondary" className="flex-1" disabled={working} onClick={() => onDismiss(proposal.id)}>
              {t("dismiss")}
            </Button>
          </div>
        </section>
      );
    }
  }
}
