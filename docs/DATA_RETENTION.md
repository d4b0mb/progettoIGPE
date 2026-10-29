# Data retention

A job runs every night at 03:30 (server time) and erases:

| What | When | How |
|------|------|-----|
| Guest name, phone, email, notes, allergens on reservations | 90 days after the reservation ended | The name is replaced with the word `Anonymised`; phone, notes and allergens are emptied and the email is removed. The booking row stays for statistics (date, time, party size, status) |
| Guest name, allergens and the note on order tickets | 90 days after the ticket was created | The name and the note are emptied and the allergens removed. The order stays: code, table number, status, times |
| The note on each line of those order tickets | Same | Emptied. The dish name, quantity, options and the dish's own allergens stay |
| Activity log entries | 365 days after the event | Deleted |
| Refresh tokens (the sign-in that lets a browser stay signed in) | Expired more than 7 days ago, or revoked and created more than 7 days ago | Deleted. A revoked token newer than 7 days, and any token that is still valid and not revoked, is kept |

Notes are cleared because they are free text and can hold a guest's name or an allergy detail.

Change the periods or switch it off with environment variables on the backend:
`GUEST_DATA_DAYS` (default 90), `AUDIT_DAYS` (default 365), `RETENTION_ENABLED` (default true).
Staff accounts and menu data are not touched. Every run is logged in the backend log (counts only, no personal data)
and written to the audit table as a `RETENTION_RUN` entry that belongs to no restaurant, so no restaurant's owner sees it in the activity screen.
The rules live in the database function `retention_purge` (migration V8, replaced by V11) and are tested in `RetentionIT`.

Error handling follows the same rule: if an unexpected database error happens, the backend answers with a generic
message and an error id, and writes only the error type and that id to its log, never the database's own message,
because that message can contain the row (and so the guest's details).
