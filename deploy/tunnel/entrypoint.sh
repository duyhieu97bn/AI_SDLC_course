#!/bin/sh
# Tunnel Server (ADR-10): chạy chisel server ở chế độ --reverse với tệp xác thực do Core ghi.
#
# Core ghi /tunnel-auth/users.json (volume "tunnelauth", ở đây chỉ đọc) theo định dạng authfile của chisel:
#   {"<user>:<password>": ["^R:0\\.0\\.0\\.0:21001$", "^R:0\\.0\\.0\\.0:22001$", ...]}
# chisel so khớp regex với chuỗi "R:<địa chỉ nghe>:<cổng>" của từng remote ngược (KHÔNG có phần đích,
# cũng không có ":socks").
#
# Lưu ý an toàn: khi tệp xác thực RỖNG ({}), chisel tắt xác thực và nhận mọi client (đã kiểm chứng với 1.12.1,
# kể cả khi nạp lại lúc đang chạy). Vì vậy script không đưa thẳng tệp của Core cho chisel mà sinh tệp riêng
# /run/chisel/users.json = tệp của Core + một tài khoản "lính gác" mật khẩu ngẫu nhiên, không có quyền gì.
# Tệp của Core sai định dạng thì giữ nguyên bản đang dùng. chisel tự nạp lại tệp khi thay đổi; thay đổi chỉ áp
# dụng cho kết nối/đường hầm mới, đường hầm đang chạy không bị ngắt.
#
# Biến môi trường: TUNNEL_AUTHFILE (mặc định /tunnel-auth/users.json), TUNNEL_PORT (8080),
#   TUNNEL_SYNC_SECONDS (2), CHISEL_KEY_FILE (khóa SSH cố định để fingerprint không đổi; mặc định sinh mới mỗi lần chạy).
set -eu

SRC="${TUNNEL_AUTHFILE:-/tunnel-auth/users.json}"
DST=/run/chisel/users.json
PORT="${TUNNEL_PORT:-8080}"
INTERVAL="${TUNNEL_SYNC_SECONDS:-2}"

log() { printf '%s tunnel: %s\n' "$(date '+%Y/%m/%d %H:%M:%S')" "$*" >&2; }
rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

SENTINEL="camhub-sentinel-$(rand_hex 8):$(rand_hex 32)"
LAST_WARN=""

# In ra tệp xác thực đã gộp; trả mã khác 0 nếu tệp của Core sai định dạng
render() {
  if [ -s "$SRC" ]; then
    jq -ce --arg s "$SENTINEL" '
      if type == "object" and all(.[]; type == "array" and all(.[]; type == "string"))
      then . + {($s): []} else error("sai định dạng") end' "$SRC"
  else
    jq -cn --arg s "$SENTINEL" '{($s): []}'
  fi
}

sync_authfile() {
  if ! new="$(render 2>/dev/null)"; then
    if [ "$LAST_WARN" != invalid ]; then log "Tệp $SRC sai định dạng, giữ cấu hình xác thực đang dùng"; LAST_WARN=invalid; fi
    return 0
  fi
  LAST_WARN=""
  [ -f "$DST" ] && [ "$new" = "$(cat "$DST")" ] && return 0
  printf '%s\n' "$new" > "$DST.tmp"
  mv "$DST.tmp" "$DST"
  log "Đã nạp tệp xác thực: $(($(printf '%s' "$new" | jq 'length') - 1)) bộ kết nối"
}

sync_authfile
[ -s "$SRC" ] || log "Chưa có $SRC (Core chưa ghi): mọi client đều bị từ chối cho tới khi có"

chisel server --host 0.0.0.0 --port "$PORT" --reverse --authfile "$DST" &
PID=$!
trap 'kill -TERM "$PID" 2>/dev/null' TERM INT

while kill -0 "$PID" 2>/dev/null; do
  sleep "$INTERVAL" &
  wait $! || true
  sync_authfile
done
wait "$PID"
