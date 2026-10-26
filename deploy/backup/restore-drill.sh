#!/usr/bin/env bash
# Diễn tập khôi phục CamHub (US-40.5, TS-08.3) trên môi trường Docker Compose đang chạy.
# KHÔNG đụng tới CSDL đang chạy (volume pgdata) hay MinIO đang chạy: khôi phục vào container và volume tạm.
#
#   ./backup/restore-drill.sh            chạy từ thư mục deploy/, in kết quả và thời gian từng bước
#   KEEP=1 ./backup/restore-drill.sh     giữ lại container/volume tạm để kiểm tra thêm (tự dọn ở lần chạy sau)
#
# Các bước:
#   1. Chụp trạng thái CSDL thật: khóa SHARE mọi bảng trong tích tắc, đếm dòng + md5 nội dung từng bảng,
#      lấy mốc thời gian T (không giao dịch nào commit được trong lúc khóa nên T khớp chính xác ảnh chụp)
#   2. Đẩy WAL chứa T vào kho (pgbackrest check)
#   3. PITR: pgbackrest restore --type=time --target=T vào volume tạm, khởi động PostgreSQL tạm, chờ promote
#   4. So sánh số dòng + md5 từng bảng giữa ảnh chụp và bản khôi phục
#   5. MinIO: dựng MinIO tạm, chép ngược bản sao (volume miniobackup) vào, đếm đối tượng
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash trên Windows: không đổi /var/... thành đường dẫn Windows

P=${COMPOSE_PROJECT_NAME:-camhub}
LIVE_PG=$P-postgres-1
BACKUP=$P-backup-1
IMAGE=camhub/postgres:dev
MINIO_IMAGE=bitnamilegacy/minio:2025.7.23
DRILL_VOL=${P}_drill_pgdata
DRILL_PG=$P-drill-pg
DRILL_MINIO=$P-drill-minio
DRILL_NET=${P}_drill
REPO_VOL=${P}_pgbackrest
MIRROR_VOL=${P}_miniobackup
WORK=$(mktemp -d "${TMPDIR:-/tmp}/camhub-drill.XXXXXX")

log() { printf '\033[36m[drill]\033[0m %s\n' "$*"; }
ms() { date +%s%3N; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", (b - a) / 1000 }'; }

cleanup() {
  docker rm -f "$DRILL_PG" "$DRILL_MINIO" >/dev/null 2>&1 || true
  docker volume rm "$DRILL_VOL" >/dev/null 2>&1 || true
  docker network rm "$DRILL_NET" >/dev/null 2>&1 || true
}

# Đếm dòng + md5 nội dung mọi bảng schema public; $1: container
TABLE_STATS_SQL="select format('%I.%I', schemaname, tablename),
  (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, tablename), false, true, '')))[1]::text,
  (xpath('/row/m/text()', query_to_xml(format('select md5(coalesce(string_agg(x::text, ''|'' order by x::text), '''')) as m from %I.%I x', schemaname, tablename), false, true, '')))[1]::text
from pg_tables where schemaname = 'public' order by 1;"

cleanup
trap '[ "${KEEP:-0}" = 1 ] || cleanup; rm -rf "$WORK"' EXIT

docker inspect "$LIVE_PG" "$BACKUP" >/dev/null || { echo "Cần stack đang chạy (dev.sh up)" >&2; exit 1; }

# ---------------------------------------------------------------- 1. Ảnh chụp CSDL thật
log "1. Chụp trạng thái CSDL thật (khóa SHARE các bảng trong vài mili giây)"
LOCK_SQL=$(docker exec "$LIVE_PG" psql -U camhub -d camhub -XAtc \
  "select 'lock table ' || string_agg(format('%I.%I', schemaname, tablename), ', ') || ' in share mode;' from pg_tables where schemaname = 'public'")
docker exec -i "$LIVE_PG" psql -U camhub -d camhub -XqAt -F $'\t' -v ON_ERROR_STOP=1 > "$WORK/live.tsv" <<SQL
begin;
set local lock_timeout = '10s';
$LOCK_SQL
$TABLE_STATS_SQL
select 'T', to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00', '';
commit;
SQL
TARGET=$(awk -F'\t' '$1 == "T" { print $2 }' "$WORK/live.tsv")
sed -i '/^T\t/d' "$WORK/live.tsv"
TABLES=$(wc -l < "$WORK/live.tsv" | tr -d ' ')
ROWS=$(awk -F'\t' '{ s += $2 } END { print s }' "$WORK/live.tsv")
log "   Mốc khôi phục T = $TARGET; $TABLES bảng, tổng $ROWS dòng (tính cả bảng cha phân vùng)"

# Có ít nhất một commit sau T để recovery dừng đúng mốc, rồi đóng đoạn WAL và đẩy vào kho
docker exec "$LIVE_PG" psql -U camhub -d camhub -XAtc "select pg_current_xact_id()" >/dev/null
docker exec "$BACKUP" camhub-backup check > "$WORK/check.log" 2>&1 || { cat "$WORK/check.log"; exit 1; }
log "2. WAL chứa T đã vào kho (pgbackrest check đạt)"
LAST_BACKUP=$(docker exec "$BACKUP" su-exec postgres pgbackrest --stanza=camhub --output=json info \
  | docker exec -i "$BACKUP" jq -r '.[0].backup[-1] | "\(.label) (\(.type))"')

# ---------------------------------------------------------------- 3. PITR vào volume tạm
log "3. Khôi phục PITR vào volume tạm $DRILL_VOL (bản gần nhất: $LAST_BACKUP)"
t_start=$(ms)
docker volume create "$DRILL_VOL" >/dev/null
docker run --rm -u postgres -v "$REPO_VOL:/var/lib/pgbackrest:ro" -v "$DRILL_VOL:/var/lib/postgresql/data" "$IMAGE" \
  pgbackrest --stanza=camhub --type=time "--target=$TARGET" --target-action=promote restore > "$WORK/restore.log" 2>&1 \
  || { cat "$WORK/restore.log"; exit 1; }
t_restored=$(ms)
log "   pgbackrest restore xong sau $(secs "$t_start" "$t_restored")s"

# archive_mode=off: bản khôi phục không được đẩy WAL (timeline mới) ngược vào kho thật; kho gắn read-only
docker run -d --name "$DRILL_PG" --network none \
  -v "$REPO_VOL:/var/lib/pgbackrest:ro" -v "$DRILL_VOL:/var/lib/postgresql/data" \
  "$IMAGE" postgres -c archive_mode=off >/dev/null
for _ in $(seq 1 300); do
  state=$(docker exec "$DRILL_PG" psql -U camhub -d camhub -XAtc "select pg_is_in_recovery()" 2>/dev/null || true)
  [ "$state" = f ] && break
  sleep 1
done
[ "$state" = f ] || { docker logs "$DRILL_PG" | tail -30; echo "PostgreSQL tạm chưa promote sau 300 giây" >&2; exit 1; }
t_up=$(ms)
log "   PostgreSQL tạm đã replay WAL tới T và promote sau $(secs "$t_restored" "$t_up")s"
docker logs "$DRILL_PG" 2>&1 | grep -E 'recovery stopping|redo done|selected new timeline|database system is ready' > "$WORK/recovery.log" || true

# ---------------------------------------------------------------- 4. So sánh
docker exec -i "$DRILL_PG" psql -U camhub -d camhub -XqAt -F $'\t' -v ON_ERROR_STOP=1 > "$WORK/drill.tsv" <<SQL
$TABLE_STATS_SQL
SQL
t_verified=$(ms)
if diff "$WORK/live.tsv" "$WORK/drill.tsv" > "$WORK/diff.txt"; then
  PG_RESULT="ĐẠT – $TABLES/$TABLES bảng khớp số dòng và md5 nội dung"
else
  PG_RESULT="LỖI – lệch dữ liệu:"$'\n'"$(cat "$WORK/diff.txt")"
fi
log "4. So sánh: $PG_RESULT"

# ---------------------------------------------------------------- 5. MinIO
log "5. Khôi phục MinIO từ bản sao vào MinIO tạm"
t_m0=$(ms)
docker network create "$DRILL_NET" >/dev/null
DRILL_MINIO_PW=$(openssl rand -hex 16 | tr -d '\r\n')
docker run -d --name "$DRILL_MINIO" --network "$DRILL_NET" --network-alias drill-minio \
  -e MINIO_ROOT_USER=drill -e MINIO_ROOT_PASSWORD="$DRILL_MINIO_PW" "$MINIO_IMAGE" >/dev/null
for _ in $(seq 1 120); do
  docker exec "$DRILL_MINIO" curl -fs http://localhost:9000/minio/health/live >/dev/null 2>&1 && break
  sleep 1
done
docker run --rm --network "$DRILL_NET" -v "$MIRROR_VOL:/miniobackup:ro" \
  -e MC_CONFIG_DIR=/tmp/.mc -e "MC_HOST_dst=http://drill:$DRILL_MINIO_PW@drill-minio:9000" \
  --entrypoint sh "$IMAGE" -c '
    set -e
    for d in /miniobackup/*/; do
      [ -d "$d" ] || continue
      b=$(basename "$d")
      mc mb -q --ignore-existing "dst/$b" >/dev/null
      mc mirror -q --overwrite "$d" "dst/$b" >/dev/null
    done
    echo "$(find /miniobackup -type f | wc -l) $(mc ls -r --json dst | grep -c "\"type\":\"file\"")"
  ' > "$WORK/minio.txt"
t_m1=$(ms)
read -r MIRROR_OBJ RESTORED_OBJ < "$WORK/minio.txt"
if [ "$MIRROR_OBJ" = "$RESTORED_OBJ" ]; then MINIO_RESULT="ĐẠT – $RESTORED_OBJ/$MIRROR_OBJ đối tượng"
else MINIO_RESULT="LỖI – bản sao có $MIRROR_OBJ, khôi phục được $RESTORED_OBJ"; fi
log "   $MINIO_RESULT sau $(secs "$t_m0" "$t_m1")s"

# ---------------------------------------------------------------- Kết quả
REPO_SIZE=$(docker exec "$BACKUP" du -sh /var/lib/pgbackrest | cut -f1)
DB_SIZE=$(docker exec "$DRILL_PG" psql -U camhub -d camhub -XAtc "select pg_size_pretty(pg_database_size('camhub'))")
cat <<EOF

================ KẾT QUẢ DIỄN TẬP ================
Thời điểm chạy (UTC)          : $(date -u +%Y-%m-%dT%H:%M:%SZ)
Mốc PITR (T)                   : $TARGET
Bản sao lưu nền                : $LAST_BACKUP
Kích thước CSDL / kho pgBackRest: $DB_SIZE / $REPO_SIZE
pgbackrest restore             : $(secs "$t_start" "$t_restored") s
Replay WAL + promote           : $(secs "$t_restored" "$t_up") s
Đối chiếu dữ liệu              : $(secs "$t_up" "$t_verified") s
Tổng PostgreSQL                : $(secs "$t_start" "$t_verified") s
Khôi phục MinIO                : $(secs "$t_m0" "$t_m1") s
PostgreSQL                     : $PG_RESULT
MinIO                          : $MINIO_RESULT
Log recovery:
$(sed 's/^/  /' "$WORK/recovery.log")
==================================================
EOF
[[ "$PG_RESULT" == ĐẠT* && "$MINIO_RESULT" == ĐẠT* ]]
