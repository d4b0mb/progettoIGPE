import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { announceReservationsChanged, useOnReservationsChanged } from "./reservationsChanged";

describe("reservationsChanged", () => {
  it("runs the listener each time reservations are announced as changed", () => {
    const onChange = vi.fn();
    renderHook(() => useOnReservationsChanged(onChange));
    announceReservationsChanged();
    announceReservationsChanged();
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it("calls the latest callback after a re-render", () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ cb }) => useOnReservationsChanged(cb), { initialProps: { cb: first } });
    rerender({ cb: second });
    announceReservationsChanged();
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("stops listening when the screen goes away", () => {
    const onChange = vi.fn();
    const { unmount } = renderHook(() => useOnReservationsChanged(onChange));
    unmount();
    announceReservationsChanged();
    expect(onChange).not.toHaveBeenCalled();
  });
});
