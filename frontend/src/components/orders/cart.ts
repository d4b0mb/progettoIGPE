/**
 * The order being taken. Every line is one dish in one serving, with its options and a free note. Two pizzas with
 * different toppings are two lines; the same dish with the same options and note is always one line.
 */
export interface CartLine {
  id: string;
  itemId: string;
  serving: number;
  quantity: number;
  /** Chosen option ids, kept sorted so the same choice always looks the same. */
  modifierIds: string[];
  note: string;
}

export const MAX_QUANTITY = 99;

let counter = 0;
/** A short id that works on any page (crypto.randomUUID needs https, and this app may run on plain http). */
export function newLineId(): string {
  counter += 1;
  return `line-${Date.now().toString(36)}-${counter}`;
}

/** A line with no options and no note: what the plain + and − buttons change. */
export function isPlain(line: Pick<CartLine, "modifierIds" | "note">): boolean {
  return line.modifierIds.length === 0 && line.note.trim() === "";
}

const sameChoice = (a: CartLine, b: CartLine) =>
  a.itemId === b.itemId && a.serving === b.serving && a.note.trim() === b.note.trim() && a.modifierIds.join() === b.modifierIds.join();

function clean(line: CartLine): CartLine {
  return { ...line, note: line.note.trim(), modifierIds: [...new Set(line.modifierIds)].sort(), quantity: Math.max(1, Math.min(MAX_QUANTITY, line.quantity)) };
}

/** Adds a line, joining it to an identical one if there is already one. */
export function addLine(lines: CartLine[], line: CartLine): CartLine[] {
  const next = clean(line);
  const twin = lines.find((l) => sameChoice(l, next));
  if (!twin) return [...lines, next];
  return lines.map((l) => (l === twin ? { ...l, quantity: Math.min(MAX_QUANTITY, l.quantity + next.quantity) } : l));
}

/** The plain + and − of a dish: changes the line without options, creating or removing it as needed. */
export function nudgePlain(lines: CartLine[], itemId: string, serving: number, delta: number): CartLine[] {
  const plain = lines.find((l) => l.itemId === itemId && l.serving === serving && isPlain(l));
  if (!plain) {
    return delta > 0 ? [...lines, { id: newLineId(), itemId, serving, quantity: Math.min(delta, MAX_QUANTITY), modifierIds: [], note: "" }] : lines;
  }
  const quantity = Math.min(MAX_QUANTITY, plain.quantity + delta);
  return quantity <= 0 ? lines.filter((l) => l !== plain) : lines.map((l) => (l === plain ? { ...l, quantity } : l));
}

/** Changes a line (its options, note or amount). If it now matches another line, the two become one. */
export function replaceLine(lines: CartLine[], id: string, change: Partial<Omit<CartLine, "id">>): CartLine[] {
  const current = lines.find((l) => l.id === id);
  if (!current) return lines;
  const rest = lines.filter((l) => l.id !== id);
  const at = lines.indexOf(current);
  const updated = clean({ ...current, ...change });
  const twin = rest.find((l) => sameChoice(l, updated));
  if (twin) return rest.map((l) => (l === twin ? { ...l, quantity: Math.min(MAX_QUANTITY, l.quantity + updated.quantity) } : l));
  return [...rest.slice(0, at), updated, ...rest.slice(at)];
}

export function removeLine(lines: CartLine[], id: string): CartLine[] {
  return lines.filter((l) => l.id !== id);
}

/** How many of a dish are in a serving, options or not. */
export function quantityOf(lines: CartLine[], itemId: string, serving: number): number {
  return lines.filter((l) => l.itemId === itemId && l.serving === serving).reduce((sum, l) => sum + l.quantity, 0);
}

/** Price of one line in cents: the dish plus every chosen option, times the amount. A missing price counts as zero. */
export function lineCents(line: CartLine, dishCents: number | null | undefined, optionCents: Record<string, number>): number {
  const options = line.modifierIds.reduce((sum, id) => sum + (optionCents[id] ?? 0), 0);
  return ((dishCents ?? 0) + options) * line.quantity;
}
