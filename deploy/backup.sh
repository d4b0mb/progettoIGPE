#!/bin/sh
# Dumps the database to a dated, compressed file and keeps the last 14.
# Run it nightly from cron on the server, then copy the files OFF the server (a backup that
# lives on the same disk as the database is not a backup).
#   0 3 * * *  /path/to/deploy/backup.sh /var/backups/ristomanager
set -eu
DIR="${1:?usage: backup.sh <backup-directory>}"
COMPOSE="docker compose -f $(dirname "$0")/docker-compose.prod.yml --env-file $(dirname "$0")/.env"
mkdir -p "$DIR"
FILE="$DIR/ristomanager-$(date +%Y%m%d-%H%M%S).sql.gz"
$COMPOSE exec -T db pg_dump -U ristomanager -d ristomanager --no-owner | gzip > "$FILE"
# A truncated dump is worse than none: refuse to keep one that is suspiciously small.
[ "$(wc -c < "$FILE")" -gt 1000 ] || { rm -f "$FILE"; echo "backup looks empty, removed" >&2; exit 1; }
ls -1t "$DIR"/ristomanager-*.sql.gz | tail -n +15 | xargs -r rm -f
echo "wrote $FILE"
