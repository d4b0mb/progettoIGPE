#!/bin/sh
# Creates the restricted role the application connects as.
#
# Why a separate role: PostgreSQL superusers and table owners skip row-level
# security. Migrations run as the owner; the running API runs as this role,
# which is neither superuser nor owner, so the tenant policies always apply.
#
# Runs once, when the database volume is first created
# (docker-entrypoint-initdb.d). In production, create the same role by hand.
set -e

psql -v ON_ERROR_STOP=1 \
     -v app_password="${APP_DB_PASSWORD:-ristomanager_app}" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE ristomanager_app
    LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
GRANT CONNECT ON DATABASE ristomanager TO ristomanager_app;
GRANT USAGE ON SCHEMA public TO ristomanager_app;
SQL
