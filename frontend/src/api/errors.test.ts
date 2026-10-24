import { describe, expect, it } from "vitest";
import { ApiError, toApiError } from "./errors";

describe("toApiError", () => {
  it("reads the detail and the per-field messages of a problem response", () => {
    const error = toApiError(400, { detail: "Validation failed", fields: { phone: "Enter a valid phone number" } });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.message).toBe("Validation failed");
    expect(error.fields).toEqual({ phone: "Enter a valid phone number" });
  });

  it("copes with a body that is missing or not an object", () => {
    expect(toApiError(500, undefined).message).toBe("Request failed (500)");
    expect(toApiError(502, "Bad Gateway").fields).toEqual({});
    expect(toApiError(500, null).status).toBe(500);
  });
});
