/**
 * The pure part of the staff assistant chat: what a conversation is made of, what is sent back to the
 * server as history, when a proposal has run out, which starter questions a role sees and how a booking
 * date reads. No React and no browser here, so all of it is easy to test.
 */
import type { components } from "@/api/schema";
import type { Role } from "@/components/nav";

export type Proposal = components["schemas"]["ProposalView"];
export type ConfirmResult = components["schemas"]["ConfirmResult"];

/** The server accepts at most this many characters per message and per history turn. */
export const MAX_MESSAGE_CHARS = 1000;
/** ...and at most this many history turns. */
export const MAX_HISTORY_TURNS = 20;

/**
 * Where a proposal card is in its life. A proposal is only a question ("shall I do this?"): nothing has
 * happened until the person presses Confirm, and once confirm has been tried (whatever came of it) the
 * proposal is used up on the server.
 */
export type ProposalState =
  | { status: "pending" }
  | { status: "confirming" }
  | { status: "done"; result: ConfirmResult }
  /**
   * "gone": the server no longer has it (expired, or not yours).
   * "network": no answer came back, so nobody knows whether it was done.
   * "error": the server refused, with its explanation in `message`.
   */
  | { status: "failed"; reason: "gone" | "network" | "error"; message?: string }
  | { status: "dismissed" }
  | { status: "expired" };

export interface ProposalCard {
  proposal: Proposal;
  state: ProposalState;
}

export type NoticeCode = (typeof NOTICE_CODES)[number];

/** The notices the server can send. The screen shows a fixed, translated line for each, never the server's own text. */
export const NOTICE_CODES = ["ALLERGEN_CONFIRM_WITH_KITCHEN", "SAFETY_CLAIM_REMOVED", "TOO_MANY_STEPS", "NOTHING_CHANGED"] as const;

/** Keeps the notice codes we know, once each, in the order received. Anything else is ignored. */
export function knownNotices(codes: readonly string[]): NoticeCode[] {
  const known: NoticeCode[] = [];
  for (const code of codes) {
    if ((NOTICE_CODES as readonly string[]).includes(code) && !known.includes(code as NoticeCode)) {
      known.push(code as NoticeCode);
    }
  }
  return known;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  /** Plain text, shown as plain text. It can contain what a guest wrote, so it is never treated as markup. */
  text: string;
  proposals?: ProposalCard[];
  notices?: NoticeCode[];
  /** A message that says the assistant could not answer. Shown in the danger style and never sent back as history. */
  error?: boolean;
}

export interface HistoryTurn {
  role: "user" | "assistant";
  content: string;
}

/**
 * The turns to send along with a new message, so the assistant remembers the conversation: only what the
 * person and the assistant said in words (no errors, no proposal cards, no notices), trimmed, each cut to the
 * server's limit, empty ones left out, and only the most recent ones.
 */
export function toHistory(
  messages: readonly { role: string; text: string; error?: boolean }[],
  maxTurns: number = MAX_HISTORY_TURNS,
): HistoryTurn[] {
  const turns: HistoryTurn[] = [];
  for (const message of messages) {
    if (message.role !== "user" && message.role !== "assistant") continue;
    if (message.error) continue;
    const content = (message.text ?? "").trim().slice(0, MAX_MESSAGE_CHARS).trim();
    if (content === "") continue;
    turns.push({ role: message.role, content });
  }
  return maxTurns <= 0 ? [] : turns.slice(-maxTurns);
}

/** Whether a proposal has run out. A time nobody can read counts as run out: better no Confirm button than a doubtful one. */
export function isExpired(expiresAt: string, now: number): boolean {
  const at = Date.parse(expiresAt);
  return Number.isNaN(at) || now >= at;
}

/** What a proposal does. Anything the screen does not know has no card at all, so it can never be confirmed blindly. */
export function proposalKind(kind: string): "create" | "cancel" | null {
  if (kind === "CREATE_RESERVATION") return "create";
  if (kind === "CANCEL_RESERVATION") return "cancel";
  return null;
}

/**
 * Whether the floating assistant button is shown on a page. Not on the sign-in pages (it belongs to the signed-in
 * shell) and not on the kitchen screen, a wall tablet that must stay clean.
 */
export function showsAssistantButton(pathname: string): boolean {
  const hidden = ["/login", "/signup", "/recover", "/kitchen"];
  return !hidden.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

/** The starter questions, as keys in the "assistant.suggestions" translations. The chip's text is what gets sent. */
export type SuggestionKey = "whoBookedTonight" | "freeTables" | "kitchenQueue" | "menu" | "bookExample";

/** Starter chips for a role. Only owner and manager can have the assistant change bookings, so only they get the one that does. */
export function suggestionsFor(role: Role): SuggestionKey[] {
  switch (role) {
    case "OWNER":
    case "MANAGER":
      return ["whoBookedTonight", "freeTables", "kitchenQueue", "bookExample"];
    case "WAITER":
      return ["whoBookedTonight", "freeTables", "kitchenQueue"];
    case "KITCHEN":
      return ["kitchenQueue", "menu"];
    default:
      return ["kitchenQueue", "menu"];
  }
}

/**
 * A booking date and time for people: "Sun 1 Nov, 21:00" (weekday and month in the language of the screen,
 * always day before month, always the 24-hour clock). The date is read as a plain calendar date and formatted
 * in UTC from a UTC moment, so neither the device's time zone nor a daylight-saving change can shift the day or the hour.
 * Input that is not YYYY-MM-DD and HH:mm is returned as it came.
 */
export function describeProposalDate(date: string, time: string, locale: string): string {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const t = /^(\d{2}):(\d{2})$/.exec(time);
  if (!d || !t) return `${date} ${time}`.trim();
  const moment = new Date(Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2])));
  if (Number.isNaN(moment.getTime())) return `${date} ${time}`;
  try {
    const part = (options: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat(locale, { timeZone: "UTC", ...options }).format(moment);
    const weekday = part({ weekday: "short" });
    const day = part({ day: "numeric" });
    const month = part({ month: "short" });
    const clock = part({ hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
    return `${weekday} ${day} ${month}, ${clock}`;
  } catch {
    // An unknown locale tag: fall back to the plain values rather than failing to draw the card.
    return `${date} ${time}`;
  }
}
