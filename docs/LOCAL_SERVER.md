# Running it on a spare PC for the whole restaurant

One always-on computer runs the app; phones and tablets on the same network open it in the browser.
Costs nothing. Not reachable from outside the building (see the end for that).

## What you need
- A PC or laptop that stays on, with Docker Desktop (Windows/Mac) or Docker Engine (Linux), about 8 GB RAM.
- A router you can log into, and a Wi-Fi the guests cannot join (staff network or isolated guest network).

## One-time setup
1. **Get the code and pick secrets.** `git clone`, then `git checkout ristomanager-cloud`.
   Copy `.env.example` to `.env` and change at least:
   - `POSTGRES_PASSWORD` and `APP_DB_PASSWORD` to long random values (set them before the first start:
     the database keeps the first ones it saw).
   - `JWT_SECRET` to 32+ random characters (`openssl rand -base64 32`). If left empty a new one is made on
     each start and everyone is signed out whenever the server restarts.
2. **Start it:** `docker compose up --build -d`. The first start builds the images (a few minutes) and creates
   the demo restaurant. Check with `docker compose ps`.
3. **Give the PC a fixed address.** In the router, find "DHCP reservation" / "static lease" and pin this PC
   (for example 192.168.1.20). Otherwise the address changes and the link breaks.
4. **Open the firewall** for TCP port 3000 on private networks. Windows (PowerShell as administrator):
   `New-NetFirewallRule -DisplayName "RistoManager" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow -Profile Private`
5. **Open `http://192.168.1.20:3000`** on any device on the network. Save it to the phone's home screen.
6. **Start on boot.** Docker Desktop: Settings > General > "Start Docker Desktop when you sign in".
   Containers restart on their own (`restart: unless-stopped`). Also set the PC not to sleep (power settings).

Create the real restaurant with "Create an account" on the sign-in page. The demo restaurant can stay
for practice; its password is public in the docs, so do not keep real data in it.

## Every day
- Update to a new version: `git pull`, then `docker compose up --build -d`. Data is kept.
- Logs: `docker compose logs -f backend`. Stop: `docker compose down` (data kept; `down -v` erases it).

## Backups (do this)
Everything lives in one Docker volume. If the disk dies it is gone. Daily copy to another drive:

    docker compose exec -T db pg_dump -U ristomanager ristomanager > backup-YYYY-MM-DD.sql

Schedule it (Windows Task Scheduler, or cron on Linux) and keep the files on a different disk.
Restore into a fresh stack: `docker compose exec -T db psql -U ristomanager ristomanager < backup.sql`.
Test a restore once before you rely on it.

## Things to know
- It is plain `http://` on the local network. Passwords travel unencrypted inside the building, which is
  acceptable on a network you control and not on an open Wi-Fi.
- Only port 3000 is open to the network. The database and API ports are reachable from the PC itself only.
- A local assistant (Ollama on the same PC) is reachable from the API container at `host.docker.internal:11434`.

## Reaching it from outside later (still free)
A Cloudflare Tunnel gives an `https://` address pointing at this PC without opening any router port.
It needs a domain name (a few euros a year). When you do that, set `COOKIE_SECURE=true` in `.env`.
For a paid server instead, see `docs/DEPLOY.md`.
