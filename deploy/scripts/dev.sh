#!/usr/bin/env bash
# Quản lý môi trường dev/staging CamHub (TS-02.4).
#   ./scripts/dev.sh init        sinh bí mật, chứng chỉ TLS tự ký, .env (chỉ tạo cái còn thiếu)
#   ./scripts/dev.sh up          init + build + khởi động, chờ mọi dịch vụ healthy
#   ./scripts/dev.sh sim [N]     chạy bộ giả lập RTSP với N luồng (mặc định theo .env)
#   ./scripts/dev.sh site <mã>   chạy địa điểm từ xa giả lập (profile site) với mã kích hoạt Site Connector
#   ./scripts/dev.sh smoke       chạy smoke test
#   ./scripts/dev.sh ps | logs [dịch vụ]
#   ./scripts/dev.sh down        dừng, giữ dữ liệu
#   ./scripts/dev.sh reset [-y]  dừng và XÓA toàn bộ dữ liệu (volume)
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash trên Windows: không đổi "/CN=..." thành đường dẫn

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DEPLOY_DIR"
SECRETS=secrets
COMPOSE=(docker compose -f docker-compose.yml)

log() { printf '\033[36m[camhub]\033[0m %s\n' "$*"; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "Thiếu lệnh '$1'" >&2; exit 1; }; }

# Ghi bí mật ngẫu nhiên nếu file chưa có. $2: hex (mật khẩu, an toàn trong URL) | b64 (khóa 32 byte)
secret() {
  local file="$SECRETS/$1" kind="$2"
  [[ -s "$file" ]] && return 0
  # tr -d '\r\n': openssl bản Windows kết thúc dòng bằng CRLF
  if [[ "$kind" == b64 ]]; then openssl rand -base64 32 | tr -d '\r\n' > "$file"
  else openssl rand -hex 24 | tr -d '\r\n' > "$file"; fi
  chmod 600 "$file" 2>/dev/null || true
  log "Đã tạo bí mật $1"
}

# Định nghĩa tài khoản RabbitMQ với hash SHA-256 có salt (định dạng rabbit_password_hashing_sha256)
rabbitmq_users() {
  local out="$SECRETS/rabbitmq-users.json"
  [[ -s "$out" ]] && return 0
  # Thư mục tạm tương đối: openssl bản Windows không hiểu đường dẫn /tmp của Git Bash
  local tmp; tmp="$(mktemp -d "$SECRETS/.tmp.XXXXXX")"
  openssl rand -out "$tmp/salt" 4
  { cat "$tmp/salt"; cat "$SECRETS/rabbitmq_password"; } > "$tmp/pre"
  local hash; hash="$({ cat "$tmp/salt"; openssl dgst -sha256 -binary "$tmp/pre"; } | openssl base64 -A | tr -d '\r\n')"
  rm -rf "$tmp"
  cat > "$out" <<JSON
{
  "users": [{ "name": "camhub", "password_hash": "$hash", "hashing_algorithm": "rabbit_password_hashing_sha256", "tags": ["administrator"] }],
  "vhosts": [{ "name": "camhub" }],
  "permissions": [{ "user": "camhub", "vhost": "camhub", "configure": ".*", "write": ".*", "read": ".*" }]
}
JSON
  log "Đã tạo tài khoản RabbitMQ"
}

tls_cert() {
  mkdir -p nginx/certs
  [[ -s nginx/certs/camhub.crt ]] && return 0
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 -sha256 \
    -keyout nginx/certs/camhub.key -out nginx/certs/camhub.crt \
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  log "Đã tạo chứng chỉ TLS tự ký (nginx/certs) – trình duyệt sẽ cảnh báo, chấp nhận để tiếp tục"
}

cmd_init() {
  need openssl
  mkdir -p "$SECRETS"
  secret camhub_master_key b64
  secret media_token_secret b64
  secret db_owner_password hex
  secret db_app_password hex
  secret rabbitmq_password hex
  secret media_internal_secret hex
  secret minio_root_password hex
  secret grafana_admin_password hex
  secret jwt_secret b64
  secret bootstrap_admin_password hex
  secret gateway_internal_token hex
  # Thư mục LDAP dev (profile sim, US-44): mật khẩu quản trị và tài khoản dịch vụ cn=svc-camhub
  secret ldap_admin_password hex
  secret ldap_bind_password hex
  [[ -s "$SECRETS/gateway_rabbitmq_url" ]] ||     printf 'amqp://camhub:%s@rabbitmq:5672/camhub' "$(cat "$SECRETS/rabbitmq_password")" > "$SECRETS/gateway_rabbitmq_url"
  rabbitmq_users
  tls_cert
  [[ -f .env ]] || { cp .env.example .env; log "Đã tạo .env từ .env.example"; }
}

cmd_up() {
  need docker
  cmd_init
  log "Build và khởi động (lần đầu mất vài phút để tải thư viện)…"
  # Môi trường dev mặc định có camera giả lập (profile sim); đặt CAMHUB_SIM=0 để tắt
  local profiles=()
  [[ "${CAMHUB_SIM:-1}" != 0 ]] && profiles=(--profile sim)
  "${COMPOSE[@]}" "${profiles[@]}" up -d --build --wait --wait-timeout 300
  log "Sẵn sàng: https://localhost:${CAMHUB_HTTPS_PORT:-8443}"
  log "  Đăng nhập: ${CAMHUB_ADMIN_EMAIL:-admin@camhub.local} / mật khẩu trong secrets/bootstrap_admin_password"
  log "  Grafana: http://localhost:3000 (admin / secrets/grafana_admin_password)"
}

cmd_sim() {
  local n="${1:-}"
  if [[ -n "$n" ]]; then export SIM_STREAMS="$n"; fi
  "${COMPOSE[@]}" --profile sim up -d --force-recreate camera-sim rtsp-sim
  log "Bộ giả lập đang phát ${SIM_STREAMS:-theo .env} luồng sim-1..sim-N"
}

# Địa điểm từ xa giả lập (ADR-10, TS-06.4): camera + ONVIF trong site-lan, Site Connector chỉ ra Nginx:443
cmd_site() {
  local code="${1:-${CAMHUB_SITE_CODE:-}}"
  "${COMPOSE[@]}" --profile site up -d --build site-camera site-onvif
  if [[ -z "$code" ]]; then
    log "Đã chạy camera địa điểm. Tạo bộ kết nối trên giao diện rồi chạy: ./scripts/dev.sh site <mã kích hoạt>"
    return 0
  fi
  CAMHUB_SITE_CODE="$code" "${COMPOSE[@]}" --profile site up -d --build site-connector
  log "Site Connector đang chạy; xem log: ./scripts/dev.sh logs site-connector"
}

cmd_reset() {
  if [[ "${1:-}" != "-y" ]]; then
    read -r -p "Xóa TOÀN BỘ dữ liệu dev (DB, video ghi, MinIO, log)? Gõ 'xoa' để xác nhận: " ans
    [[ "$ans" == "xoa" ]] || { echo "Đã hủy"; exit 1; }
  fi
  "${COMPOSE[@]}" --profile sim --profile site down -v --remove-orphans
}

case "${1:-help}" in
  init)  cmd_init ;;
  up)    cmd_up ;;
  sim)   shift; cmd_sim "$@" ;;
  site)  shift; cmd_site "$@" ;;
  smoke) bash "$DEPLOY_DIR/../tests/smoke/smoke.sh" ;;
  ps)    "${COMPOSE[@]}" ps ;;
  logs)  shift; "${COMPOSE[@]}" logs -f --tail 200 "$@" ;;
  down)  "${COMPOSE[@]}" --profile sim --profile site down ;;
  reset) shift; cmd_reset "$@" ;;
  *)     sed -n '2,11p' "$0" ;;
esac
