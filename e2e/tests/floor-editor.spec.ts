import { expect, test, type Page } from "@playwright/test";
import { demo, signedIn } from "./helpers";

/**
 * Editing the room by direct manipulation: add things from the palette, drag the handles on their edges to
 * resize, turn them, stretch the room itself. With a mouse and with a finger.
 */

const inspectorNumber = (page: Page, label: RegExp) => page.getByLabel(label).first();
const numberIn = async (page: Page, label: RegExp) => Number(await inspectorNumber(page, label).inputValue());

/** Centre of an element on screen. */
async function centre(page: Page, selector: string) {
  const box = (await page.locator(selector).first().boundingBox())!;
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** A drag with a real mouse, in small steps like a hand. */
async function mouseDrag(page: Page, from: { x: number; y: number }, by: { x: number; y: number }) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(from.x + by.x, from.y + by.y, { steps: 8 });
  await page.mouse.up();
}

test.describe("floor plan editor", () => {
  test("add a table, resize it by its handle, turn it, duplicate it and delete it", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1440, height: 1000 });
    page.on("dialog", (dialog) => void dialog.accept());
    await page.goto("/floor");
    const tables = page.getByRole("button", { name: /^Table \d+,/ });
    await expect(tables.first()).toBeVisible();
    const before = await tables.count();

    await page.getByRole("button", { name: "+ Round table" }).click();
    await expect(tables).toHaveCount(before + 1);
    await expect(page.getByRole("heading", { name: /^Table \d+$/ })).toBeVisible();
    const width0 = await numberIn(page, /^Width/);
    const seats0 = await numberIn(page, /^Seats/);

    // Drag the bottom-right corner handle outwards: the table grows, and the seats follow its size.
    await mouseDrag(page, await centre(page, '[data-handle="se"]'), { x: 70, y: 70 });
    await expect.poll(() => numberIn(page, /^Width/)).toBeGreaterThan(width0);
    await expect.poll(() => numberIn(page, /^Seats/)).toBeGreaterThan(seats0);
    await expect(page.getByRole("button", { name: "Undo" })).toBeEnabled();

    // Undo gives the old size back.
    await page.getByRole("button", { name: "Undo" }).click();
    await expect.poll(() => numberIn(page, /^Width/)).toBe(width0);

    // Turn it a quarter, then copy it, then delete both.
    await page.getByRole("button", { name: "Turn 90°" }).click();
    await expect.poll(() => numberIn(page, /^Rotation/)).toBe(90);
    await page.getByRole("button", { name: "Duplicate" }).click();
    await expect(tables).toHaveCount(before + 2);
    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await expect(tables).toHaveCount(before + 1);
    await tables.last().click();
    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await expect(tables).toHaveCount(before);
    await context.close();
  });

  test("a finger can resize too", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1180, height: 1000 }, { hasTouch: true });
    page.on("dialog", (dialog) => void dialog.accept());
    await page.goto("/floor");
    await page.getByRole("button", { name: "+ Square table" }).click();
    await expect(page.getByRole("heading", { name: /^Table \d+$/ })).toBeVisible();
    const width0 = await numberIn(page, /^Width/);

    const cdp = await context.newCDPSession(page);
    const start = await centre(page, '[data-handle="se"]');
    await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [start] });
    for (let i = 1; i <= 8; i++) {
      await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: start.x + i * 9, y: start.y + i * 9 }] });
    }
    await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    await expect.poll(() => numberIn(page, /^Width/)).toBeGreaterThan(width0);

    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await context.close();
  });

  test("the room can be stretched by its edge, and changes in room settings show without a reload", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1440, height: 1000 });
    await page.goto("/floor");
    await expect(page.getByRole("button", { name: /^Table 1,/ })).toBeVisible();
    const outline = page.getByTestId("room-outline");
    const ratio = async () => {
      const box = (await outline.boundingBox())!;
      return box.width / box.height;
    };
    const ratio0 = await ratio(); // 12 x 9

    // Stretch with the corner handle, then read the new size in the settings.
    await mouseDrag(page, await centre(page, '[data-room-handle="se"]'), { x: 60, y: 0 });
    await page.getByRole("button", { name: "Room settings" }).click();
    const dialog = page.getByRole("dialog");
    const widthAfter = Number(await dialog.getByLabel(/^Width/).inputValue());
    expect(widthAfter).toBeGreaterThan(12);
    expect(await ratio()).toBeGreaterThan(ratio0);

    // Type the size back in the settings: the drawing follows at once, with no page reload.
    await dialog.getByLabel(/^Width/).fill("12");
    await dialog.getByLabel(/^Depth/).fill("9");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect.poll(ratio).toBeCloseTo(ratio0, 1);
    await context.close();
  });
});

test("dark look: the sun/moon button switches it and the choice is remembered", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "EN" }).first().click();
  const html = page.locator("html");
  const before = await html.getAttribute("data-mode");
  const bg = () => page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
  const lightBg = before === "dark" ? null : await bg();

  await page.getByRole("button", { name: "Switch to dark look" }).click();
  await expect(html).toHaveAttribute("data-mode", "dark");
  expect(await bg()).not.toBe(lightBg ?? "");
  await page.reload();
  await expect(html).toHaveAttribute("data-mode", "dark");

  await page.getByRole("button", { name: "Switch to light look" }).click();
  await expect(html).toHaveAttribute("data-mode", "light");
  await page.reload();
  await expect(html).toHaveAttribute("data-mode", "light");
});
