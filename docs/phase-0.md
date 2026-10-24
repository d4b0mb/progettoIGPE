# Phase 0 - Foundations

**Goal:** a repo, a Spring Boot skeleton, PostgreSQL in Docker Compose and a CI
pipeline. **Done when:** one endpoint and its test pass in CI.

## What was built

| Piece | Where | Purpose |
|-------|-------|---------|
| Spring Boot app | `backend/` | The API. Today it has one endpoint, `GET /api/v1/status`. |
| Flyway migration | `backend/src/main/resources/db/migration/V1__create_restaurant.sql` | Creates the `restaurant` table, the tenant root. |
| Integration test | `backend/src/test/.../StatusControllerIT.java` | Starts real PostgreSQL, checks the endpoint and the migration. |
| Dockerfile | `backend/Dockerfile` | Multi-stage, non-root, layered image. |
| Compose stack | `docker-compose.yml` | PostgreSQL + backend with one command. |
| CI | `.github/workflows/ci.yml` | Builds, runs all tests, builds the image on every push. |

## Decisions worth knowing

**Unit tests vs integration tests.** Maven's Surefire plugin runs `*Test` classes;
Failsafe runs `*IT` classes. The split keeps fast tests fast and makes slow,
Docker-dependent tests explicit. An early mistake: without Failsafe configured,
the `*IT` test was silently skipped and the build still said SUCCESS. A green build
only means something if you have seen the tests run.

**Real database in tests.** Testcontainers starts the same `postgres:17` image the
Compose file uses. Slower than an in-memory fake (about 20 s for the first test),
but the fake behaves differently from production PostgreSQL, which is exactly where
tenant-isolation bugs and SQL differences hide.

**Two endpoints for "is it alive".** `/actuator/health` is for machines
(Kubernetes probes). `/api/v1/status` is a normal versioned API endpoint for
humans and end-to-end tests. Different audiences, different stability promises.

**Migrations from day one.** Schema lives in versioned Flyway files, never edited
by hand, so every environment builds the same database from scratch.

**Docker image.** Multi-stage build keeps the compiler out of the final image;
dependencies sit in their own layer so rebuilds after a code change are fast;
the process runs as a non-root user, limiting damage if the app is compromised.

**Limits and known gaps.**
- No authentication yet: the status endpoint is public by design. Security arrives
  in Phase 1.
- The backend container has no `HEALTHCHECK`; the JRE image has no `curl`, and
  Kubernetes will use the actuator probes instead.
- The Docker image build was verified by CI, not on the author's sandbox (Docker
  Hub rate-limits it).
