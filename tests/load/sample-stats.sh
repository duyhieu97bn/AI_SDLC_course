#!/usr/bin/env bash
# Ghi CPU/RAM các container chính mỗi 30 giây trong lúc chạy tải (TS-08.2).
#   ./tests/load/sample-stats.sh 65 > tests/load/build/stats.csv     # 65 phút
set -euo pipefail
MINUTES="${1:-65}"
echo "time,container,cpu_percent,mem_mib"
end=$(( $(date +%s) + MINUTES * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  now=$(date -u +%H:%M:%S)
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
    | grep -E '^camhub-(core|postgres|redis|rabbitmq|nginx|device-gateway|mediamtx|media-worker)-1,' \
    | awk -F, -v t="$now" '{
        cpu=$2; sub(/%/,"",cpu)
        split($3, m, " / "); mem=m[1]
        if (mem ~ /GiB/) { sub(/GiB/,"",mem); mem=mem*1024 } else if (mem ~ /MiB/) { sub(/MiB/,"",mem) } else if (mem ~ /KiB/) { sub(/KiB/,"",mem); mem=mem/1024 }
        sub(/^camhub-/,"",$1); sub(/-1$/,"",$1)
        printf "%s,%s,%s,%.0f\n", t, $1, cpu, mem }'
  sleep 30
done
