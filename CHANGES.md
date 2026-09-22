# What changed in `ristorante-polished`

This folder is a copy of `Desktop\ristorante`. The original folder was not modified
(verified by comparing SHA-256 hashes of every original file before and after).
Everything below applies to this copy only.

The project, its structure and its Italian comments are unchanged in spirit: same
MVC split, same packages, same libraries. The work falls into three groups:
bugs fixed, features finished, and engineering (tests, build, docs).

---

## 1. Bugs fixed

Each entry: what went wrong, what was changed, how it was checked.
"Probe" means a small program run against **both** the original and the new code
to show the bug before and after. "Test" means one of the new JUnit tests.

| # | Problem in the original | Fix | Checked by |
|---|---|---|---|
| 1 | **Login: Enter submitted twice.** A `KeyListener` *and* the window's default button both called `onLogin()`. With a wrong password the second call ran after the field was cleared and replaced "Credenziali non valide" with "Inserisci nome utente e password". | Removed the `KeyListener`; Enter goes through the default button only. | Probe: original shows the wrong message, new shows the right one (and still logs in with Enter). |
| 2 | **Editing a reservation changed the live object.** If the model rejected the save and the user pressed Annulla, the list kept the invalid, unsaved data (e.g. an empty name). Same for the quick status buttons and the users form. | Dialogs and status buttons work on `Reservation.copy()` / `User.copy()`; the model replaces the list entry by id only after a successful save. | Probe (original: name becomes `""`) + test. |
| 3 | **Table choice reset in the reservation dialog.** Changing date, time, party size or duration re-selected the originally assigned table, discarding the user's choice. | Keeps the chosen table if still available; otherwise says why under the combo instead of switching silently. Shows how many tables are free. | Manual review + screenshot. |
| 4 | **Floor editor crash.** Selecting an element whose value was outside a spinner's fixed range (dragged 10 m off the room, rotated to 359.5°, a wall longer than 40 m, a table with > 40 seats) threw `IllegalArgumentException` and broke the properties panel. | Spinner ranges widen to include the current value. | Probe: original throws, new builds the panel. |
| 5 | **Floor editor: typed labels were lost** unless you pressed Enter. | Text fields apply as you type and record one undo step when you leave the field. | Manual review. |
| 6 | **Floor editor: coordinates went stale** after dragging an element. | Fields follow the element live while dragging. | Screenshot. |
| 7 | **Touchpad zoom:** any small two-finger scroll zoomed *out* (`getWheelRotation()` is 0 for sub-notch movement). | Uses `getPreciseWheelRotation()`. | Code review. |
| 8 | **Server on a busy port looked "running".** The bind error happened on a background thread and was not even logged; the window said "In esecuzione". | `start()` opens the port on the caller's thread and throws; the window shows a clear error with the command to use another port, and can stop/start the server. | Test (`aSecondServerOnTheSamePortFailsLoudly`). |
| 9 | **Concurrent tickets could collide.** Several client threads shared one JDBC connection with no synchronisation: two tickets could get the same code, or read each other's generated id. | Ticket handlers on the server are `synchronized`. | Test: 60 tickets sent at once from 4 stations → 60 distinct codes and ids. |
| 10 | **Allergies attached to the wrong dish.** When sending a ticket, the guest's declared allergies were merged into the *first dish's* allergen list (e.g. "water contains gluten"). The red kitchen banner then appeared on almost every ticket, so it stopped meaning anything. | `Ticket` now has separate `guestAllergens`. The alarm is the *intersection* with dish allergens: those dishes are red in the menu, red on the kitchen ticket, and sending asks for confirmation. Stored in a new DB column (added automatically to old databases). | Tests + screenshots. |
| 11 | **Theme toggle did nothing lasting.** It said "restart to apply", but startup always forced the dark theme. | The choice is saved with `java.util.prefs` and applied immediately by rebuilding the window. | Code review. |
| 12 | **Monospace font missing on Windows** ("DejaVu Sans Mono"), so timers fell back to a proportional font. | Picks the first installed of a list (Consolas on Windows). | Screenshots. |
| 13 | **Deleting a table left dangling reservations** pointing at an id that a new table could later reuse. | Deleting a table with upcoming reservations asks first; saving the plan sets those reservations to "da assegnare" and says how many. | Test. |
| 14 | **Admin lockout.** An admin could deactivate or demote themselves (possibly leaving no admin at all); deleting an *inactive* admin was wrongly refused. | Model rules: you can't deactivate/demote/delete yourself; at least one active admin must remain. The form disables those fields for your own account. | Tests. |
| 15 | **Couldn't cancel a reservation whose table was removed or out of service.** Full table validation also ran for "Annullata"/"Completata". | Table checks apply only to statuses that occupy a table. | Test. |
| 16 | `deleteReservation` removed by object identity, so it failed silently with a copy. | Removes by id. | Test. |
| 17 | **Table status mixed two things.** Computed states (libero/prenotato/occupato) were saved to the DB, and the editor offered them in a combo even though the next refresh overwrote them. | `RestaurantTable` separates the operator's choice (`serviceStatus`: in servizio / da pulire / fuori servizio, saved) from the computed state (`liveStatus`, `transient`). The combo only offers the operator's choices. | Tests. |
| 18 | New reservations could be created on past days. | Validation rule (existing past reservations can still be updated). | Test. |
| 19 | The login form came **pre-filled with the admin password**. | Fields start empty; clicking a demo user fills them. | Screenshot. |
| 20 | The loyalty "priority Alta" bump stuck when the waiter switched to another table. | The automatic bump reverts if the waiter didn't change it by hand. | Screenshot. |

---

## 2. Features finished or added

- **Auto-reconnect.** Stations connect by themselves when the server starts and reconnect after a restart, so start order no longer matters. Click the status label to retry immediately.
- **Cross-station sync (same computer).** Reservations entered at one station appear within ~5 s at the other stations that use the same `ristorante.db` (`PRAGMA data_version`, one cheap query; tables are reloaded only when another connection wrote). Stations on *different* computers exchange tickets through the server but each keeps its own database, exactly as in the original: sharing reservations across machines would need them to travel over the network too.
- **Floor editor:** undo / redo (serialized snapshots — Memento pattern), "unsaved changes" badge, "Ripristina salvata", prompt to save on logout/exit/theme change, Esc to cancel, back to "Seleziona" after drawing, cursor position in metres, duplicate-number warning, upcoming reservations for the selected table.
- **Notifications:** toast + beep for a new ticket (kitchen) and a ready ticket (hall); nav badges for open and ready tickets.
- **Logout vs exit:** "Esci" offers *Cambia utente* (back to login) or *Chiudi*. The main window now releases its timers, listeners and connection when closed.
- **Keyboard:** Ctrl+1…5 sections, Ctrl+N new reservation, Ctrl+F search, Enter edit, Canc delete, Esc closes dialogs, Ctrl+Z/Y/D/S in the editor.
- **Reservations:** "No-show" and "Disdici" buttons, proper "Tutte le date" toggle, empty-table message, full name in a tooltip.
- **Reset demo data** button on the launcher (the sample reservations are dated on first launch, so after a few days "today" would be empty on presentation day).
- **Server window:** shows the LAN address to type on other machines; start/stop toggle.
- **Global error dialog** for unexpected exceptions instead of a silent console trace.
- `java -jar ristomanager.jar server 8422` now accepts a port (the old `server host port` form still works).

---

## 3. Engineering

- **Tests:** 43 JUnit 5 tests in `src/test/java` (model rules, permissions, floor geometry and snapshots, allergies, persistence and migration, real sockets with concurrent clients and reconnection). They use temporary databases and port 0, so they never touch `ristorante.db` or a running server.
- **`pom.xml`:** added `junit-jupiter` 5.14.4 (test scope) and pinned `maven-surefire-plugin` 3.5.4 (headless); removed `flatlaf-extras`, which nothing used.
- **Schema migration:** `Database` adds missing columns to databases created by the old version.
- **Dead code removed:** `LoginFrame.centered()`, `FloorPanel.toolSeparator()`, `Reservation.getRequiredSeats()`, and the empty `controller` package.
- **Eclipse:** the project name in `.project` is `ristorante-polished`, so it can be imported next to the original.
- **README** updated (Italian, same style) with the new behaviour, tests and shortcuts.

---

## 4. How it was verified, and what was not

Verified:
- `javac --release 17` with `-Xlint:all` (minus the usual Swing serialization noise): no errors, no warnings.
- 43/43 tests pass (run with the JUnit 5.14.4 jars bundled with Eclipse).
- Bug probes 1, 2 and 4 above: bug reproduced on the original code, absent on the new code.
- Every screen rendered off-screen in dark and light theme and inspected.

Not verified:
- A full `mvn package` run. The offline Maven cache on this machine doesn't have JUnit or the surefire JUnit provider; Eclipse/Maven will download them on first import.
- Clicking through the app by hand on a real display (the screens were rendered, not driven with a mouse).

---

## 5. Notes

- You can copy an existing `ristorante.db` into this folder: the new column is added automatically, and table states saved by the old version as "prenotato/occupato" are read back as "in servizio" (they are recomputed from reservations anyway).
- `CHANGES.md` is for you; delete it before submitting if you don't want it in the project.

---

## 6. Independent review

A separate read-only review (FIM) confirmed that the fixes above are present and
consistent between model and views, and raised four points:

| Point | Outcome |
|---|---|
| **F-1** Save/delete handlers only catch `ValidationException`; a database error thrown in a button handler might never reach the error dialog. | Tested: an exception thrown inside an `ActionListener` does reach `Thread.setDefaultUncaughtExceptionHandler` (on `AWT-EventQueue-0`), and the event thread keeps working, so the user sees the error dialog. No change needed. |
| **F-2** Reservation sync doesn't work across machines, but the docs said it did. | Correct. README, this file and the `syncWithDatabase` comment now say sync is for stations sharing one `ristorante.db`; across machines only tickets travel, as in the original. |
| **F-3** No SQLite busy timeout is configured. | Tested: sqlite-jdbc's default is 3000 ms, and a second writer waited about 1.5 s for a held lock and then succeeded. No change needed. |
| **F-4** "Ripristina dati di esempio" while other stations are open. | Known limitation: the confirmation dialog tells you to close the other stations first. |
