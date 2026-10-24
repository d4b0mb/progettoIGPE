# 0009 - Preventing double bookings in the database

**Status:** Accepted

## Context
The desktop app checked for overlapping reservations in application code before
saving. With one user at a time that works. On the web, two staff members (or a
guest and a waiter) can book the same table at the same instant; both checks pass,
both saves succeed, and the table is double-booked.

## Decision
Three layers, each catching what the previous one cannot:
1. **A friendly check** in the service gives readable errors ("Table 3 is already
   booked by Rossi at 20:00") and explains seats, out-of-service and similar rules.
2. **A per-table advisory lock** (`pg_advisory_xact_lock`) makes simultaneous
   bookings of one table take turns, so the friendly check sees the committed
   result of the request before it. Without it, 16 parallel bookings produced
   PostgreSQL deadlocks inside the exclusion constraint during testing.
3. **An exclusion constraint** (`EXCLUDE USING gist (table_id WITH =,
   tstzrange(starts_at, ends_at) WITH &&) WHERE live status`) is the final
   guarantee. No code path, present or future, can store two overlapping live
   reservations for one table.

Reservations store `starts_at` and `ends_at` (not a duration) because a range
expression built from a duration is not allowed in an index. The API still
exposes `durationMinutes`.

## Consequences
- Pros: correctness does not depend on every future code path remembering the
  check; a test with 16 parallel requests proves exactly one wins.
- Cons: needs the `btree_gist` extension; the advisory lock serialises bookings of
  the same table (fine: contention is per table, not global); the rule lives in the
  database as well as in code.

## Alternatives rejected
- Check-then-insert in code only: has the race described above.
- A row lock on the table row (`SELECT ... FOR UPDATE`): works too, but would also
  block unrelated updates to that table row such as changing its service status.
- Serializable transactions with retries: correct but pushes retry logic into every
  caller.
