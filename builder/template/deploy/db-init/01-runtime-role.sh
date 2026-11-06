#!/bin/sh
# Runs once, when the database is first created. The engine migrates as the owner and serves as this plain role, so row-level
# security applies to everything it does.
set -e
psql -v ON_ERROR_STOP=1 -v password="$COMMERCE_DB_PASSWORD" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
create role commerce_app login password :'password' nosuperuser nocreaterole nobypassrls;
grant connect on database commerce to commerce_app;
SQL
