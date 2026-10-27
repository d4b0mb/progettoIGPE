import { expect, request, type Browser, type BrowserContext, type Page } from "@playwright/test";

export const PASSWORD = process.env.DEMO_PASSWORD ?? "Demo-Password-2026";
export const demo = {
  owner: "owner@demo.ristomanager.local",
  manager: "manager@demo.ristomanager.local",
  waiter: "waiter@demo.ristomanager.local",
  kitchen: "kitchen@demo.ristomanager.local",
};

/** The pizzeria demo: a real menu and its own look. */
export const duodecim = {
  owner: "owner@duodecim.ristomanager.local",
  waiter: "waiter@duodecim.ristomanager.local",
  kitchen: "kitchen@duodecim.ristomanager.local",
};

export const PHONE = { width: 390, height: 844 };
export const DESKTOP = { width: 1280, height: 800 };

/** Opens a fresh browser context, signs in through the real form, and switches the UI to English. */
export async function signedIn(
  browser: Browser,
  email: string,
  viewport = DESKTOP,
  contextOptions: Parameters<Browser["newContext"]>[0] = {},
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ viewport, locale: "en-GB", ...contextOptions });
  const page = await context.newPage();
  await page.goto("/login");
  await page.getByRole("button", { name: "EN" }).first().click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).not.toHaveURL(/\/login/);
  return { context, page };
}

/** The page must never scroll sideways: on a phone that is the first sign of a broken layout. */
export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow, `page scrolls horizontally: ${page.url()}`).toBe(false);
}

/**
 * Removes reservations a previous (possibly crashed) run left behind on a given day, so tests start
 * from the same state every time. Only touches guests whose name starts with the given prefix.
 */
export async function removeLeftovers(baseURL: string, day: string, prefix: string) {
  const api = await request.newContext({ baseURL });
  const login = await api.post("/api/v1/auth/login", { data: { email: demo.manager, password: PASSWORD } });
  const { accessToken } = (await login.json()) as { accessToken: string };
  const headers = { Authorization: `Bearer ${accessToken}` };
  const list = await api.get(`/api/v1/reservations?date=${day}`, { headers });
  for (const r of (await list.json()) as { id: string; guestName: string }[]) {
    if (r.guestName.startsWith(prefix)) await api.delete(`/api/v1/reservations/${r.id}`, { headers });
  }
  await api.dispose();
}

/** Local calendar date (YYYY-MM-DD) of the day that many days from now. */
export function dayFromNow(days: number): string {
  const d = new Date(Date.now() + days * 24 * 3600 * 1000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/**
 * Creates a confirmed booking for the given day at lunch, through the API as the demo manager, and returns its id.
 * Tests that need a booking should make their own for a day that is always in the future (tomorrow), instead of relying
 * on the seeded evening ones: the seed places them minutes or hours after whenever it ran, so late in the evening
 * they fall on the next day and a test that looks at "today" no longer finds them.
 */
export async function createReservation(baseURL: string, booking: { guest: string; day: string; partySize?: number }): Promise<string> {
  const api = await request.newContext({ baseURL });
  const login = await api.post("/api/v1/auth/login", { data: { email: demo.manager, password: PASSWORD } });
  const { accessToken } = (await login.json()) as { accessToken: string };
  const response = await api.post("/api/v1/reservations", {
    headers: { Authorization: `Bearer ${accessToken}` },
    // 10:30 UTC is 11:30 or 12:30 in Rome: the same calendar day in the restaurant either way.
    data: { guestName: booking.guest, phone: "+39 333 765 4321", startsAt: `${booking.day}T10:30:00Z`, partySize: booking.partySize ?? 2, status: "CONFIRMED", allergens: [] },
  });
  expect(response.status(), await response.text()).toBe(201);
  const { id } = (await response.json()) as { id: string };
  await api.dispose();
  return id;
}
