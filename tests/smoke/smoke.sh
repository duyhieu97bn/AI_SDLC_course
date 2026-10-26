#!/usr/bin/env bash
# Smoke test sau khi dựng môi trường (TS-02.5) – tiêu chí "Khởi động môi trường".
# Kiểm tra: mọi container healthy, SPA và API qua Nginx, /internal bị chặn từ ngoài,
# MediaMTX hỏi Core khi xem luồng (từ chối khi thiếu token), RabbitMQ có exchange, Prometheus thấy target.
set -uo pipefail
export MSYS_NO_PATHCONV=1

BASE="${CAMHUB_BASE_URL:-https://localhost:8443}"
cd "$(dirname "${BASH_SOURCE[0]}")/../../deploy"
DC=(docker compose -f docker-compose.yml)
pass=0; fail=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then printf '  \033[32mĐẠT\033[0m  %s\n' "$name"; pass=$((pass + 1))
  else printf '  \033[31mLỖI\033[0m  %s\n' "$name"; fail=$((fail + 1)); fi
}

http_code() { curl -sk -o /dev/null -w '%{http_code}' "$@"; }
expect_code() { local want="$1"; shift; [[ "$(http_code "$@")" == "$want" ]]; }

all_healthy() {
  local bad
  bad="$("${DC[@]}" ps --format '{{.Service}} {{.State}} {{.Health}}' | awk '$2!="running" || ($3!="" && $3!="healthy")')"
  [[ -z "$bad" ]] || { echo "$bad" >&2; return 1; }
}

spa_served()       { curl -sk "$BASE/" | grep -q '<div id="app">'; }
core_ready()       { "${DC[@]}" exec -T core wget -q -O /dev/null http://localhost:8080/actuator/health/readiness; }
poc_token()        { curl -sk -X POST -H 'Content-Type: application/json' -d '{"path":"smoke-1"}' "$BASE/api/v1/poc/media-token" | grep -q '"token"'; }
rabbit_has_jobs()  { "${DC[@]}" exec -T rabbitmq rabbitmqctl -q list_exchanges -p camhub name | grep -qx jobs; }
flyway_migrated()  { "${DC[@]}" exec -T postgres psql -U camhub -d camhub -tAc "select count(*) from flyway_schema_history where success" | grep -qv '^0$'; }
# MediaMTX trả 400 (không phải 401) khi Core từ chối phiên WHEP, nên kiểm tra cả mã lẫn log từ chối
whep_denied() {
  local code; code="$(http_code -X POST -H 'Content-Type: application/sdp' --data 'v=0' "$BASE/media/webrtc/smoke-deny/whep")"
  [[ "$code" != 201 ]] && "${DC[@]}" logs --since 1m mediamtx 2>&1 | grep -q 'authentication failed: server replied with code 401'
}
prom_sees_core()   { curl -s 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22core%22%7D' | grep -q '"value":\[[^]]*,"1"\]'; }

echo "Smoke test CamHub – $BASE"
check "Mọi container đang chạy và healthy"            all_healthy
check "Nginx trả SPA (index.html)"                     spa_served
check "SPA fallback cho đường dẫn của Vue Router"      expect_code 200 "$BASE/poc/player"
check "Core healthy (readiness qua mạng nội bộ)"       core_ready
check "Nginx chặn /internal từ bên ngoài"              expect_code 404 -X POST "$BASE/internal/media/authorize"
check "Nginx chặn /actuator từ bên ngoài"              expect_code 404 "$BASE/actuator/health"
check "API PoC cấp token xem qua /api"                 poc_token
check "MediaMTX hỏi Core và từ chối WHEP thiếu token"  whep_denied
check "RabbitMQ có exchange jobs"                      rabbit_has_jobs
check "PostgreSQL đã chạy migration nền"               flyway_migrated
check "Prometheus thu được metric Core"                prom_sees_core
check "Grafana đang chạy"                              expect_code 200 http://localhost:3000/api/health

echo "Kết quả: $pass đạt, $fail lỗi"
[[ $fail -eq 0 ]]
