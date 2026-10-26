#!/usr/bin/env bash
# Chạy test e2e giao diện (Playwright + Chrome) trong container gắn vào mạng backend của stack dev.
# Điều kiện: ./deploy/scripts/dev.sh up  (profile Core có "demo" để có dữ liệu mẫu)
# Ví dụ:     ./tests/e2e/run.sh                 # mọi spec
#            ./tests/e2e/run.sh specs/s1.spec.js
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ADMIN_PASSWORD="$(cat "$ROOT/deploy/secrets/bootstrap_admin_password")"
LDAP_BIND_PASSWORD="$(cat "$ROOT/deploy/secrets/ldap_bind_password" 2>/dev/null || true)"
mount_src="$HERE"
command -v cygpath >/dev/null 2>&1 && mount_src="$(cygpath -w "$HERE")"

docker run --rm --network camhub_backend --ipc=host \
  -e E2E_BASE_URL=https://nginx -e E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" -e E2E_LDAP_BIND_PASSWORD="$LDAP_BIND_PASSWORD" \
  -v "$mount_src:/e2e" -v camhub_e2e_cache:/cache -e npm_config_cache=/cache/npm \
  -w /e2e mcr.microsoft.com/playwright:v1.50.1-noble \
  bash -c 'npm install --no-fund --no-audit --no-save >/dev/null \
           && (command -v google-chrome >/dev/null || npx playwright install --with-deps chrome >/dev/null) \
           && npx playwright test '"$*"
