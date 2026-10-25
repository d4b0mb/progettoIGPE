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
});
