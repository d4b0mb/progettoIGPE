# 0010. Activity log, owner password recovery, guest-data retention

Status: Accepted

## Decisions
**Activity log.** Every sign-in (good or bad), password change, staff change, order action and
denied request is written to `audit_event`. The API's database role can only read and add rows,
never edit or delete them. Entries are written in their own transaction, so a failed request
still leaves its trace. Passwords and recovery answers are never stored. Only the owner can
read the log (`/activity`).

**No lockout on failed sign-ins.** The owner chose logging over blocking: a lockout lets anyone
shut a real user out by typing wrong passwords. Failed attempts are logged with email and IP.
BCrypt's slowness limits guessing speed. Revisit if the service goes public at scale.

**Owner recovery.** The owner writes two personal questions; answers are normalised (case,
accents, spaces ignored) and stored as BCrypt hashes. A reset needs both answers and revokes
every session. Unknown or non-owner emails get decoy questions, so the form does not reveal
which accounts exist. Wrong guesses are throttled (5 per email per 15 min, 30 per IP per hour).
Staff do not use this: the owner resets their password.
Trade-offs: security questions are weaker than email links or recovery codes (answers can be
guessed or found out). The throttle lives in memory, so it is per API instance and resets on
restart. Recovery codes printed at setup would be a stronger later option.

**Retention.** A nightly job (03:30) blanks guest names, phones, emails, notes and allergens
90 days after the visit, and deletes audit entries older than 365 days and dead refresh tokens.
See `docs/DATA_RETENTION.md`.

## Known limits
IP addresses come from the proxy headers and can be faked by a client unless the proxy
overwrites them (the Caddy setup in `deploy/` does).
