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
| 0 | Foundations: Spring Boot, PostgreSQL, Docker, CI | **Done** (see [notes](docs/phase-0.md)) |
| 1 | Back-office core: login, roles, restaurants, floor plan, reservations | Next |
| 2 | Live kitchen: order entry, kitchen screen | Planned |
| 3 | Production and pilot: k3s, staging/production, backups, monitoring | Planned |
| 4 | Public booking site | Planned |

## Stack

Java 21 and Spring Boot 4 (API), PostgreSQL 17 with Flyway, Next.js (all frontends,
from Phase 1), Docker, GitHub Actions; Kubernetes (k3s) from Phase 3. The reasoning
behind each choice is in [`docs/adr`](docs/adr/README.md).

## Run it locally

You need Docker, and a JDK 21 if you want to run the tests outside Docker.

```bash
docker compose up --build
curl localhost:8080/api/v1/status      # {"application":"ristomanager-cloud","version":"...","database":"UP"}
curl localhost:8080/actuator/health    # health probe (used by Kubernetes later)
```

Copy `.env.example` to `.env` to change the local database credentials.

## Run the tests

```bash
cd backend
./mvnw verify
```

Integration tests start a real PostgreSQL in Docker through Testcontainers, so
Docker must be running. No mocks stand in for the database.

## Repository layout

```
backend/        Spring Boot API (Maven)
docs/adr/       Architecture decision records
docs/phase-0.md What Phase 0 built and why
docker-compose.yml   Local stack: PostgreSQL + backend
```

## Why Kubernetes, at this size?

Because a restaurant cannot be down during service, and updates must not interrupt
it. Kubernetes gives zero-downtime rolling updates and a setup that grows to more
servers without being rebuilt. It arrives only in Phase 3, after the product works
on plain Docker Compose. See [ADR 0006](docs/adr/0006-deployment-path.md).
