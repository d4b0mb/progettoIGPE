# RistoManager Cloud: project brief

Written 2026-10-29 for a job application. Every claim below is something that exists in the repository; the last section says what is not proven yet.

## What it is
A multi-tenant web system for restaurants: reservations, an editable floor plan, menu, table orders, a live kitchen screen, and a staff assistant powered by a language model that runs **on the restaurant's own machine**. Built solo, alongside a part-time job as a waiter, so the requirements come from real service: what the floor needs at 8 pm on a Saturday. Italian and English UI, 24-hour clock. A trial at a real pizzeria is planned.

## What it does
- **Reservations and floor:** bookings with allergy notes, arrivals, no-shows, table moves; a floor plan editor; a draft floor plan generated from a photo of the room (the owner always reviews it).
- **Orders and kitchen:** waiters place orders, the kitchen sees tickets live (server-sent events) and moves them NEW, PREPARING, READY.
- **Roles:** owner, head waiter, waiter, kitchen, each with different permissions, enforced twice (API and database).
- **Staff assistant:** ask in plain language ("who is arriving at 20:00?", "move the Rossi booking to table 5"). The model can only *propose* a change; a person presses Confirm, which is a separate authenticated request. Proposals are single-use, expire in 10 minutes and are tied to the user and restaurant.
- **Voice (optional, off by default):** speak to the assistant and hear the answer through local speech engines; voice never confirms or executes anything.

## Technologies
- **Backend:** Java 21, Spring Boot 4, Maven, PostgreSQL 17, Flyway migrations (10 so far), row-level security with a restricted application role (a bug in application code cannot read another restaurant's rows).
- **Frontend:** Next.js 16, React 19, TypeScript, Tailwind 4, next-intl.
- **AI:** Ollama with a local 9B model (Qwen 3.5), tool calling, no data leaves the machine. OpenAI-style audio API adapters for local speech-to-text and text-to-speech (faster-whisper/speaches, Kokoro).
- **Infrastructure:** Docker and Docker Compose (dev, trial, a production variant with Caddy and HTTPS), images published to GitHub Container Registry.
- **Kubernetes:** drafted (see the last section).

## Engineering practice
- **Tests:** about 190 backend unit tests and 165 integration tests that run against a real PostgreSQL in Testcontainers (including tenant isolation and role permissions); 211 frontend unit tests; Playwright end-to-end tests that drive the whole stack in a real browser (including the voice flow with a fake microphone); a contract test that fails if the API description changes without being regenerated.
- **Continuous integration (GitHub Actions):** backend build and tests, frontend lint, type check, tests and build, the whole-stack Playwright run, and image publishing. Every push is checked; broken runs are fixed before moving on.
- **AI evaluation:** a set of 53 scripted assistant cases (including allergy safety and attempts to trick it) run against the real model through the real endpoint; 47 of 50 passed in the last full run. Failures are kept and listed, not hidden.
- **Safety design for the AI** (recorded as architecture decision records, ADR 0012 and 0013): the model proposes, a person confirms; tools are role-gated; code, not the model, replaces any allergy-safety claim and any false "I did it" claim; guest-written text is quarantined from instructions; concurrency and per-user rate limits protect the single local model; nothing from voice is stored.
- **Security reviews:** the whole codebase was reviewed with an independent read-only review agent, findings were triaged and fixed (for example JSON handling that could throw on untrusted input, recovery-flow user enumeration, memory growth in throttles). An open-issues list in `docs/HANDOFF.md` tracks what is still pending.

## Working with AI agents (how it was built)
The project is built with Claude Code as a team of agents with separate jobs, and I act as the person who decides and verifies:
- a **dev** agent writes code from precise briefs but cannot run anything; I review every line, run the tests in a Linux container, and commit;
- a **read-only review** agent (FIM) checks finished work for functional problems;
- **demo staff agents** (chef, head waiter, dish runner) drive the real HTTP API so the live screens show realistic traffic;
- work is split into independent chunks that run in parallel, then merged only after the tests pass.
Lessons are written down (`docs/HANDOFF.md`): for example that tests must not depend on the time of day, and that agent-written code is only "done" once it has been run.
Architecture is documented in 14 decision records, with plans for the voice layer and for AI that talks to customers (WhatsApp and Telegram first, phone later), designed so one assistant serves both staff and guests without ever being able to disrupt the floor.

## Kubernetes: where we are, and why we are only starting
Manifests for a single-node k3s cluster (kustomize, staging and production overlays, non-root read-only pods, network policies, probes, a backup job) are **drafted, and rendered locally, but have never been applied to a cluster**. This is deliberate:
1. The app currently runs well on Docker Compose, and the single-server production setup has not yet been rehearsed on a real server. A cluster on top of an untried deployment adds two unknowns at once, so the plan puts that rehearsal first.
2. The backend keeps some state in memory (live kitchen stream, pending assistant proposals, rate limiters), so it must run as **one replica** for now. Running several needs that state moved to Postgres first; this is written down as a decision (ADR 0014) with the order of work.
3. Rolling updates need database migrations that add before they remove, and a graceful-shutdown setting that is not added yet.
4. So I claim no "zero downtime" until a script measures it during a real update.
The plan (`docs/KUBERNETES_PLAN.md`) has numbered steps, each with a check that shows it worked. Kubernetes here is a learning goal and a future operations tool, not a need of a one-restaurant pilot.

## What is not proven yet (honest list)
- The restaurant trial has not happened; the voice engines have not been started on the GPU yet (the voice layer is tested with fakes and a lab of 80 tests; the real engines and a listening test come next).
- Manifests are unapplied; the production Compose setup is unrehearsed on a real server.
- The assistant is a 9B local model: good on the scripted cases, not perfect, and always behind the confirm button for writes.
- Some of the latest hardening work (recovery, session expiry, audit limits) is written and is waiting for review and a full test run.
