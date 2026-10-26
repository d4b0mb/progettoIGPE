"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import { ApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import type { Role } from "@/components/nav";
import { announceReservationsChanged } from "@/lib/reservationsChanged";
import {
  canConfirm,
  chatErrorFor,
  confirmFailureFor,
  isExpired,
  knownNotices,
  MAX_MESSAGE_CHARS,
  proposalKind,
  toHistory,
  type ChatMessage,
  type ProposalState,
} from "./chat";

type AssistantReply = components["schemas"]["AssistantReply"];

interface AssistantApi {
  /** The signed-in person's role: it decides which starter questions are offered. */
  role: Role;
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;
  messages: ChatMessage[];
  /** True while a question is with the assistant. */
  busy: boolean;
  /** Sends a question. Never confirms anything: only confirm() does, and only the Confirm button calls it. */
  send: (text: string) => Promise<void>;
  /** Stops waiting for the assistant. */
  cancel: () => void;
  /** Carries out a proposal the person has approved. */
  confirm: (proposalId: string) => Promise<void>;
  /** Turns a proposal down. */
  dismiss: (proposalId: string) => Promise<void>;
  /** Forgets the whole conversation. */
  clear: () => void;
}

const AssistantContext = createContext<AssistantApi | null>(null);

let counter = 0;
/** A key for a message. (crypto.randomUUID is not available on a plain-http tablet, so a counter it is.) */
const nextId = () => `m${++counter}`;

function assistantMessage(reply: AssistantReply): ChatMessage {
  return {
    id: nextId(),
    role: "assistant",
    text: reply.reply ?? "",
    // A proposal of a kind this screen does not know gets no card, so it can never be confirmed without being understood.
    proposals: (reply.proposals ?? [])
      .filter((proposal) => proposalKind(proposal.kind) !== null)
      .map((proposal) => ({ proposal, state: { status: "pending" as const } })),
    notices: knownNotices(reply.notices ?? []),
  };
}

function findCard(messages: readonly ChatMessage[], proposalId: string) {
  for (const message of messages) {
    const card = message.proposals?.find((c) => c.proposal.id === proposalId);
    if (card) return card;
  }
  return undefined;
}

/**
 * Owns the conversation with the assistant, so closing the sheet does not lose it. Mount it with a `key`
 * of the signed-in user's id: a different person (or nobody) then starts with an empty conversation.
 *
 * The one rule that matters: typing never confirms anything. Only confirm(), which only the Confirm button
 * on a proposal card calls, asks the server to carry a proposal out.
 */
export function AssistantProvider({ role, children }: { role: Role; children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [busy, setBusy] = useState(false);
  // The latest messages, readable at once from an event handler (state is only up to date after the next render).
  const messagesRef = useRef<ChatMessage[]>([]);
  // The question in flight. Also the "already waiting" flag: it is set the moment send() starts.
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const mutate = useCallback((change: (list: ChatMessage[]) => ChatMessage[]) => {
    const next = change(messagesRef.current);
    messagesRef.current = next;
    setMessages(next);
  }, []);

  const setCard = useCallback(
    (proposalId: string, state: ProposalState) => {
      mutate((list) =>
        list.map((message) =>
          message.proposals?.some((c) => c.proposal.id === proposalId)
            ? { ...message, proposals: message.proposals.map((c) => (c.proposal.id === proposalId ? { ...c, state } : c)) }
            : message,
        ),
      );
    },
    [mutate],
  );

  const send = useCallback(
    async (raw: string) => {
      const text = raw.trim().slice(0, MAX_MESSAGE_CHARS);
      if (text === "" || abortRef.current) return;
      // The history is what was said before this message.
      const history = toHistory(messagesRef.current);
      const controller = new AbortController();
      abortRef.current = controller;
      setBusy(true);
      mutate((list) => [...list, { id: nextId(), role: "user", text }]);
      try {
        const reply = await unwrap(
          api.POST("/api/v1/assistant/chat", { body: { message: text, history }, signal: controller.signal }),
        );
        if (controller.signal.aborted) return;
        mutate((list) => [...list, assistantMessage(reply)]);
      } catch (e) {
        if (controller.signal.aborted) return;
        // Only the kind of failure is kept: the panel shows a fixed, translated notice, never the server's own words.
        const errorCode = chatErrorFor(e instanceof ApiError ? e.status : null);
        mutate((list) => [...list, { id: nextId(), role: "assistant", text: "", error: true, errorCode }]);
      } finally {
        if (abortRef.current === controller) {
          abortRef.current = null;
          setBusy(false);
        }
      }
    },
    [mutate],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setBusy(false);
  }, []);

  const confirm = useCallback(
    async (proposalId: string) => {
      const card = findCard(messagesRef.current, proposalId);
      // Only a card that is waiting for an answer can be confirmed, and only once: this check and the
      // "confirming" mark below happen in the same breath, so a double tap cannot send it twice.
      if (!card || card.state.status !== "pending") return;
      // The server refuses anyone else, and using the proposal up for nothing would only lose it.
      if (!canConfirm(role)) return;
      if (isExpired(card.proposal.expiresAt, Date.now())) {
        setCard(proposalId, { status: "expired" });
        return;
      }
      setCard(proposalId, { status: "confirming" });
      try {
        // No AbortController here on purpose: once asked, the answer must be seen, not abandoned.
        const result = await unwrap(
          api.POST("/api/v1/assistant/proposals/{id}/confirm", { params: { path: { id: proposalId } } }),
        );
        setCard(proposalId, { status: "done", result });
        // The reservations list and the floor plan may be showing the old picture.
        announceReservationsChanged();
      } catch (e) {
        setCard(proposalId, { status: "failed", reason: confirmFailureFor(e instanceof ApiError ? e.status : null) });
      }
    },
    [role, setCard],
  );

  const dismiss = useCallback(
    async (proposalId: string) => {
      const card = findCard(messagesRef.current, proposalId);
      if (!card || card.state.status !== "pending") return;
      setCard(proposalId, { status: "dismissed" });
      try {
        await expectOk(api.DELETE("/api/v1/assistant/proposals/{id}", { params: { path: { id: proposalId } } }));
      } catch {
        // The card is hidden anyway, and the server drops an unused proposal by itself when it expires.
      }
    },
    [setCard],
  );

  const clear = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setBusy(false);
    messagesRef.current = [];
    setMessages([]);
  }, []);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((current) => !current), []);

  const value = useMemo<AssistantApi>(
    () => ({ role, isOpen, open, close, toggle, messages, busy, send, cancel, confirm, dismiss, clear }),
    [role, isOpen, open, close, toggle, messages, busy, send, cancel, confirm, dismiss, clear],
  );
  return <AssistantContext.Provider value={value}>{children}</AssistantContext.Provider>;
}

export function useAssistant(): AssistantApi {
  const ctx = useContext(AssistantContext);
  if (!ctx) throw new Error("useAssistant must be used inside <AssistantProvider>");
  return ctx;
}
