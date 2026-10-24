import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authedFetch, refreshSession } from "./client";
import { token } from "./token";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  token.set(null);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe("authedFetch", () => {
  it("sends the access token with the request", async () => {
    token.set("abc");
    fetchMock.mockResolvedValueOnce(json({ ok: true }));
    await authedFetch(new Request("http://app/api/v1/me"));
    const sent = fetchMock.mock.calls[0][0] as Request;
    expect(sent.headers.get("Authorization")).toBe("Bearer abc");
  });

  it("refreshes once after a 401 and repeats the request with the new token", async () => {
    token.set("expired");
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 })) // original call
      .mockResolvedValueOnce(json({ accessToken: "fresh" })) // refresh
      .mockResolvedValueOnce(json({ ok: true })); // retry
    const res = await authedFetch(new Request("http://app/api/v1/staff", { method: "POST", body: '{"a":1}' }));

    expect(res.status).toBe(200);
    expect(token.get()).toBe("fresh");
    const calls = fetchMock.mock.calls.map(([arg]) => arg as Request | string);
    expect(String(calls[1] instanceof Request ? calls[1].url : calls[1])).toContain("/api/v1/auth/refresh");
    const retry = calls[2] as Request;
    expect(retry.headers.get("Authorization")).toBe("Bearer fresh");
    // The request body survives the retry.
    expect(await retry.text()).toBe('{"a":1}');
  });

  it("gives back the 401 when the refresh fails, and forgets the token", async () => {
    token.set("expired");
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    const res = await authedFetch(new Request("http://app/api/v1/me"));
    expect(res.status).toBe(401);
    expect(token.get()).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("never tries to refresh when an auth call itself returns 401", async () => {
    token.set("abc");
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    const res = await authedFetch(new Request("http://app/api/v1/auth/login", { method: "POST", body: "{}" }));
    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not refresh for a visitor who was never signed in", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await authedFetch(new Request("http://app/api/v1/me"));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("shares one refresh between simultaneous 401s, because refresh tokens are single-use", async () => {
    token.set("expired");
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (input) => {
      const request = input as Request | string;
      const url = request instanceof Request ? request.url : request;
      if (url.includes("/auth/refresh")) {
        refreshCalls += 1;
        return json({ accessToken: "fresh" });
      }
      const auth = request instanceof Request ? request.headers.get("Authorization") : null;
      return auth === "Bearer fresh" ? json({ ok: true }) : new Response(null, { status: 401 });
    });

    const results = await Promise.all([
      authedFetch(new Request("http://app/api/v1/rooms")),
      authedFetch(new Request("http://app/api/v1/menu/items")),
      authedFetch(new Request("http://app/api/v1/staff")),
    ]);

    expect(results.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(refreshCalls).toBe(1);
  });
});

describe("refreshSession", () => {
  it("starts a new refresh once the previous one has finished", async () => {
    fetchMock.mockImplementation(async () => json({ accessToken: "one" }));
    await refreshSession();
    await refreshSession();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
