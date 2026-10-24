import { defineConfig } from "@playwright/test";

/**
 * Runs against a stack that is already up (`docker compose up --build`, which also
 * creates the demo restaurant these tests use). BASE_URL points at the web app.
 *
 * CHROMIUM_PATH is only for machines where the browser is installed somewhere
 * Playwright does not look; CI downloads its own.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1, // the tests share one demo restaurant, so they run one after another
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:3000",
    locale: "en-GB",
    trace: "retain-on-failure",
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
  },
});
