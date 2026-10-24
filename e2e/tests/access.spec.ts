import { expect, test } from "@playwright/test";
import { demo, DESKTOP, expectNoHorizontalScroll, PASSWORD, PHONE, signedIn } from "./helpers";

test.describe("signing in", () => {
  test("a wrong password is explained and nobody is let in", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "EN" }).first().click();
    await page.getByLabel("Email").fill(demo.owner);
    await page.getByLabel("Password").fill("not-the-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Invalid email or password")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("signed-out visitors are sent to the sign-in page", async ({ page }) => {
    await page.goto("/reservations");
    await expect(page).toHaveURL(/\/login/);
  });

  test("a session survives a page reload", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner);
    await page.goto("/reservations");
    await page.reload();
    await expect(page.getByRole("heading", { name: "Reservations", exact: true })).toBeVisible();
    await context.close();
  });

  test("each role sees only its own screens", async ({ browser }) => {
    const owner = await signedIn(browser, demo.owner);
    const nav = (p: typeof owner.page) => p.getByRole("navigation", { name: "Main navigation" }).first();
    await expect(nav(owner.page).getByRole("link", { name: "Staff" })).toBeVisible();
    await owner.context.close();

    const waiter = await signedIn(browser, demo.waiter, DESKTOP);
    await expect(nav(waiter.page).getByRole("link", { name: "Orders" })).toBeVisible();
    await expect(nav(waiter.page).getByRole("link", { name: "Staff" })).toHaveCount(0);
    await waiter.context.close();

    // Kitchen staff land straight on the kitchen screen, and it is their only one.
    const kitchen = await signedIn(browser, demo.kitchen);
    await expect(kitchen.page).toHaveURL(/\/kitchen/);
    await expect(nav(kitchen.page).getByRole("link")).toHaveCount(1);
    await kitchen.context.close();
  });

  test("the owner's pages fit a phone and a desktop without sideways scrolling", async ({ browser }) => {
    for (const viewport of [PHONE, DESKTOP]) {
      const { context, page } = await signedIn(browser, demo.owner, viewport);
      for (const path of ["/reservations", "/floor", "/menu", "/staff", "/orders"]) {
        await page.goto(path);
        // Not "network idle": the orders screen holds a live stream open on purpose.
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await page.waitForTimeout(500); // let the screen's data arrive and lay out
        await expectNoHorizontalScroll(page);
      }
      await context.close();
    }
  });

  test("the demo logins all use the documented password", async ({ browser }) => {
    for (const email of Object.values(demo)) {
      const { context } = await signedIn(browser, email);
      await context.close();
    }
    expect(PASSWORD.length).toBeGreaterThanOrEqual(10);
  });
});
