import { expect, test } from "@playwright/test";
import { demo, expectNoHorizontalScroll, PHONE, signedIn } from "./helpers";

/**
 * A whole service in one test: a waiter on a phone takes an order for a table whose
 * guests declared a nut allergy, the kitchen screen shows it the instant it is sent,
 * the cook prepares it, and the waiter serves it. Two real browsers, no page reloads.
 */
test("a phone order reaches the kitchen live, with the allergy warning", async ({ browser }) => {
  const kitchen = await signedIn(browser, demo.kitchen);
  await expect(kitchen.page).toHaveURL(/\/kitchen/);
  await expect(kitchen.page.getByRole("status")).toContainText("Live");

  const waiter = await signedIn(browser, demo.waiter, PHONE);
  waiter.page.on("dialog", (dialog) => {
    // Sending a dish that contains a declared allergen asks for confirmation first.
    expect(dialog.message()).toContain("Tree nuts");
    void dialog.accept();
  });
  await waiter.page.goto("/orders");
  await expect(waiter.page.getByRole("heading", { name: "New order" })).toBeVisible();

  // Table 1 is seated by the Bianchi party, who declared a tree-nut allergy.
  await waiter.page.getByRole("button", { name: /^Table 1 / }).click();
  await expect(waiter.page.getByText("Allergy on this table: Tree nuts")).toBeVisible();
  await waiter.page.getByRole("button", { name: "Add one Trofie al pesto" }).click();
  await waiter.page.getByRole("button", { name: "Add one Tagliata di manzo" }).click();
  await expect(waiter.page.getByText("Contains Tree nuts (table allergy)")).toBeVisible();
  await waiter.page.getByLabel("Priority").selectOption("URGENT");
  await waiter.page.getByRole("button", { name: /Send to kitchen/ }).click();
  const sent = waiter.page.getByText(/Order C-\d+ sent to the kitchen/);
  await expect(sent).toBeVisible();
  const code = /C-\d+/.exec((await sent.textContent()) ?? "")![0];

  // Live on the kitchen screen: allergy conflict, priority, no reload.
  const card = kitchen.page.getByRole("article", { name: code });
  await expect(card).toBeVisible();
  await expect(card).toContainText("ALLERGY CONFLICT: Tree nuts");
  await expect(card).toContainText("URGENT");
  await expect(card).toContainText("Trofie al pesto");

  await card.getByRole("button", { name: "Start preparing" }).click();
  await kitchen.page.getByRole("article", { name: code }).getByRole("button", { name: "Mark ready" }).click();

  // The waiter's phone now lists it under "Ready to serve".
  const ready = waiter.page.getByRole("article", { name: code });
  await expect(ready).toBeVisible();
  await expectNoHorizontalScroll(waiter.page);
  await ready.getByRole("button", { name: "Served" }).click();

  // Served orders leave both screens.
  await expect(kitchen.page.getByRole("article", { name: code })).toHaveCount(0);
  await expect(waiter.page.getByRole("article", { name: code })).toHaveCount(0);

  await kitchen.context.close();
  await waiter.context.close();
});

test("kitchen staff who open the orders screen are told they have no access", async ({ browser }) => {
  const kitchen = await signedIn(browser, demo.kitchen);
  await kitchen.page.goto("/orders");
  await expect(kitchen.page.getByText("You do not have access to this screen.")).toBeVisible();
  await expect(kitchen.page.getByRole("button", { name: /Send to kitchen/ })).toHaveCount(0);
  await kitchen.context.close();
});
