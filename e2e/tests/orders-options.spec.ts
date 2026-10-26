import { expect, request, test } from "@playwright/test";
import { demo, duodecim, PASSWORD, removeLeftovers, signedIn } from "./helpers";

test("toppings and free notes reach the kitchen on the ticket", async ({ browser }) => {
  const kitchen = await signedIn(browser, duodecim.kitchen);
  const waiter = await signedIn(browser, duodecim.waiter);
  const page = waiter.page;
  await page.goto("/orders");
  await page.getByRole("button", { name: /^Table 1 / }).click();

  // A pizza with two options and a note.
  await page.getByRole("button", { name: "Customise Margherita", exact: true }).click();
  const sheet = page.getByRole("dialog");
  await expect(sheet.getByRole("heading", { name: "Options and notes: Margherita" })).toBeVisible();
  await sheet.getByLabel("Bufala in cottura").check();
  await sheet.getByLabel("Senza basilico").check();
  await sheet.getByLabel("Note for this item").fill("senza pepe");
  await sheet.getByRole("button", { name: "Add to order" }).click();
  await expect(sheet).toBeHidden();
  const lines = page.getByRole("list", { name: "Customised Margherita" });
  await expect(lines).toContainText("Bufala in cottura · Senza basilico");
  await expect(lines).toContainText("senza pepe");

  // A drink has no options, but still takes a note.
  await page.getByRole("button", { name: "Customise Coca Cola 0,33 (vetro)", exact: true }).click();
  await expect(page.getByRole("dialog").getByText("There are no options for this kind of dish")).toBeVisible();
  await page.getByRole("dialog").getByLabel("Note for this item").fill("con ghiaccio");
  await page.getByRole("dialog").getByRole("button", { name: "Add to order" }).click();

  await page.getByRole("button", { name: /Send to kitchen/ }).click();
  const sent = page.getByText(/Order C-\d+ sent to the kitchen/);
  await expect(sent).toBeVisible();
  const code = /C-\d+/.exec((await sent.textContent()) ?? "")![0];

  const card = kitchen.page.getByRole("article", { name: code });
  await expect(card).toContainText("Margherita");
  await expect(card).toContainText("+ Bufala in cottura · Senza basilico");
  await expect(card).toContainText("“senza pepe”");
  await expect(card).toContainText("“con ghiaccio”");

  // Clear the ticket so the kitchen screen is left tidy.
  await card.getByRole("button", { name: "Start preparing" }).click();
  await kitchen.page.getByRole("article", { name: code }).getByRole("button", { name: "Mark ready" }).click();
  await page.getByRole("article", { name: code }).getByRole("button", { name: "Served" }).click();
  await expect(kitchen.page.getByRole("article", { name: code })).toHaveCount(0);
  await kitchen.context.close();
  await waiter.context.close();
});

test("the owner sees the pizza options and the waiter can read them but not edit", async ({ browser }) => {
  const owner = await signedIn(browser, duodecim.owner);
  await owner.page.goto("/menu");
  await owner.page.getByRole("button", { name: "Toppings and options" }).click();
  await expect(owner.page.getByRole("heading", { name: "Aggiunte" })).toBeVisible();
  await expect(owner.page.getByText("Granella di pistacchio")).toBeVisible();
  await expect(owner.page.getByRole("button", { name: "Add option" })).toBeVisible();
  await owner.context.close();

  const waiter = await signedIn(browser, duodecim.waiter);
  await waiter.page.goto("/menu");
  await waiter.page.getByRole("button", { name: "Toppings and options" }).click();
  await expect(waiter.page.getByText("Granella di pistacchio")).toBeVisible();
  await expect(waiter.page.getByRole("button", { name: "Add option" })).toHaveCount(0);
  await waiter.context.close();
});

test("a reservation needs only a date, a time and the guests: missing name and phone are pointed out, not forced", async ({ browser, baseURL }) => {
  const tomorrow = new Date(Date.now() + 24 * 3600 * 1000);
  const day = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  await removeLeftovers(baseURL!, day, "John Doe");

  const { context, page } = await signedIn(browser, demo.manager);
  await page.goto("/reservations");
  await page.getByRole("button", { name: "New reservation" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Date").fill(day);
  await dialog.getByLabel("From (24 h)").fill("1230");
  await expect(dialog.getByLabel("From (24 h)")).toHaveValue("12:30"); // the colon appears by itself
  await dialog.getByLabel("To (24 h, optional)").fill("1400");
  await expect(dialog.getByText("Stay: 1 h 30 min")).toBeVisible();
  await dialog.getByLabel("Guests", { exact: true }).fill("2");

  // No name: offered a generic one.
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog.getByText("No name was entered")).toBeVisible();
  await dialog.getByRole("button", { name: "Use “John Doe”" }).click();
  // No phone number: a warning that can be accepted.
  await expect(dialog.getByText("There is no phone number")).toBeVisible();
  await dialog.getByRole("button", { name: "Save without a phone number" }).click();
  await expect(dialog).toBeHidden();

  await page.locator("#day").fill(day);
  const row = page.locator("li").filter({ hasText: "John Doe" });
  await expect(row).toContainText("12:30");
  await expect(row).toContainText("– 14:00");

  page.once("dialog", (d) => void d.accept());
  await row.getByRole("button", { name: "Delete" }).click();
  await expect(page.getByText("John Doe")).toHaveCount(0);
  await context.close();
  await removeLeftovers(baseURL!, day, "John Doe");
});

test("a party marked as seated shows on the floor plan by itself, without reloading", async ({ browser, baseURL }) => {
  const { context, page } = await signedIn(browser, demo.owner, { width: 1440, height: 1000 });
  await page.goto("/floor");
  await expect(page.getByRole("button", { name: /^Table 5,/ })).toBeVisible();

  // Someone else books the table and seats the guests (here: through the API).
  const api = await request.newContext({ baseURL });
  const login = await api.post("/api/v1/auth/login", { data: { email: demo.manager, password: PASSWORD } });
  const headers = { Authorization: `Bearer ${((await login.json()) as { accessToken: string }).accessToken}` };
  const tables = (await (await api.get("/api/v1/tables", { headers })).json()) as { id: string; number: number }[];
  const table = tables.find((t) => t.number === 5)!;
  const made = await api.post("/api/v1/reservations", {
    headers,
    data: { guestName: "Playwright live", startsAt: new Date(Date.now() + 5 * 3600 * 1000).toISOString(), partySize: 2, tableId: table.id, status: "ARRIVED" },
  });
  expect(made.ok()).toBe(true);
  const id = ((await made.json()) as { id: string }).id;

  // The plan refreshes on its own every few seconds: the table turns "occupied" although the booking is for later.
  await expect(page.getByRole("button", { name: /^Table 5,.*Occupied/ })).toBeVisible({ timeout: 30_000 });

  await api.delete(`/api/v1/reservations/${id}`, { headers });
  await expect(page.getByRole("button", { name: /^Table 5,.*Free/ })).toBeVisible({ timeout: 30_000 });
  await api.dispose();
  await context.close();
});
