# RistoManager Cloud

[![CI](https://github.com/d4b0mb/progettoIGPE/actions/workflows/ci.yml/badge.svg?branch=ristomanager-cloud)](https://github.com/d4b0mb/progettoIGPE/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A multi-tenant web service for restaurants. Staff run reservations, the floor plan
and orders from a back-office; the kitchen sees every order live; guests book from
a public page.

It is the web rewrite of RistoManager, a desktop Java app that lives, frozen, on
the [`uniVersion`](../../tree/uniVersion) branch. The domain model, rules and tests
carry over; the Swing interface is replaced by web apps.

> Experimental, AI-assisted project built to learn and practise real-world
> software engineering. Not production software yet.

## Status

| Phase | Goal | State |
|-------|------|-------|
| 0 | Foundations: Spring Boot, PostgreSQL, Docker, CI | Done ([notes](docs/phase-0.md)) |
| 1-2 | Back-office and live kitchen: logins and roles, tenant isolation, floor plan, reservations, menu, orders, live kitchen screen | Done, running with end-to-end tests |
| 3 | Production and pilot: Kubernetes (k3s), staging/production, monitoring | Next. A single-server setup is ready in [docs/DEPLOY.md](docs/DEPLOY.md) |
| 4 | Public booking site | Planned |

## Stack

Java 21 and Spring Boot 4 (API), PostgreSQL 17 with Flyway, Next.js 16 (one web app for staff
and kitchen screens), Docker, GitHub Actions; Kubernetes (k3s) from Phase 3. The reasoning
behind each choice is in [`docs/adr`](docs/adr/README.md).

## Try it

You need Docker.

```bash
docker compose up --build
```

Open http://localhost:3000 and sign in as `owner@demo.ristomanager.local` with password
`Demo-Password-2026` (also `manager@`, `waiter@`, `kitchen@`). A demo restaurant is created
for you. Open `/orders` (as the waiter) and `/kitchen` (as the kitchen) side by side to watch an
order travel live. More in [docs/DEPLOY.md](docs/DEPLOY.md).

## Run the tests

```bash
cd backend && ./mvnw verify                                              # needs Docker (real PostgreSQL)
cd frontend && npm ci && npm run lint && npm run typecheck && npm test   # then: npm run build
# whole stack in a real browser: start it with docker compose up --build, then
cd e2e && npm ci && npx playwright install chromium && npx playwright test
```

No mocks stand in for the database: backend tests start a real PostgreSQL through Testcontainers.

## What it does

- **Restaurants are isolated twice.** Every query filters by restaurant, and PostgreSQL
  row-level security enforces it again, so a coding mistake cannot leak data between restaurants.
- **A table cannot be double-booked**, even when two people book it at the same instant (a database
  constraint, tested with 16 parallel bookings).
- **Allergies are crossed with the menu.** If the party at a table declared an allergy, dishes that
  contain it are flagged before sending and the kitchen screen shows a conflict banner.
- **The kitchen screen is live** (server-sent events), reconnects by itself, and shows priority and
  lateness.
- **Phone and desktop**: bottom navigation on phones, sidebar on desktop; Italian and English.

## Repository layout

```
backend/         Spring Boot API (Maven), Flyway migrations
frontend/        Next.js web app; openapi/ is the API contract the types are generated from
e2e/             Browser tests against the whole running stack
deploy/          Single-server production setup (HTTPS, backups)
infra/postgres/  Creates the restricted database role the API runs as
scripts/         Demo data
docs/            ADRs (why), HANDOFF.md (state of the project), DEPLOY.md, phase notes
```

## Why Kubernetes, at this size?

Because a restaurant cannot be down during service, and updates must not interrupt
it. Kubernetes gives zero-downtime rolling updates and a setup that grows to more
servers without being rebuilt. It arrives only in Phase 3, after the product works
on plain Docker Compose. See [ADR 0006](docs/adr/0006-deployment-path.md).
