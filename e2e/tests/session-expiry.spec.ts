import { expect, test } from "@playwright/test";
import { demo, signedIn } from "./helpers";

const MESSAGE = "Your session ended. Please sign in again.";

test.describe("a session that ends while the app is open", () => {
  test("the person is sent to the sign-in page with a message that explains why", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.waiter);

    // From now on the server answers every API call, and the refresh, with 401 (as after 14 days
    // or a password change elsewhere). Mocked in the browser: nothing changes on the server.
    await page.route("**/api/v1/**", (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ message: "Not signed in" }) }),
    );

    // A client-side move to another screen makes that screen call the API.
    await page.getByRole("navigation", { name: "Main navigation" }).first().getByRole("link", { name: "Orders" }).click();

    await expect(page).toHaveURL(/\/login\?reason=expired/);
    await expect(page.getByRole("status").filter({ hasText: MESSAGE })).toBeVisible();
    // No loop: the sign-in page stays put.
    await page.waitForTimeout(500);
    await expect(page).toHaveURL(/\/login\?reason=expired/);
    await context.close();
  });

  test("a visitor who was never signed in sees no such message", async ({ page }) => {
    await page.goto("/reservations");
    await expect(page).toHaveURL(/\/login/);
    await page.getByRole("button", { name: "EN" }).first().click();
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await expect(page.getByText(MESSAGE)).toHaveCount(0);
    expect(new URL(page.url()).search).toBe("");
  });

  test("the sign-in page only understands the fixed reason code", async ({ page }) => {
    await page.goto("/login?reason=%3Cb%3Ehello%3C%2Fb%3E");
    await page.getByRole("button", { name: "EN" }).first().click();
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await expect(page.getByText(MESSAGE)).toHaveCount(0);
    await expect(page.getByText("hello")).toHaveCount(0);
  });
});
