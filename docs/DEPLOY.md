# Deploying RistoManager Cloud

Two ways to run it. **Locally** is tested end to end. **On a server** is written and its
configuration validates, but it has **not been run on a real server yet**: treat the first
deployment as a rehearsal and expect to fix small things.

## 1. On your computer (tested)

You need Docker. From the repository root:

```bash
docker compose up --build
```

First start takes a few minutes (it builds both images). Then:

| What | Where |
|------|-------|
| Web app | http://localhost:3000 |
| API | http://localhost:8080 (health: `/actuator/health`) |

A demo restaurant is created automatically. Sign in at http://localhost:3000/login with
`owner@demo.ristomanager.local` (also `manager@`, `waiter@`, `kitchen@` at the same domain),
password `Demo-Password-2026`. Try it with two browser windows: the waiter on `/orders` (use
your phone's size), the kitchen on `/kitchen`.

To start from scratch: `docker compose down -v` (this deletes the database).

Everything runs on plain HTTP here, which is why the refresh cookie is not marked Secure
(`COOKIE_SECURE=false`). Never use this setup on the internet.

## 2. On a server (one machine, HTTPS)

### What you need
- A Linux server (a small VPS is enough for a pilot) with Docker and the Compose plugin.
- A domain name pointing at the server's IP (an `A` record). Ports 80 and 443 open.
- The images, which CI publishes to `ghcr.io/<owner>/ristomanager-backend` and `-frontend`
  after every push that passes all tests. Packages from a new repository start **private**:
  either make them public (GitHub, Packages, package settings) or run
  `docker login ghcr.io` on the server with a token that can read packages.

### Steps
```bash
git clone https://github.com/d4b0mb/progettoIGPE.git && cd progettoIGPE
git checkout ristomanager-cloud
cp deploy/.env.prod.example deploy/.env
# fill in deploy/.env: DOMAIN, and three secrets generated with  openssl rand -base64 32
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
```

Caddy gets the HTTPS certificate by itself the first time the domain answers. Then open
`https://your-domain/signup` and create the real restaurant. (The demo seed is not part of the
production setup.)

### Updating
```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
```
Database changes apply by themselves on start (Flyway). For production pin `IMAGE_TAG` to an exact
`sha-xxxxxxx` build instead of `cloud-latest`, so an update is a choice, not a surprise.
Expect a few seconds of interruption while the backend restarts; zero-downtime updates are what
Kubernetes (Phase 3) is for.

### Backups (do this before real customers)
`deploy/backup.sh` writes a compressed dump and keeps the last 14. Schedule it and copy the files
off the server:
```
0 3 * * *  /path/to/progettoIGPE/deploy/backup.sh /var/backups/ristomanager
```
Test a restore at least once: `gunzip -c file.sql.gz | docker compose ... exec -T db psql -U ristomanager -d ristomanager`
into a scratch database. An untested backup is a hope, not a backup.

### Security checklist before the first real restaurant
- [ ] `deploy/.env` has three different strong secrets and is not in git (it is git-ignored).
- [ ] Only ports 80 and 443 are open (the database is not published by this setup).
- [ ] `COOKIE_SECURE` is `true` (set in the production compose) and you reach the site over HTTPS.
- [ ] A backup ran, and a restore was tested.
- [ ] Known gaps are accepted: no login rate limiting, no password reset, no monitoring/alerts yet.
      See `docs/HANDOFF.md`.

## What CI does on every push
Backend tests (real PostgreSQL), frontend lint/types/tests/build, then the whole stack is started
and 10 browser tests run against it. Only if all pass are the images published.
