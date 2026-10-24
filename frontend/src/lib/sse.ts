/**
 * A parser for Server-Sent Events, fed with raw chunks as they arrive from the network.
 *
 * Why not the browser's built-in EventSource? It cannot send an Authorization header,
 * and our API authenticates every request with a bearer token. So the stream is read
 * with fetch, and this turns its bytes back into events.
 *
 * Chunks can split an event anywhere, so the parser keeps whatever is incomplete and
 * adds it to the front of the next chunk.
 */
export interface SseEvent {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = "";

  /** Feed the next piece of text; returns every event that is now complete. */
  push(chunk: string): SseEvent[] {
    // Streams may use \n, \r\n or \r as line breaks.
    this.buffer += chunk.replace(/\r\n|\r/g, "\n");
    const events: SseEvent[] = [];
    let end: number;
    while ((end = this.buffer.indexOf("\n\n")) !== -1) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const parsed = parseBlock(block);
      if (parsed) events.push(parsed);
    }
    return events;
  }
}

function parseBlock(block: string): SseEvent | null {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line === "" || line.startsWith(":")) continue; // blank line or comment (a keep-alive)
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
  }
  return data.length === 0 && event === "message" ? null : { event, data: data.join("\n") };
}
