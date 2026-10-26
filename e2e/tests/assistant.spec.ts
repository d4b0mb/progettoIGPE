import { expect, test, type Page } from "@playwright/test";
import { demo, expectNoHorizontalScroll, signedIn } from "./helpers";

/**
 * The staff assistant chat. The model is never called: the three assistant routes are answered here, so the tests
 * are the same every run and do not depend on what a real model would say. What they check is the screen's own
 * behaviour, above all the safety rule: typing in the chat never confirms anything, only the Confirm button does.
 */

const PROPOSAL_ID = "11111111-1111-4111-8111-111111111111";
const REPLY = "Ho preparato la prenotazione, conferma per favore.";

/** A date a number of days from today, as YYYY-MM-DD on this machine's clock. */
function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

interface Calls {
  chat: { message: string; history?: { role: string; content: string }[] }[];
  confirm: string[];
  dismiss: string[];
}

/** Answers the assistant's three routes with fixed replies and records every call that reaches them. */
async function mockAssistant(page: Page): Promise<Calls> {
  const calls: Calls = { chat: [], confirm: [], dismiss: [] };
  const date = inDays(2);

  await page.route("**/api/v1/assistant/chat", async (route) => {
    calls.chat.push(route.request().postDataJSON());
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      json: {
        reply: REPLY,
        proposals: [
          {
            id: PROPOSAL_ID,
            kind: "CREATE_RESERVATION",
            guestName: "Rossi",
            partySize: 4,
            date,
            time: "21:00",
            tableNumber: 3,
            notes: "",
            expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
          },
        ],
        notices: [],
      },
    });
  });

  await page.route("**/api/v1/assistant/proposals/*/confirm", async (route) => {
    calls.confirm.push(route.request().url());
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      json: { kind: "CREATE_RESERVATION", guestName: "Rossi", partySize: 4, date, time: "21:00", tableNumber: 3 },
    });
  });

  await page.route("**/api/v1/assistant/proposals/*", async (route) => {
    if (route.request().method() === "DELETE") {
      calls.dismiss.push(route.request().url());
      await route.fulfill({ status: 204 });
      return;
    }
    await route.continue();
  });

  return calls;
}

const openButton = (page: Page) => page.getByRole("button", { name: "Open assistant" });
const sheet = (page: Page) => page.getByRole("dialog", { name: "Assistant" });

test.describe("staff assistant", () => {
  test("a starter question brings a card; nothing happens until Confirm is pressed, and then it happens once", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner);
    const calls = await mockAssistant(page);
    await page.goto("/reservations");

    await openButton(page).click();
    await expect(sheet(page)).toBeVisible();
    // The button gets out of the way while the sheet is open.
    await expect(openButton(page)).toHaveCount(0);

    await sheet(page).getByRole("button", { name: "Who has booked tonight?" }).click();
    await expect(sheet(page).getByText("Who has booked tonight?")).toBeVisible();
    await expect(sheet(page).getByText(REPLY)).toBeVisible();

    const card = sheet(page).getByRole("region", { name: "New booking" });
    await expect(card).toBeVisible();
    await expect(card.getByText("Rossi", { exact: true })).toBeVisible();
    await expect(card.getByText(/4 guests/)).toBeVisible();
    await expect(card.getByText(/21:00/)).toBeVisible();
    await expect(card.getByText("Table 3")).toBeVisible();

    // A question and its proposal are not a booking: the confirm route has not been touched.
    expect(calls.chat).toHaveLength(1);
    expect(calls.chat[0].message).toBe("Who has booked tonight?");
    expect(calls.confirm).toHaveLength(0);

    await card.getByRole("button", { name: "Confirm booking" }).click();
    await expect(sheet(page).getByText(/^Done: booking for Rossi, \w{3,4} \d{1,2} \w{3,5}, 21:00$/)).toBeVisible();
    expect(calls.confirm).toHaveLength(1);
    expect(calls.confirm[0]).toContain(`/proposals/${PROPOSAL_ID}/confirm`);
    // It cannot be confirmed a second time.
    await expect(sheet(page).getByRole("button", { name: "Confirm booking" })).toHaveCount(0);
    expect(calls.dismiss).toHaveLength(0);

    // Closing the sheet keeps the conversation.
    await page.keyboard.press("Escape");
    await expect(sheet(page)).toBeHidden();
    await openButton(page).click();
    await expect(sheet(page).getByText(REPLY)).toBeVisible();

    await context.close();
  });

  test("typing a confirmation in the chat only sends a message and never confirms", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner);
    const calls = await mockAssistant(page);
    await page.goto("/reservations");

    await openButton(page).click();
    const input = sheet(page).getByLabel("Message", { exact: true });
    await input.fill("Prenota per Rossi, 4 persone");
    await input.press("Enter");
    await expect(sheet(page).getByRole("region", { name: "New booking" })).toBeVisible();

    await input.fill("sì conferma");
    await input.press("Enter");
    await expect(sheet(page).getByText("sì conferma", { exact: true })).toBeVisible();
    await expect.poll(() => calls.chat.length).toBe(2);

    expect(calls.chat[1].message).toBe("sì conferma");
    // The first turn travels as history, as words only.
    expect(calls.chat[1].history?.map((turn) => turn.role)).toEqual(["user", "assistant"]);
    expect(calls.confirm).toHaveLength(0);
    expect(calls.dismiss).toHaveLength(0);
    // The old card is still waiting for its button.
    await expect(sheet(page).getByRole("button", { name: "Confirm booking" })).toHaveCount(2);

    await context.close();
  });

  test("Dismiss tells the server and collapses the card", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner);
    const calls = await mockAssistant(page);
    await page.goto("/reservations");

    await openButton(page).click();
    await sheet(page).getByRole("button", { name: "Who has booked tonight?" }).click();
    const card = sheet(page).getByRole("region", { name: "New booking" });
    await card.getByRole("button", { name: "Dismiss" }).click();

    await expect(sheet(page).getByText("Dismissed", { exact: true })).toBeVisible();
    await expect(sheet(page).getByRole("button", { name: "Confirm booking" })).toHaveCount(0);
    await expect.poll(() => calls.dismiss.length).toBe(1);
    expect(calls.dismiss[0]).toContain(`/proposals/${PROPOSAL_ID}`);
    expect(calls.confirm).toHaveLength(0);

    await context.close();
  });

  test("a failed question shows a fixed notice in the panel, never the server's own text", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner);
    await page.route("**/api/v1/assistant/chat", async (route) => {
      // The shape the API uses for every error (see errors.ts): the explanation is in "detail".
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        json: { status: 503, detail: "The local AI model is not available" },
      });
    });
    await page.goto("/reservations");

    await openButton(page).click();
    await sheet(page).getByRole("button", { name: "Who has booked tonight?" }).click();
    await expect(sheet(page).getByText(/The assistant is not available right now/)).toBeVisible();
    await expect(sheet(page).getByText("The local AI model is not available")).toHaveCount(0);
    // Nothing is left spinning, and the person can try again.
    await expect(sheet(page).getByText(/Thinking/)).toHaveCount(0);
    await expect(sheet(page).getByLabel("Message", { exact: true })).toBeEnabled();

    await context.close();
  });

  test("the button is not on the kitchen screen, but the kitchen role has it elsewhere", async ({ browser }) => {
    const owner = await signedIn(browser, demo.owner);
    await owner.page.goto("/reservations");
    await expect(openButton(owner.page)).toBeVisible();
    await owner.page.goto("/kitchen");
    await expect(owner.page).toHaveURL(/\/kitchen/);
    await expect(owner.page.getByRole("navigation", { name: "Main navigation" }).first()).toBeVisible();
    await expect(openButton(owner.page)).toHaveCount(0);
    await owner.context.close();

    // Kitchen staff land on the kitchen screen, which stays clean; their other page (Account) has the button.
    const kitchen = await signedIn(browser, demo.kitchen);
    await expect(kitchen.page).toHaveURL(/\/kitchen/);
    await expect(kitchen.page.getByRole("navigation", { name: "Main navigation" }).first()).toBeVisible();
    await expect(openButton(kitchen.page)).toHaveCount(0);
    await kitchen.page.goto("/account");
    await expect(openButton(kitchen.page)).toBeVisible();
    await kitchen.context.close();
  });

  test("on a phone the button floats above the bottom navigation and nothing scrolls sideways", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 375, height: 700 });
    const calls = await mockAssistant(page);
    await page.goto("/reservations");

    const button = openButton(page);
    await expect(button).toBeVisible();
    const box = (await button.boundingBox())!;
    // Only the bottom bar is visible on a phone (the sidebar is hidden), so this is the one.
    const nav = (await page.getByRole("navigation", { name: "Main navigation" }).boundingBox())!;
    expect(box.y + box.height).toBeLessThanOrEqual(nav.y);
    expect(box.width).toBeGreaterThanOrEqual(48);
    expect(box.height).toBeGreaterThanOrEqual(48);

    await button.click();
    await sheet(page).getByRole("button", { name: "Who has booked tonight?" }).click();
    await expect(sheet(page).getByRole("region", { name: "New booking" })).toBeVisible();
    await expectNoHorizontalScroll(page);
    // The card's buttons fit inside the sheet.
    const sheetBox = (await sheet(page).boundingBox())!;
    const confirmBox = (await sheet(page).getByRole("button", { name: "Confirm booking" }).boundingBox())!;
    expect(confirmBox.x).toBeGreaterThanOrEqual(sheetBox.x);
    expect(confirmBox.x + confirmBox.width).toBeLessThanOrEqual(sheetBox.x + sheetBox.width);
    expect(calls.confirm).toHaveLength(0);

    await context.close();
  });
});
