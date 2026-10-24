"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/api/client";
import { refreshSession } from "@/api/client";
import { unwrap } from "@/api/call";
import { token } from "@/api/token";
import { SseParser } from "@/lib/sse";
import { applyTicket, byQueueOrder, type Ticket, type TicketStatus } from "./tickets";

const STREAM_URL = "/api/v1/orders/stream";

async function openStream(signal: AbortSignal): Promise<Response> {
  const open = () =>
    fetch(STREAM_URL, {
      signal,
      headers: { Accept: "text/event-stream", Authorization: `Bearer ${token.get() ?? ""}` },
    });
  let res = await open();
  if (res.status === 401 && (await refreshSession())) res = await open();
  if (!res.ok || !res.body) throw new Error(`Stream failed (${res.status})`);
  return res;
}

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

/**
 * The live list of orders still on the kitchen screen.
 *
 * It connects to the server's event stream, loads the current queue as soon as the
 * stream says it is ready, and then applies each change as it arrives. If the
 * connection drops (Wi-Fi, a deploy, the server's 10-minute limit) it reconnects by
 * itself, backing off up to 15 seconds, and reloads the queue so nothing is missed.
 */
export function useTicketStream() {
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [connected, setConnected] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;

    async function loadQueue() {
      const queue = await unwrap(api.GET("/api/v1/orders/active"));
      if (!signal.aborted) {
        setTickets([...queue].sort(byQueueOrder));
        setLoaded(true);
      }
    }

    async function run() {
      let delay = 1000;
      while (!signal.aborted) {
        try {
          const res = await openStream(signal);
          const reader = res.body!.getReader();
          const decoder = new TextDecoder();
          const parser = new SseParser();
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            for (const event of parser.push(decoder.decode(value, { stream: true }))) {
              if (event.event === "ready") {
                delay = 1000;
                setConnected(true);
                await loadQueue();
              } else if (event.event === "ticket-created" || event.event === "ticket-updated") {
                const incoming = JSON.parse(event.data) as Ticket;
                setTickets((current) => applyTicket(current, incoming));
              }
            }
          }
        } catch {
          // Fall through to reconnect; an aborted fetch ends the loop below.
        }
        if (signal.aborted) break;
        setConnected(false);
        await wait(delay, signal);
        delay = Math.min(delay * 2, 15_000);
      }
    }

    void run();
    return () => controller.abort();
  }, []);

  const setStatus = useCallback(async (ticket: Ticket, status: TicketStatus) => {
    // The server pushes the change back over the stream, so every screen updates, including this one.
    const updated = await unwrap(api.PATCH("/api/v1/orders/{id}/status", { params: { path: { id: ticket.id } }, body: { status } }));
    setTickets((current) => applyTicket(current, updated));
  }, []);

  return { tickets, connected, loaded, setStatus };
}

/** The current time, refreshed every half minute, for "waiting 7 min" labels. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
