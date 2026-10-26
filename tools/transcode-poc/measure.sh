#!/usr/bin/env bash
# PoC chuyển mã H.265 -> H.264 theo yêu cầu (TS-03.5).
# Mở K phiên đọc đồng thời luồng <path>_h264 (MediaMTX chạy FFmpeg chuyển mã qua runOnDemand),
# lấy mẫu CPU/RAM của container mediamtx mỗi 5 giây, ghi ra CSV để đưa vào báo cáo PoC.
#
# Điều kiện: stack đang chạy và bộ giả lập phát H.265:  SIM_CODEC=h265 ./deploy/scripts/dev.sh sim 10
# Cách dùng:   ./tools/transcode-poc/measure.sh [K=1..10] [giây=60]
set -euo pipefail
export MSYS_NO_PATHCONV=1

K="${1:-4}"
DURATION="${2:-60}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT/docs/poc/results"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/transcode-k${K}-$(date +%Y%m%d-%H%M%S).csv"
cd "$ROOT/deploy"  # đường dẫn tương đối: docker.exe trên Windows không hiểu đường dẫn /c/... của Git Bash
DC=(docker compose -f docker-compose.yml)
MTX="$("${DC[@]}" ps -q mediamtx)"

echo "Đo chuyển mã: $K phiên đồng thời trong ${DURATION}s -> $OUT"

# Mỗi phiên đọc là một FFmpeg trong container rtsp-sim (đã có FFmpeg và secret nội bộ), bỏ dữ liệu (-f null)
for i in $(seq 1 "$K"); do
  "${DC[@]}" exec -T -d rtsp-sim sh -c \
    "ffmpeg -hide_banner -loglevel error -rtsp_transport tcp -t $DURATION \
     -i rtsp://camhub-internal:\$(cat /run/secrets/media_internal_secret)@mediamtx:8554/sim-${i}_h264 -f null -"
done

echo "ts,sessions,cpu_percent,mem_usage" > "$OUT"
end=$((SECONDS + DURATION))
while (( SECONDS < end )); do
  read -r cpu mem < <(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$MTX" | sed 's/%//; s# / .*##')
  echo "$(date +%H:%M:%S),$K,$cpu,$mem" | tee -a "$OUT"
  sleep 5
done

avg="$(awk -F, 'NR>2 {s+=$3; n++} END {if (n) printf "%.1f", s/n}' "$OUT")"
echo "CPU trung bình của mediamtx với $K phiên chuyển mã: ${avg}% (100% = 1 lõi), bỏ mẫu đầu lúc FFmpeg khởi động"
