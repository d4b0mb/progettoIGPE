import { expect, test } from "@playwright/test";
import { demo, duodecim, signedIn } from "./helpers";

/**
 * An order split into servings: the kitchen gets the first one, and only when it has been served does
 * the next one reach the kitchen. The order is never lost on the way.
 */
test("an order in two servings reaches the kitchen one serving after the other", async ({ browser }) => {
  const kitchen = await signedIn(browser, demo.kitchen);
  const waiter = await signedIn(browser, demo.waiter);
  await waiter.page.goto("/orders");
  await waiter.page.getByRole("button", { name: /^Table 2 / }).click();
  await waiter.page.getByRole("button", { name: "Add one Bruschetta" }).click();
  await waiter.page.getByRole("button", { name: /Add serving/ }).click();
  await expect(waiter.page.getByRole("button", { name: /^Serving 2/ })).toHaveAttribute("aria-pressed", "true");
  await waiter.page.getByRole("button", { name: "Add one Tiramisù" }).click();
  // Bruschetta stays in serving 1; the chip shows where each dish is.
  await waiter.page.getByRole("button", { name: /^Serving 1/ }).click();
  await expect(waiter.page.getByText("1 × Serving 2").first()).toBeVisible();
  await waiter.page.getByRole("button", { name: /Send to kitchen/ }).click();
  const sent = waiter.page.getByText(/Order C-\d+ sent to the kitchen/);
  await expect(sent).toBeVisible();
  const code = /C-\d+/.exec((await sent.textContent()) ?? "")![0];

  // The kitchen sees serving 1 in full and serving 2 as "coming next".
  const card = () => kitchen.page.getByRole("article", { name: code });
  await expect(card()).toContainText("Serving 1 of 2");
  await expect(card()).toContainText("Bruschetta");
  await expect(card()).toContainText("Coming next");
  await expect(card()).toContainText("Tiramisù");
  await card().getByRole("button", { name: "Start preparing" }).click();
  await card().getByRole("button", { name: "Mark ready" }).click();

  // The waiter serves serving 1: the order stays, and serving 2 is now new in the kitchen.
  const ready = waiter.page.getByRole("article", { name: code });
  await ready.getByRole("button", { name: "Serving 1 of 2 served" }).click();
  await expect(card()).toContainText("Serving 2 of 2");
  await expect(card().getByRole("button", { name: "Start preparing" })).toBeVisible();
  await expect(card()).not.toContainText("Coming next");

  await card().getByRole("button", { name: "Start preparing" }).click();
  await card().getByRole("button", { name: "Mark ready" }).click();
  await waiter.page.getByRole("article", { name: code }).getByRole("button", { name: "Serving 2 of 2 served" }).click();
  await expect(card()).toHaveCount(0);

  await kitchen.context.close();
  await waiter.context.close();
});

test("the pizzeria has its own look and its real menu, and the owner can switch the look", async ({ browser }) => {
  const owner = await signedIn(browser, duodecim.owner);
  const { page } = owner;
  await expect(page.locator("html")).toHaveAttribute("data-theme", "duodecim");
  await expect(page.getByRole("link", { name: /Duodecim Pizze/i }).first()).toBeVisible();

  await page.goto("/menu");
  await expect(page.getByRole("heading", { name: "Pizzas" })).toBeVisible();
  await expect(page.getByText("Margherita con Bufala")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Birre in bottiglia (33 cl)" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Vini rossi" })).toBeVisible();

  await page.goto("/account");
  await page.getByRole("button", { name: "Classic" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "classic");
  await page.getByRole("button", { name: /Duodecim Pizze/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "duodecim");
  await owner.context.close();
});
