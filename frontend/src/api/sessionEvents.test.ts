import { describe, expect, it, vi } from "vitest";
import { announceSessionEnded, onSessionEnded } from "./sessionEvents";

describe("sessionEvents", () => {
  it("tells every subscriber, each time the session ends", () => {
    const first = vi.fn();
    const second = vi.fn();
    const offFirst = onSessionEnded(first);
    const offSecond = onSessionEnded(second);

    announceSessionEnded();
    announceSessionEnded();

    expect(first).toHaveBeenCalledTimes(2);
    expect(second).toHaveBeenCalledTimes(2);
    offFirst();
    offSecond();
  });

  it("stops telling a subscriber that unsubscribed", () => {
    const listener = vi.fn();
    const off = onSessionEnded(listener);
    off();
    announceSessionEnded();
    expect(listener).not.toHaveBeenCalled();
  });

  it("does nothing when nobody listens", () => {
    expect(() => announceSessionEnded()).not.toThrow();
  });

  it("lets a subscriber unsubscribe from inside its own callback", () => {
    const other = vi.fn();
    const off = onSessionEnded(() => off());
    const offOther = onSessionEnded(other);
    announceSessionEnded();
    expect(other).toHaveBeenCalledTimes(1);
    offOther();
  });
});
