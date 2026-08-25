#!/usr/bin/env bash
set -Eeuo pipefail

# PostgreSQL의 POSTGRES_USER는 초기화와 Flyway 전용 관리자다. 애플리케이션이
# 이 슈퍼유저로 연결하면 FORCE RLS도 우회하므로 별도의 제한 역할을 만든다.
: "${CHECKON_APP_DB_USER:?CHECKON_APP_DB_USER is required}"
: "${CHECKON_APP_DB_PASSWORD:?CHECKON_APP_DB_PASSWORD is required}"

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=app_user="$CHECKON_APP_DB_USER" \
  --set=app_password="$CHECKON_APP_DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'app_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
    :'app_user',
    :'app_password'
)
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database_name', :'app_user')
\gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_user')
\gexec

-- Flyway가 이후 생성하는 테이블에도 런타임 최소 DML 권한이 자동 적용된다.
SELECT format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
    :'app_user'
)
\gexec
SQL
