import { expect, request, test } from "@playwright/test";
import { demo, PASSWORD, removeLeftovers, signedIn } from "./helpers";

const GUEST = "Playwright floor";

const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

test("the status of a table on the floor plan opens the bookings of today, the seated party first", async ({ browser, baseURL }) => {
  // The seated party started 5 minutes ago, the next one comes two hours from now; both must fall on today's date.
  const now = Date.now();
  const seatedStart = new Date(now - 5 * 60_000);
  const laterStart = new Date(now + 2 * 3600_000);
  test.skip(ymd(seatedStart) !== ymd(laterStart), "too close to midnight: the second booking would fall on tomorrow");
  await removeLeftovers(baseURL!, ymd(new Date(now)), GUEST);

  const { context, page } = await signedIn(browser, demo.manager, { width: 1440, height: 1000 });
  const api = await request.newContext({ baseURL });
  const login = await api.post("/api/v1/auth/login", { data: { email: demo.manager, password: PASSWORD } });
  const headers = { Authorization: `Bearer ${((await login.json()) as { accessToken: string }).accessToken}` };
  const ids: string[] = [];
  try {
    const tables = (await (await api.get("/api/v1/tables", { headers })).json()) as { id: string; number: number }[];
    const table = tables.find((t) => t.number === 5)!;
    // One hour each, so the two bookings do not overlap (the server refuses overlaps on one table).
    const book = async (name: string, start: Date, status: "ARRIVED" | "CONFIRMED") => {
      const made = await api.post("/api/v1/reservations", {
        headers,
        data: { guestName: name, phone: "+39 333 765 4321", startsAt: start.toISOString(), durationMinutes: 60, partySize: 2, tableId: table.id, status },
      });
      expect(made.status(), await made.text()).toBe(201);
      ids.push(((await made.json()) as { id: string }).id);
    };
    // Created in the "wrong" order on purpose: the list must still put the seated party first.
    await book(`${GUEST} B`, laterStart, "CONFIRMED");
    await book(`${GUEST} A`, seatedStart, "ARRIVED");

    await page.goto("/floor");
    await page.getByRole("button", { name: /^Table 5,/ }).click();
    await page.getByRole("button", { name: "Occupied, show bookings" }).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("heading", { name: "Table 5: bookings today" })).toBeVisible();
    await expect(dialog.getByText("2 bookings today")).toBeVisible();
    const cards = dialog.getByRole("listitem");
    await expect(cards).toHaveCount(2);
    await expect(cards.nth(0)).toContainText(`${GUEST} A`);
    await expect(cards.nth(0)).toContainText("Seated now");
    await expect(cards.nth(1)).toContainText(`${GUEST} B`);
    await expect(cards.nth(1)).toContainText("Next");
    await expect(dialog.getByRole("link", { name: "+39 333 765 4321" }).first()).toHaveAttribute("href", "tel:+393337654321");

    await dialog.getByRole("button", { name: "Close" }).click();
    await expect(dialog).toBeHidden();
  } finally {
    for (const id of ids) await api.delete(`/api/v1/reservations/${id}`, { headers });
    await api.dispose();
    await context.close();
  }
});
