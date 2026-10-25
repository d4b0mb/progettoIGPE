# Data retention

A job runs every night at 03:30 (server time) and erases:

| What | When | How |
|------|------|-----|
| Guest name, phone, email, notes, allergens on reservations | 90 days after the reservation | Replaced with blanks; the booking row stays for statistics |
| Guest name and allergens on order tickets | 90 days after the ticket | Same |
| Activity log entries | 365 days | Deleted |
| Expired or revoked refresh tokens | when dead | Deleted |

Change the periods or switch it off with environment variables on the backend:
`GUEST_DATA_DAYS` (default 90), `AUDIT_DAYS` (default 365), `RETENTION_ENABLED` (default true).
Staff accounts and menu data are not touched. Every run is logged in the backend log.
The rules live in the database function `retention_purge` (migration V8) and are tested in `RetentionIT`.
