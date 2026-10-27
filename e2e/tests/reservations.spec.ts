import { expect, test } from "@playwright/test";
import { createReservation, dayFromNow, demo, removeLeftovers, signedIn } from "./helpers";

test("the manager books a table, and the same table is no longer offered at that time", async ({ browser, baseURL }) => {
  // Book for tomorrow at lunch so the seeded evening reservations are out of the way.
  const tomorrow = new Date(Date.now() + 24 * 3600 * 1000);
  const day = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  await removeLeftovers(baseURL!, day, "Playwright");

  const { context, page } = await signedIn(browser, demo.manager);
  await page.goto("/reservations");
  const guest = `Playwright ${Date.now()}`;

  async function openForm() {
    await page.getByRole("button", { name: "New reservation" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Date").fill(day);
    await dialog.getByLabel("From (24 h)").fill("12:30");
    await dialog.getByLabel("Guests", { exact: true }).fill("3");
    return dialog;
  }

  const first = await openForm();
  await first.getByLabel("Guest name").fill(guest);
  await first.getByLabel("Phone").fill("+39 333 123 4567");
  const table = first.getByLabel("Table");
  // 3 guests: the 2-seat tables must not be offered, and the smallest table that fits comes first.
  await expect.poll(async () => table.locator("option").count()).toBeGreaterThan(1);
  const options = await table.locator("option").allTextContents();
  expect(options.some((o) => o.includes("2 seats"))).toBe(false);
  expect(options[1]).toContain("4 seats");
  const chosen = options[1];
  await table.selectOption({ index: 1 });
  await first.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();

  await page.locator("#day").fill(day);
  await expect(page.getByText(guest)).toBeVisible();

  // Same time again: the table just taken is gone from the offers.
  const second = await openForm();
  await expect.poll(async () => second.getByLabel("Table").locator("option").count()).toBeGreaterThan(1);
  const again = await second.getByLabel("Table").locator("option").allTextContents();
  expect(again).not.toContain(chosen);
  await second.getByRole("button", { name: "Cancel" }).click();

  // Clean up so the test can run again.
  page.once("dialog", (d) => void d.accept());
  await page.locator("li").filter({ hasText: guest }).getByRole("button", { name: "Delete" }).click();
  await expect(page.getByText(guest)).toHaveCount(0);
  await context.close();
  await removeLeftovers(baseURL!, day, "Playwright");
});

test("a waiter can mark a party as arrived but cannot edit or delete the booking", async ({ browser, baseURL }) => {
  // Make the booking here, for tomorrow, instead of relying on the seeded "Greco": the seed puts him 90 minutes after
  // whenever it ran, which after about 22:30 in Rome is already tomorrow and so not on today's list.
  const day = dayFromNow(1);
  const guest = `Playwright waiter ${Date.now()}`;
  await removeLeftovers(baseURL!, day, "Playwright");
  await createReservation(baseURL!, { guest, day });

  const { context, page } = await signedIn(browser, demo.waiter);
  await page.goto("/reservations");
  await page.locator("#day").fill(day);
  const card = page.locator("li").filter({ hasText: guest });
  await expect(card).toBeVisible();
  await expect(card.getByRole("button", { name: "Edit reservation" })).toHaveCount(0);
  await expect(card.getByRole("button", { name: "Delete" })).toHaveCount(0);
  await expect(card.getByRole("button", { name: "Arrived" })).toBeVisible();
  await context.close();
  await removeLeftovers(baseURL!, day, "Playwright");
});
