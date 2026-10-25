import { expect, test } from "@playwright/test";
import { demo, PASSWORD, PHONE, signedIn } from "./helpers";

test.describe("passwords, recovery and the activity log", () => {
  test("the owner sets a new password for a staff member, who can then sign in with it", async ({ browser }) => {
    const owner = await signedIn(browser, demo.owner);
    await owner.page.goto("/staff");
    const name = `E2E Waiter ${Date.now()}`;
    const email = `e2e-${Date.now()}@demo.ristomanager.local`;
    await owner.page.getByLabel("Name").fill(name);
    await owner.page.getByLabel("Email").fill(email);
    await owner.page.getByLabel("Password").fill(PASSWORD);
    await owner.page.getByRole("button", { name: "Save" }).click();
    await expect(owner.page.getByText(`${name} was added.`)).toBeVisible();

    await owner.page.locator("li").filter({ hasText: name }).getByRole("button", { name: "Reset password" }).click();
    await owner.page.getByRole("dialog").getByLabel("New password").fill("Brand-New-Password-1");
    await owner.page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
    await expect(owner.page.getByText(`The password of ${name} was changed.`)).toBeVisible();

    // The old password no longer works; the new one does.
    const staffCtx = await browser.newContext({ locale: "en-GB" });
    const page = await staffCtx.newPage();
    await page.goto("/login");
    await page.getByRole("button", { name: "EN" }).first().click();
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Invalid email or password")).toBeVisible();
    await page.getByLabel("Password").fill("Brand-New-Password-1");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).not.toHaveURL(/\/login/);

    // Clean up: deactivate so the demo staff list stays tidy.
    owner.page.once("dialog", (d) => void d.accept());
    await owner.page.locator("li").filter({ hasText: name }).getByRole("button", { name: "Deactivate" }).click();
    await staffCtx.close();
    await owner.context.close();
  });

  test("a new owner sets up recovery, forgets the password, and gets back in with their answers", async ({ browser }) => {
    const context = await browser.newContext({ viewport: PHONE, locale: "en-GB" });
    const page = await context.newPage();
    const email = `owner-${Date.now()}@example.com`;

    // Sign up through the real form.
    await page.goto("/signup");
    await page.getByRole("button", { name: "EN" }).first().click();
    await page.getByLabel("Restaurant name").fill("Recovery Test");
    await page.getByLabel("Your name").fill("Owner Test");
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill("Original-Password-1");
    await page.getByRole("button", { name: "Create account" }).click();

    // A banner asks the new owner to set up recovery.
    await expect(page.getByText("Set up account recovery")).toBeVisible();
    await page.getByRole("link", { name: "Set it up now" }).click();
    await page.getByLabel("Question 1").fill("What was the name of my first dog?");
    await page.getByLabel("Answer 1").fill("Rex");
    await page.getByLabel("Question 2").fill("Which street did I grow up on?");
    await page.getByLabel("Answer 2").fill("Via Roma 12");
    await page.getByLabel("Enter your current password to confirm").fill("Original-Password-1");
    await page.getByRole("button", { name: "Save recovery questions" }).click();
    await expect(page.getByText("Recovery questions saved.")).toBeVisible();
    await expect(page.getByText("Recovery is set up.")).toBeVisible();

    // Sign out, then recover with the answers (different capitals and punctuation are fine).
    await page.getByRole("button", { name: "Sign out" }).first().click();
    await page.waitForURL("**/login");
    await page.getByRole("link", { name: "Forgot your password?" }).click();
    await page.getByLabel("Owner email").fill(email);
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByLabel("What was the name of my first dog?")).toBeVisible();
    await page.getByLabel("What was the name of my first dog?").fill(" REX! ");
    await page.getByLabel("Which street did I grow up on?").fill("via roma, 12");
    await page.getByLabel("New password").fill("Recovered-Password-1");
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText("Password changed. You can sign in now.")).toBeVisible();

    await page.getByRole("link", { name: "Back to sign in" }).click();
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill("Original-Password-1");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Invalid email or password")).toBeVisible();
    await page.getByLabel("Password").fill("Recovered-Password-1");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("heading", { name: /Welcome/ })).toBeVisible();
    await context.close();
  });

  test("wrong recovery answers are refused", async ({ browser }) => {
    const context = await browser.newContext({ locale: "en-GB" });
    const page = await context.newPage();
    await page.goto("/recover");
    await page.getByRole("button", { name: "EN" }).first().click();
    await page.getByLabel("Owner email").fill("nobody-" + Date.now() + "@example.com");
    await page.getByRole("button", { name: "Continue" }).click();
    // An unknown email still gets two questions (decoys), so nothing is given away.
    await expect(page.getByRole("heading", { name: "Answer your questions" })).toBeVisible();
    const boxes = page.locator('form input[autocomplete="off"]');
    await expect(boxes).toHaveCount(2);
    await boxes.nth(0).fill("anything");
    await boxes.nth(1).fill("anything else");
    await page.getByLabel("New password").fill("Some-Password-1234");
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText("The answers do not match.")).toBeVisible();
    await context.close();
  });

  test("the activity log shows failed sign-ins, and only the owner can open it", async ({ browser }) => {
    // Someone fails to sign in as the manager.
    const intruder = await browser.newContext({ locale: "en-GB" });
    const attempt = await intruder.newPage();
    await attempt.goto("/login");
    await attempt.getByRole("button", { name: "EN" }).first().click();
    await attempt.getByLabel("Email").fill(demo.manager);
    await attempt.getByLabel("Password").fill("definitely-wrong-password");
    await attempt.getByRole("button", { name: "Sign in" }).click();
    await expect(attempt.getByText("Invalid email or password")).toBeVisible();
    await intruder.close();

    const owner = await signedIn(browser, demo.owner);
    await owner.page.goto("/activity");
    await owner.page.getByRole("button", { name: "Security" }).click();
    await owner.page.getByLabel("Failures only").check();
    const failed = owner.page.locator("li").filter({ hasText: "Failed sign-in" }).first();
    await expect(failed).toBeVisible();
    await expect(failed).toContainText("wrong password");
    await expect(failed).toContainText("Failed"); // the badge
    // Entries show what happened, never a password.
    await expect(owner.page.locator("main")).not.toContainText("definitely-wrong-password");
    await owner.context.close();

    const waiter = await signedIn(browser, demo.waiter);
    await waiter.page.goto("/activity");
    await expect(waiter.page.getByText("You do not have access to this screen.")).toBeVisible();
    await waiter.context.close();
  });
});
