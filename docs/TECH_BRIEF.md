# RistoManager Cloud: technical brief

Plain-English description of what the system is made of, why each piece was chosen, and how the pieces fit.
Versions are the ones in the repository on 2026-10-25. For the reasoning behind each big decision see `docs/adr/`.

## 1. What it is
A web service where restaurants run their day: reservations, a drawn floor plan, a menu with allergens,
order taking on phones and tablets, and a live kitchen screen. Many restaurants share one running system
("multi-tenant"); each sees only its own data. It is the web rewrite of a desktop Java app (frozen on branch `uniVersion`).

## 2. The big picture
```
 phone / tablet / PC browser
          |  https (or http on a private network)
          v
 +------------------+     /api/*      +------------------+      SQL      +------------------+
 |  Next.js web app | --------------> |  Spring Boot API | ------------> |  PostgreSQL 17   |
 |  (screens, i18n) |  proxied        |  (rules, security)|               |  (data + RLS)    |
 +------------------+                 +------------------+               +------------------+
                                            |  Server-Sent Events (live kitchen)
                                            +-----> kitchen screen, waiters' phones
```
Three programs, each in its own container: database, API, web app. `docker compose up --build` starts all three
and creates two demo restaurants.

## 3. The technologies
| Layer | Technology (version) | What it does here | Why this one |
|-------|----------------------|-------------------|--------------|
| Database | PostgreSQL 17 | Stores everything; enforces the safety rules itself | Mature, strict, has row-level security and "exclusion constraints" used for double-booking |
| Migrations | Flyway (10 migrations, V1 to V10) | Versioned schema changes applied at startup | Repeatable: every environment gets the same schema |
| API language | Java 21 | The server code | Strong typing, long-term support, fits the author's studies |
| API framework | Spring Boot 4.1.2, Spring MVC | Endpoints, validation, dependency wiring | Industry standard; huge ecosystem |
| Data access | Spring `JdbcClient` (plain SQL, no ORM) | Reads and writes the database | Every query is visible and testable; no hidden magic |
| Pooling | HikariCP | Reuses database connections | Default, fast; also where each connection is tagged with the restaurant |
| Security | Spring Security 7, Nimbus JWT (HS256), BCrypt | Login, roles, tokens | Standard building blocks, nothing home-made |
| API contract | springdoc OpenAPI | Describes every endpoint as a machine-readable file | The frontend's types are generated from it, so API and screens cannot drift apart |
| Live updates | Server-Sent Events | Pushes new/changed orders to screens | One-way push is all the kitchen needs; simpler than WebSockets |
| Web app | Next.js 16.4, React 19.3, TypeScript 5.9 | All screens, one app for every role | One codebase for waiter phone, kitchen tablet, back office; later the public booking page |
| Styling | Tailwind CSS 4 with design tokens | Layout, colours, fonts per restaurant "look" | A look is just a set of values, so a restaurant can have its own |
| Translations | next-intl 4 | Italian (default) and English | Every text lives in a file; adding a language adds a file |
| API client | openapi-fetch + openapi-typescript | Typed calls to the API | Wrong field names fail at build time |
| Packaging | Docker, Docker Compose | Same images on a laptop and on a server | Easy to run anywhere |
| Production proxy | Caddy (in `deploy/`) | HTTPS and routing on one server | Automatic certificates |
| CI | GitHub Actions | Builds and tests every push, publishes images to ghcr.io | Catches mistakes before they reach a server |
| Tests | JUnit + Testcontainers (real PostgreSQL), Vitest, Playwright 1.64 | Unit, integration and real-browser tests | See section 6 |

## 4. How the main things work
**Who is allowed to do what.** Four roles: owner, manager, waiter, kitchen. Every API endpoint checks the role on
the server; hiding a button is never the protection. Login returns a short-lived access token (15 minutes) plus a
refresh cookie (HttpOnly, SameSite=Strict) that rotates on every use; reusing an old one signs the user out everywhere.
Passwords are BCrypt-hashed (10 to 72 characters).

**Keeping restaurants apart (two locks).** Every table row carries a `restaurant_id`, and every query filters on it.
Second lock: PostgreSQL row-level security. The API connects as a restricted database role; each request tags its
connection with the restaurant, and the database itself refuses to show or change any other restaurant's rows, even
if the code had a bug. A test fails the build if a table is added without this protection.

**Double-booking is impossible.** A database exclusion constraint forbids overlapping bookings on one table, and a
per-table lock makes simultaneous requests take turns. Tested with 16 parallel requests.

**An order's journey.** Waiter taps Send, the API checks login, role and restaurant, copies each dish's allergens
from the menu (never from the client), crosses them with the allergies of the party seated at that table, saves the
ticket, then pushes it by Server-Sent Events to that restaurant's screens only. Measured: about 150 ms to the kitchen.
An order can be split into servings; the kitchen receives them one after the other (ADR 0011).

**Account safety and history.** An append-only activity log records sign-ins (good and failed), password changes,
staff changes, order steps and refused requests; the application can only add rows, never edit or delete. The owner
can reset staff passwords and recover a forgotten own password with two personal questions (stored only as hashes).
A nightly job erases guest names, phones, emails and notes 90 days after a visit, and old log entries after 365 days.

**Each restaurant's look.** A setting per restaurant (`classic` or `duodecim`) switches the colour tokens, fonts and
logo for all its staff. The printed menu of Duodecim Pizze is loaded from a plain text file by an import script.

## 5. Where things live
| Path | Content |
|------|---------|
| `backend/` | Spring Boot API: packages auth, security, tenant, floor, reservation, menu, order, audit, recovery, retention, restaurant |
| `backend/src/main/resources/db/migration/` | The ten schema migrations |
| `frontend/` | Next.js app; `src/api` typed client, `src/components/*` one folder per feature, `messages/` translations |
| `frontend/openapi/openapi.json` | The API contract (generated by a backend test, guarded against drift) |
| `e2e/` | Playwright browser tests of the whole running stack |
| `scripts/` | Demo seed, menu importer, menu data |
| `infra/postgres/` | Creates the restricted database role |
| `deploy/` | One-server production setup with HTTPS |
| `docs/` | Handoff brief, deploy guides, retention, pizzeria trial, decision records |

## 6. How quality is checked
Every push runs three jobs on GitHub, and a fourth publishes images only if the others pass.
- Backend: 110 tests against a real PostgreSQL, including tenant isolation, parallel orders and the API contract guard.
- Frontend: lint, type check, 66 unit tests, production build.
- End to end: the whole stack starts and 19 Playwright tests drive real browsers (a phone order reaching the kitchen
  live, role access, floor plan zoom, servings, password recovery, phone-width layout with no sideways scroll).

## 7. Size
About 7,100 lines of backend code and 5,500 of backend tests, 7,400 lines of frontend and 1,200 of frontend tests, 1,100 of browser tests, 10 migrations (counted 2026-10-27).

## 8. Known limits (honest list)
- Live updates are kept in memory: one API instance only. Several instances need a shared channel (ADR 0005).
- Recovery-question attempts are throttled in memory, per instance. Security questions are weaker than email links
  or printed recovery codes. There is no login lockout by choice; failures are logged instead.
- The Duodecim allergens are a draft derived from ingredient text and must be confirmed by the pizzeria.
- No payments, receipts or fiscal printing: it is not a till. No takeaway or delivery flow.
- The server setup in `deploy/` has not been run on a real server yet.
- Built since this brief was first written: floor plan from a photo and the staff assistant on a local model (ADR 0012). Not built yet: public booking page (Phase 4), Kubernetes (Phase 3), voice.
