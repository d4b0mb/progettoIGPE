/** Groups items by their menu section, keeping the order in which sections first appear. */
export function groupBySection<T extends { section: string }>(items: T[]): { section: string; items: T[] }[] {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const list = groups.get(item.section) ?? [];
    list.push(item);
    groups.set(item.section, list);
  }
  return [...groups.entries()].map(([section, list]) => ({ section, items: list }));
}
