import { expect, test } from "@playwright/test";
import { demo, signedIn } from "./helpers";

/**
 * Regression tests for the floor plan problems found on a big monitor: the plan ran off the bottom of
 * the screen so only some tables showed, it could not be zoomed or moved, and clicking a table drew a
 * huge black outline and made it look bigger.
 */
test.describe("floor plan", () => {
  test("the whole room fits the screen, and selecting a table does not change the plan's size", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1920, height: 1080 });
    await page.goto("/floor");
    const plan = page.getByRole("group", { name: "Main room" });
    await expect(page.getByRole("button", { name: /^Table 1,/ })).toBeVisible();

    const before = (await plan.boundingBox())!;
    // The bottom of the plan is on screen (a few pixels of border are allowed).
    expect(before.y + before.height).toBeLessThan(1080 + 12);
    // Every demo table can be seen without scrolling the plan.
    for (const n of [1, 2, 3, 4, 5, 6]) {
      const box = (await page.getByRole("button", { name: new RegExp(`^Table ${n},`) }).boundingBox())!;
      expect(box.y + box.height, `table ${n} is inside the screen`).toBeLessThan(1080);
    }

    await page.getByRole("button", { name: /^Table 3,/ }).click();
    await expect(page.getByRole("heading", { name: "Table 3" })).toBeVisible();
    const after = (await plan.boundingBox())!;
    expect(Math.round(after.width)).toBe(Math.round(before.width));
    expect(Math.round(after.height)).toBe(Math.round(before.height));
    await context.close();
  });

  test("zoom in, zoom out and Fit work, and the plan can be dragged", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1440, height: 900 });
    await page.goto("/floor");
    await expect(page.getByRole("button", { name: /^Table 1,/ })).toBeVisible();
    const level = page.getByRole("button", { name: "Fit to screen" });
    await expect(level).toHaveText("100%");

    await page.getByRole("button", { name: "Zoom in" }).click();
    await page.getByRole("button", { name: "Zoom in" }).click();
    await expect(level).not.toHaveText("100%");
    const zoomed = parseInt((await level.textContent())!, 10);
    expect(zoomed).toBeGreaterThan(150);

    // Drag the empty floor: tables move on screen.
    const table = page.getByRole("button", { name: /^Table 5,/ });
    const where = (await table.boundingBox())!;
    const plan = (await page.getByRole("group", { name: "Main room" }).boundingBox())!;
    await page.mouse.move(plan.x + plan.width * 0.5, plan.y + plan.height * 0.15);
    await page.mouse.down();
    await page.mouse.move(plan.x + plan.width * 0.35, plan.y + plan.height * 0.25, { steps: 6 });
    await page.mouse.up();
    const moved = (await table.boundingBox())!;
    expect(Math.abs(moved.x - where.x) + Math.abs(moved.y - where.y)).toBeGreaterThan(20);

    await page.getByRole("button", { name: "Zoom out" }).click();
    await page.getByRole("button", { name: "Fit to screen" }).click();
    await expect(level).toHaveText("100%");
    await context.close();
  });

  test("Ctrl + scroll zooms the plan only after it was clicked, and otherwise leaves the browser's zoom alone", async ({ browser }) => {
    const { context, page } = await signedIn(browser, demo.owner, { width: 1440, height: 900 });
    await page.goto("/floor");
    await expect(page.getByRole("button", { name: /^Table 1,/ })).toBeVisible();
    const level = page.getByRole("button", { name: "Fit to screen" });
    const plan = page.getByRole("group", { name: "Main room" });

    // Sends a Ctrl+wheel and reports whether the page stopped the browser's own zoom.
    const ctrlWheel = () =>
      plan.evaluate((svg) => {
        const event = new WheelEvent("wheel", { ctrlKey: true, deltaY: -100, bubbles: true, cancelable: true });
        svg.dispatchEvent(event);
        return event.defaultPrevented;
      });

    expect(await ctrlWheel(), "not clicked yet: the browser zooms as usual").toBe(false);
    await expect(level).toHaveText("100%");

    const box = (await plan.boundingBox())!;
    await page.mouse.click(box.x + box.width * 0.5, box.y + box.height * 0.1);
    expect(await ctrlWheel(), "clicked: the plan takes the scroll").toBe(true);
    await expect(level).not.toHaveText("100%");

    await page.mouse.click(5, 5); // click somewhere else on the page
    expect(await ctrlWheel(), "clicked away: the browser zooms again").toBe(false);
    await context.close();
  });
});
