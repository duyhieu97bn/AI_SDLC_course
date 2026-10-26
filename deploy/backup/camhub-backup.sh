#!/bin/sh
# Sao lưu CamHub (US-40.1, SADD 3.12.3) – chạy trong container "backup" (image camhub/postgres).
#   camhub-backup daemon             (root, entrypoint) chuẩn bị thư mục, sinh crontab, chạy init rồi crond
#   camhub-backup init               chờ PostgreSQL, stanza-create, check, bản đầy đủ đầu tiên nếu chưa có, sao chép MinIO
#   camhub-backup pg full|diff|incr  một lần sao lưu PostgreSQL bằng pgBackRest (có khóa, tránh chạy chồng)
#   camhub-backup minio              sao chép (mc mirror) mọi bucket MinIO sang $MINIO_MIRROR_DIR
#   camhub-backup status             ghi lại $BACKUP_STATUS_DIR/status.json
#   camhub-backup check              pgbackrest check (kiểm tra WAL archive hoạt động)
#
# Tệp trạng thái (Core đọc cho GET /system/backups): $BACKUP_STATUS_DIR/status.json, ghi nguyên tử
# (tệp tạm + mv) sau mỗi lần chạy và khi khởi động. Định dạng xem docs/runbook/restore.md mục 7.
# Lệnh chạy bằng root (docker compose exec backup camhub-backup ...) tự chuyển sang user postgres.
set -eu

STANZA=${PGBACKREST_STANZA:-camhub}
STATUS_DIR=${BACKUP_STATUS_DIR:-/backup-status}
STATE_DIR=$STATUS_DIR/.state
REPO_DIR=/var/lib/pgbackrest
PG_SOCKET=/var/run/postgresql
PG_SUPERUSER=${BACKUP_PG_USER:-camhub}
MIRROR_DIR=${MINIO_MIRROR_DIR:-/miniobackup}
MINIO_ENDPOINT=${MINIO_ENDPOINT:-minio:9000}
MINIO_USER=${MINIO_USER:-camhub}
MINIO_PASSWORD_FILE=${MINIO_PASSWORD_FILE:-/run/secrets/minio_root_password}
MINIO_PW_COPY=/run/camhub/minio_pw
SELF=/usr/local/bin/camhub-backup

log() { printf '%s [backup] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# Chờ khóa trên fd $1 tối đa $2 giây (flock của BusyBox không có -w)
wait_lock() {
  waited=0
  until flock -n "$1"; do
    [ "$waited" -lt "$2" ] || return 1
    sleep 10; waited=$((waited + 10))
  done
}

# Lưu kết quả lần chạy gần nhất của một job: $1 tên (pg|minio), các tham số còn lại là cặp --arg/--argjson cho jq
save_state() {
  name=$1; shift
  jq -n "$@" '$ARGS.named' > "$STATE_DIR/$name.json.tmp" && mv "$STATE_DIR/$name.json.tmp" "$STATE_DIR/$name.json"
}

# ---------------------------------------------------------------- status.json
write_status() {
  mkdir -p "$STATE_DIR"
  info=$(pgbackrest --stanza="$STANZA" --output=json info 2>/dev/null) || info='[]'
  [ -n "$info" ] || info='[]'
  # Thời điểm WAL được archive thành công / thất bại gần nhất (pg_stat_archiver)
  arch=$(psql -h "$PG_SOCKET" -U "$PG_SUPERUSER" -d postgres -XAtc \
    "select coalesce(to_char(last_archived_time at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'')
       || '|' || coalesce(to_char(last_failed_time at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'')
     from pg_stat_archiver" 2>/dev/null) || arch='|'
  repo_kb=$(du -sk "$REPO_DIR" 2>/dev/null | cut -f1) || repo_kb=''
  pg_state=$(cat "$STATE_DIR/pg.json" 2>/dev/null) || pg_state='null'
  minio_state=$(cat "$STATE_DIR/minio.json" 2>/dev/null) || minio_state='null'

  # Tên tệp tạm riêng cho mỗi tiến trình: job minio và status có thể cùng chạy lúc hh:30
  tmp="$STATUS_DIR/.status.json.$$.tmp"
  jq -n \
    --argjson info "$info" \
    --argjson pgState "$pg_state" \
    --argjson minioState "$minio_state" \
    --arg stanza "$STANZA" \
    --arg arch "$arch" \
    --arg repoKb "$repo_kb" \
    --argjson staleHours "${BACKUP_STALE_HOURS:-2}" \
    --argjson mirrorStaleHours "${MINIO_MIRROR_STALE_HOURS:-3}" '
    def iso: if . == null then null else (. | floor | todate) end;
    def nz: if . == "" then null else . end;
    ($info[0] // {}) as $s
    | ([$s.backup[]?] | sort_by(.timestamp.stop) | reverse) as $b
    | ($b[0] // null) as $last
    | ($arch | split("|")) as $a
    | ($a[0] | nz) as $walOk
    | ($a[1] | nz) as $walFail
    | ([
        (if $pgState != null and $pgState.result == "ERROR" then "Lần sao lưu \($pgState.type) lúc \($pgState.at) lỗi: \($pgState.message)" else empty end),
        (if ($s | length) == 0 then "Chưa đọc được thông tin kho pgBackRest (stanza chưa tạo hoặc kho không truy cập được)"
         elif $s.status.code != 0 then "pgBackRest: \($s.status.message)" else empty end),
        (if $walFail != null and ($walOk == null or $walFail > $walOk) then "Đẩy WAL vào kho đang lỗi từ \($walFail)" else empty end),
        (if $last != null and (now - $last.timestamp.stop) > ($staleHours * 3600)
         then "Bản sao lưu gần nhất đã cũ hơn \($staleHours) giờ" else empty end)
      ]) as $errors
    | {
        generatedAt: (now | iso),
        postgres: {
          stanza: $stanza,
          status: (if ($errors | length) == 0 then "OK" else "ERROR" end),
          message: (if ($errors | length) == 0 then null else ($errors | join("; ")) end),
          lastBackupAt: ($last.timestamp.stop // null | iso),
          lastBackupType: ($last.type // null),
          backupCount: ($b | length),
          fullCount: ([$b[] | select(.type == "full")] | length),
          lastWalArchivedAt: $walOk,
          repoSizeBytes: (if $repoKb == "" then null else ($repoKb | tonumber * 1024) end),
          backups: [$b[:50][] | {
            label: .label,
            type: .type,
            startedAt: (.timestamp.start | iso),
            finishedAt: (.timestamp.stop | iso),
            sizeBytes: (.info.repository.delta // .info.repository.size // 0)
          }]
        },
        minio: (
          if $minioState == null then {status: "UNKNOWN", message: "Chưa chạy sao chép MinIO lần nào", lastMirrorAt: null, objects: null}
          else
            ($minioState.lastOkAt // null) as $ok
            | (if $minioState.result == "ERROR" then "Sao chép lúc \($minioState.at) lỗi: \($minioState.message)"
               elif $ok != null and (now - ($ok | fromdateiso8601)) > ($mirrorStaleHours * 3600)
               then "Lần sao chép thành công gần nhất đã cũ hơn \($mirrorStaleHours) giờ"
               else null end) as $msg
            | {status: (if $msg == null then "OK" else "ERROR" end), message: $msg,
               lastMirrorAt: $ok, objects: ($minioState.objects // null)}
          end)
      }' > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$STATUS_DIR/status.json"
}

# ---------------------------------------------------------------- PostgreSQL
wait_pg() {
  i=0
  until pg_isready -q -h "$PG_SOCKET" -U "$PG_SUPERUSER" -d postgres; do
    i=$((i + 1))
    [ "$i" -lt 150 ] || { log "PostgreSQL chưa sẵn sàng sau 300 giây"; return 1; }
    sleep 2
  done
}

# Có bản sao lưu nào kết thúc trong vòng $1 phút gần đây không
recent_backup() {
  pgbackrest --stanza="$STANZA" --output=json info 2>/dev/null \
    | jq -e --argjson m "$1" '[.[0].backup[]?.timestamp.stop] | max // 0 | (now - .) < ($m * 60)' >/dev/null
}

run_pg() {
  type=$1
  case "$type" in full|diff|incr) ;; *) echo "Kiểu sao lưu không hợp lệ: $type" >&2; exit 2 ;; esac
  mkdir -p "$STATE_DIR"
  exec 9>"$STATE_DIR/pg.lock"
  wait_lock 9 7200 || { log "Bỏ qua $type: job sao lưu khác đang chạy quá 2 giờ"; return 1; }
  # Bản diff/incr ngay sau một bản sao lưu khác (vd. 01:00 vừa chạy full) là thừa
  if [ "$type" != full ] && recent_backup "${BACKUP_MIN_GAP_MINUTES:-30}"; then
    log "Bỏ qua $type: đã có bản sao lưu trong ${BACKUP_MIN_GAP_MINUTES:-30} phút gần đây"
    write_status
    return 0
  fi
  log "Bắt đầu sao lưu PostgreSQL ($type)"
  start=$(date +%s)
  out="$STATE_DIR/pg-last.log"
  if pgbackrest --stanza="$STANZA" --type="$type" backup > "$out" 2>&1; then
    secs=$(( $(date +%s) - start ))
    log "Sao lưu $type thành công sau ${secs}s"
    save_state pg --arg result OK --arg type "$type" --arg at "$(now_iso)" --argjson seconds "$secs" --argjson message null
    rc=0
  else
    rc=$?
    msg=$(grep -E 'ERROR|error' "$out" | tail -n 1 | cut -c1-400)
    [ -n "$msg" ] || msg="pgbackrest thoát với mã $rc"
    log "Sao lưu $type LỖI: $msg"
    save_state pg --arg result ERROR --arg type "$type" --arg at "$(now_iso)" --arg message "$msg"
  fi
  cat "$out"
  write_status
  return "$rc"
}

# ---------------------------------------------------------------- MinIO
run_minio() {
  mkdir -p "$STATE_DIR" "$MIRROR_DIR"
  exec 8>"$STATE_DIR/minio.lock"
  wait_lock 8 3600 || { log "Bỏ qua sao chép MinIO: lần trước chưa xong"; return 1; }
  prev_ok=$(jq -r '.lastOkAt // empty' "$STATE_DIR/minio.json" 2>/dev/null) || prev_ok=''
  pw_file=$MINIO_PW_COPY; [ -r "$pw_file" ] || pw_file=$MINIO_PASSWORD_FILE
  export MC_CONFIG_DIR=/tmp/.mc
  # Mật khẩu do dev.sh sinh ở dạng hex nên đặt thẳng vào URL được
  MC_HOST_src="http://$MINIO_USER:$(cat "$pw_file")@$MINIO_ENDPOINT"
  export MC_HOST_src
  remove=''
  [ "${MINIO_MIRROR_REMOVE:-0}" = 1 ] && remove='--remove'
  log "Bắt đầu sao chép MinIO -> $MIRROR_DIR"
  out="$STATE_DIR/minio-last.log"
  : > "$out"
  ok=1
  if buckets=$(mc ls --json src 2>>"$out" | jq -r 'select(.type == "folder") | .key | rtrimstr("/")'); then
    [ -n "$buckets" ] || { echo "Không thấy bucket nào trên MinIO" >> "$out"; ok=0; }
    for b in $buckets; do
      # shellcheck disable=SC2086
      mc mirror --overwrite --quiet $remove "src/$b" "$MIRROR_DIR/$b" >> "$out" 2>&1 || ok=0
    done
  else
    ok=0
  fi
  objects=$(find "$MIRROR_DIR" -type f | wc -l | tr -d ' ')
  if [ "$ok" = 1 ]; then
    log "Sao chép MinIO xong: $objects đối tượng"
    save_state minio --arg result OK --arg at "$(now_iso)" --arg lastOkAt "$(now_iso)" \
      --argjson objects "$objects" --argjson message null
    rc=0
  else
    msg=$(grep -v '^$' "$out" | tail -n 1 | cut -c1-400)
    log "Sao chép MinIO LỖI: $msg"
    if [ -n "$prev_ok" ]; then lastok=$(jq -n --arg v "$prev_ok" '$v'); else lastok=null; fi
    save_state minio --arg result ERROR --arg at "$(now_iso)" --argjson lastOkAt "$lastok" \
      --argjson objects "$objects" --arg message "${msg:-mc mirror thất bại}"
    rc=1
  fi
  cat "$out"
  write_status
  return "$rc"
}

# ---------------------------------------------------------------- khởi động
cmd_init() {
  mkdir -p "$STATE_DIR"
  write_status
  if wait_pg; then
    # stanza-create an toàn khi chạy lại: stanza đã có thì chỉ kiểm tra khớp với cụm
    pgbackrest --stanza="$STANZA" stanza-create || log "stanza-create lỗi"
    pgbackrest --stanza="$STANZA" check || log "pgbackrest check lỗi: WAL archive chưa hoạt động"
    if ! pgbackrest --stanza="$STANZA" --output=json info 2>/dev/null | jq -e '.[0].backup | length > 0' >/dev/null; then
      run_pg full || true
    fi
  fi
  run_minio || true
  write_status
}

cmd_daemon() {
  [ "$(id -u)" = 0 ] || { echo "daemon cần chạy bằng root" >&2; exit 1; }
  mkdir -p "$STATE_DIR" "$MIRROR_DIR" "$REPO_DIR" /run/camhub
  chown postgres:postgres "$STATUS_DIR" "$STATE_DIR" "$MIRROR_DIR" "$REPO_DIR"
  chmod 755 "$STATUS_DIR"
  # Bí mật Compose có thể chỉ root đọc được: chép một bản cho user postgres
  if [ -r "$MINIO_PASSWORD_FILE" ]; then
    install -o postgres -g postgres -m 400 "$MINIO_PASSWORD_FILE" "$MINIO_PW_COPY"
  fi
  run="$SELF"
  {
    echo "# Sinh bởi camhub-backup daemon từ biến môi trường – TZ=${TZ:-UTC}"
    echo "${BACKUP_FULL_CRON:-0 1 * * *} $run pg full >/proc/1/fd/1 2>&1"
    echo "${BACKUP_DIFF_CRON:-0 * * * *} $run pg ${BACKUP_HOURLY_TYPE:-diff} >/proc/1/fd/1 2>&1"
    echo "${MINIO_MIRROR_CRON:-30 * * * *} $run minio >/proc/1/fd/1 2>&1"
    echo "*/5 * * * * $run status >/proc/1/fd/1 2>&1"
  } > /etc/crontabs/root
  log "Lịch sao lưu:"; grep -v '^#' /etc/crontabs/root
  su-exec postgres "$SELF" init &
  # -d: ghi log của crond ra stderr (docker logs) thay vì syslog
  exec crond -f -d 8
}

# Mọi lệnh trừ daemon chạy bằng user postgres (chủ của kho pgBackRest)
cmd=${1:-help}
if [ "$cmd" != daemon ] && [ "$cmd" != help ] && [ "$(id -u)" = 0 ]; then
  exec su-exec postgres "$SELF" "$@"
fi

case "$cmd" in
  daemon) cmd_daemon ;;
  init)   cmd_init ;;
  pg)     run_pg "${2:-diff}" ;;
  minio)  run_minio ;;
  status) write_status ;;
  check)  pgbackrest --stanza="$STANZA" check ;;
  *)      sed -n '2,8p' "$0" ;;
esac
