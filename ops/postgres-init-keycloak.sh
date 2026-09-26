#!/bin/sh
set -eu

if [ -z "${KEYCLOAK_DB_PASSWORD:-}" ]; then
  echo "KEYCLOAK_DB_PASSWORD is required" >&2
  exit 1
fi

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE keycloak_app LOGIN PASSWORD %L', :'keycloak_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak_app')
\gexec

SELECT format('ALTER ROLE keycloak_app WITH LOGIN PASSWORD %L', :'keycloak_password')
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak_app')
\gexec

SELECT 'CREATE DATABASE keycloak OWNER keycloak_app'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
SQL
