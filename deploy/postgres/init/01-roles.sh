#!/bin/sh
# Chạy một lần khi khởi tạo cụm PostgreSQL mới (docker-entrypoint-initdb.d).
# camhub     – owner của schema, Flyway chạy migration bằng tài khoản này
# camhub_app – tài khoản ứng dụng: không phải owner, không BYPASSRLS, nên luôn chịu RLS (INV-01)
set -eu
APP_PASSWORD=$(cat /run/secrets/db_app_password)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v app_pw="$APP_PASSWORD" <<'SQL'
create role camhub_app login nosuperuser nobypassrls password :'app_pw';
grant connect on database camhub to camhub_app;
SQL
