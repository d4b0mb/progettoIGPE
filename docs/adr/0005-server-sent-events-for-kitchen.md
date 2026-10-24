# 0005 - Server-Sent Events for the live kitchen screen

**Status:** Accepted (supersedes WebSocket in the master brief)

## Context
Orders appear on the kitchen screen the moment a waiter sends them. Waiters send
orders and the kitchen marks them ready through ordinary REST calls; only
server-to-kitchen updates need to be pushed.

## Decision
Use Server-Sent Events (one-way server push over plain HTTP). Revisit WebSocket
only if a genuinely two-way real-time need appears.

## Consequences
- Pros: simpler than WebSocket; the browser reconnects automatically; works
  through ordinary HTTP infrastructure.
- Cons: one direction only; with more than one backend instance, events must be
  shared between instances (PostgreSQL LISTEN/NOTIFY or Redis) so every kitchen
  screen hears every order.

## As built (Block 4)
- `GET /api/v1/orders/stream` sends `ticket-created` and `ticket-updated` events
  to subscribers of the same restaurant only. A `ready` event confirms the stream is
  live; clients then load `GET /api/v1/orders/active` for the current queue (the
  stream carries only changes, so reconnecting always reloads the queue).
- Events are sent after the database transaction commits, so a screen never shows an
  order that was rolled back.
- Browsers' built-in `EventSource` cannot send an `Authorization` header, so the
  frontend reads the stream with `fetch` and keeps the same bearer-token security
  as every other call.
- A stream closes after 10 minutes and the client reconnects with a fresh token.
  This bounds how long a deactivated user could keep listening, and keeps
  idle-connection timeouts from proxies from looking like failures.
- A 25-second heartbeat keeps connections open through proxies and detects dead ones.
- Subscribers live in memory, so this serves one API instance. Several instances
  need a shared channel (PostgreSQL LISTEN/NOTIFY first) behind the same
  `TicketEvents` class. Not needed for the pilot.
