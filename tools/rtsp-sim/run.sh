#!/bin/sh
# Bộ giả lập RTSP (TS-03.1): phát N luồng giống camera vào MediaMTX để đo tải khi chưa đủ camera thật.
#
# Cách làm: mã hóa MỘT file mẫu (720p, 25 fps, GOP 2 giây, ~2 Mbit/s, KHÔNG B-frame – giống cấu hình
# camera giám sát; WebRTC của MediaMTX từ chối H.264 có B-frame, xem báo cáo PoC),
# rồi mỗi luồng chỉ đọc lại file bằng "-c copy". Như vậy CPU của bộ giả lập rất thấp, tải đo được là tải
# của MediaMTX, không phải của FFmpeg mã hóa.
#
# Biến môi trường:
#   SIM_STREAMS  số luồng (mặc định 20)           SIM_CODEC  h264 | h265 (mặc định h264)
#   SIM_TARGET   rtsp://mediamtx:8554              SIM_USER   tài khoản nội bộ MediaMTX
#   SIM_PREFIX   tiền tố path (mặc định sim-)      SIM_SAMPLE_SECONDS độ dài file mẫu (mặc định 60)
# Mật khẩu đọc từ Docker secret /run/secrets/media_internal_secret.
set -eu

STREAMS="${SIM_STREAMS:-20}"
CODEC="${SIM_CODEC:-h264}"
TARGET="${SIM_TARGET:-rtsp://mediamtx:8554}"
USER_NAME="${SIM_USER:-camhub-internal}"
PREFIX="${SIM_PREFIX:-sim-}"
SECONDS_LEN="${SIM_SAMPLE_SECONDS:-60}"
SECRET="$(cat /run/secrets/media_internal_secret)"
SAMPLE="/samples/sample-${CODEC}-${SECONDS_LEN}s-nobf.mp4"

if [ "$CODEC" = "h265" ]; then
  ENC="-c:v libx265 -preset ultrafast -x265-params keyint=50:min-keyint=50:bframes=0:log-level=error"
else
  ENC="-c:v libx264 -preset veryfast -profile:v main -bf 0 -g 50 -keyint_min 50 -sc_threshold 0"
fi

if [ ! -s "$SAMPLE" ]; then
  echo "rtsp-sim: tạo file mẫu $SAMPLE"
  # shellcheck disable=SC2086
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=1280x720:rate=25:duration=${SECONDS_LEN}" \
    -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=${SECONDS_LEN}" \
    $ENC -b:v 2M -maxrate 2M -bufsize 4M -pix_fmt yuv420p \
    -c:a aac -b:a 32k -ac 1 \
    -movflags +faststart "$SAMPLE"
fi

# Phát một luồng; tự khởi động lại nếu FFmpeg thoát (MediaMTX khởi động lại, mạng chập chờn)
publish() {
  path="$1"
  while true; do
    ffmpeg -hide_banner -loglevel error -re -stream_loop -1 -i "$SAMPLE" -c copy \
      -f rtsp -rtsp_transport tcp "rtsp://${USER_NAME}:${SECRET}@${TARGET#rtsp://}/${path}" || true
    echo "rtsp-sim: ${path} dừng, thử lại sau 3 giây" >&2
    sleep 3
  done
}

i=1
while [ "$i" -le "$STREAMS" ]; do
  publish "${PREFIX}${i}" &
  i=$((i + 1))
  sleep 0.05   # tránh dồn hết kết nối vào cùng một thời điểm
done
echo "rtsp-sim: đang phát ${STREAMS} luồng ${CODEC} tới ${TARGET}/${PREFIX}1..${PREFIX}${STREAMS}"
wait
