#!/bin/sh
# Hook runOnRecordSegmentComplete (TS-03.4): báo Core đã ghi xong một đoạn 60 giây.
# MediaMTX truyền MTX_PATH, MTX_SEGMENT_PATH, MTX_SEGMENT_DURATION qua biến môi trường.
# Lỗi gọi Core không được làm hỏng ghi hình: chỉ ghi ra stderr, Core đối soát lại thư mục ghi ở S4.
body=$(printf '{"path":"%s","file":"%s","duration":"%s"}' "$MTX_PATH" "$MTX_SEGMENT_PATH" "$MTX_SEGMENT_DURATION")
wget -q -O /dev/null -T 5 \
    --header "Content-Type: application/json" \
    --post-data "$body" \
    http://core:8080/internal/media/segments \
    || echo "segment-complete: không gọi được Core cho $MTX_SEGMENT_PATH" >&2
exit 0
