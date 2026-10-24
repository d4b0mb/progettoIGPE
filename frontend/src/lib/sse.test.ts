import { describe, expect, it } from "vitest";
import { SseParser } from "./sse";

describe("SseParser", () => {
  it("reads a complete event", () => {
    const parser = new SseParser();
    expect(parser.push('event: ticket-created\ndata: {"code":"C-001"}\n\n')).toEqual([
      { event: "ticket-created", data: '{"code":"C-001"}' },
    ]);
  });

  it("waits for the rest of an event split across chunks, even mid-word", () => {
    const parser = new SseParser();
    expect(parser.push("event: ticket-up")).toEqual([]);
    expect(parser.push('dated\ndata: {"a":')).toEqual([]);
    expect(parser.push("1}\n")).toEqual([]);
    expect(parser.push("\n")).toEqual([{ event: "ticket-updated", data: '{"a":1}' }]);
  });

  it("returns several events from one chunk, in order", () => {
    const parser = new SseParser();
    const events = parser.push("event: ready\ndata: ok\n\nevent: ticket-created\ndata: 1\n\nevent: ticket-updated\ndata: 2\n\n");
    expect(events.map((e) => e.event)).toEqual(["ready", "ticket-created", "ticket-updated"]);
  });

  it("accepts Windows and old Mac line endings", () => {
    const parser = new SseParser();
    expect(parser.push("event: ready\r\ndata: ok\r\n\r\n")).toEqual([{ event: "ready", data: "ok" }]);
    expect(parser.push("event: x\rdata: y\r\r")).toEqual([{ event: "x", data: "y" }]);
  });

  it("ignores keep-alive comments", () => {
    const parser = new SseParser();
    expect(parser.push(": keepalive\n\n")).toEqual([]);
    expect(parser.push(": keepalive\n\nevent: ready\ndata: ok\n\n")).toEqual([{ event: "ready", data: "ok" }]);
  });

  it("joins multi-line data with newlines", () => {
    const parser = new SseParser();
    expect(parser.push("event: m\ndata: line one\ndata: line two\n\n")).toEqual([{ event: "m", data: "line one\nline two" }]);
  });

  it("treats an event with no name as a plain message", () => {
    const parser = new SseParser();
    expect(parser.push("data: hello\n\n")).toEqual([{ event: "message", data: "hello" }]);
  });
});
