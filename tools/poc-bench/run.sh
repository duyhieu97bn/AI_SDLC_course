#!/usr/bin/env bash
# Chạy bench.mjs trong container Playwright gắn vào mạng backend của stack CamHub.
# Điều kiện: ./deploy/scripts/dev.sh up  và  ./deploy/scripts/dev.sh sim 20
# Ví dụ:     BENCH_COUNTS=1,4,9,16,20 BENCH_ROUNDS=3 ./tools/poc-bench/run.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT_DIR="$ROOT/docs/poc/results"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/first-frame-${BENCH_MODE:-webrtc}-$(date +%Y%m%d-%H%M%S).jsonl"

# Đường dẫn thư mục cho docker trên Windows (Git Bash) và Linux
mount_src="$HERE"
command -v cygpath >/dev/null 2>&1 && mount_src="$(cygpath -w "$HERE")"

docker run --rm --network camhub_backend --ipc=host \
  -e BENCH_URL=https://nginx \
  -e BENCH_COUNTS="${BENCH_COUNTS:-1,4,9,16,20}" \
  -e BENCH_ROUNDS="${BENCH_ROUNDS:-3}" \
  -e BENCH_MODE="${BENCH_MODE:-webrtc}" \
  -v "$mount_src:/bench:ro" -v camhub_bench_cache:/cache \
  -e npm_config_cache=/cache/npm -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
  -w /work mcr.microsoft.com/playwright:v1.50.1-noble \
  bash -c 'cp /bench/bench.mjs . && npm init -y >/dev/null && npm i --no-fund --no-audit playwright@1.50.1 >/dev/null \
           && (command -v google-chrome >/dev/null || npx playwright install --with-deps chrome >/dev/null) \
           && node bench.mjs' | tee "$OUT"

echo "Đã ghi $OUT"
