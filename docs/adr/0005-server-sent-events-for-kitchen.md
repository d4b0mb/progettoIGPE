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
