#!/usr/bin/env bash
# Kiểm thử tải API bằng Gatling (TS-08.1) trong container JDK gắn vào mạng backend của stack.
# Ví dụ:  ./tests/load/run.sh                          # 100 người dùng, 60 phút (tiêu chí NFR-03)
#         ./tests/load/run.sh -Dusers=20 -Dminutes=5  # chạy thử ngắn
# Báo cáo HTML: tests/load/build/reports/gatling/<mô phỏng>-<thời điểm>/index.html
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ADMIN_PASSWORD="$(cat "$ROOT/deploy/secrets/bootstrap_admin_password")"
mount_src="$HERE"
command -v cygpath >/dev/null 2>&1 && mount_src="$(cygpath -w "$HERE")"

docker run --rm --network camhub_backend \
  -e CAMHUB_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -v "$mount_src:/load" -v camhub_load_gradle:/root/.gradle \
  -w /load eclipse-temurin:21-jdk \
  bash -c 'sed -i "s/\r$//" gradlew && ./gradlew --no-daemon -q gatlingRun --simulation camhub.load.ApiLoadSimulation '"$*"
