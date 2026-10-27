#!/usr/bin/env bash
# Đo first frame trên trang Xem trực tiếp (NFR-01) bằng Chrome trong container gắn mạng backend.
# Ví dụ: FF_COUNTS=1,4,9,16 FF_ROUNDS=3 ./tests/load/first-frame.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
mount_src="$HERE"
command -v cygpath >/dev/null 2>&1 && mount_src="$(cygpath -w "$HERE")"

docker run --rm --network camhub_backend --ipc=host \
  -e FF_URL=https://nginx -e FF_PASSWORD="$(cat "$ROOT/deploy/secrets/bootstrap_admin_password")" \
  -e FF_COUNTS="${FF_COUNTS:-1,4,9,16}" -e FF_ROUNDS="${FF_ROUNDS:-3}" -e FF_DEBUG="${FF_DEBUG:-}" \
  -v "$mount_src:/load:ro" -v camhub_bench_cache:/cache \
  -e npm_config_cache=/cache/npm -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
  -w /work mcr.microsoft.com/playwright:v1.50.1-noble \
  bash -c 'cp /load/first-frame.mjs . && npm init -y >/dev/null && npm i --no-fund --no-audit playwright@1.50.1 >/dev/null \
           && (command -v google-chrome >/dev/null || npx playwright install --with-deps chrome >/dev/null) \
           && node first-frame.mjs'
