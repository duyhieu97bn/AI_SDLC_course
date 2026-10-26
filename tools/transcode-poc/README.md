# PoC chuyển mã H.265 sang H.264 (TS-03.5)

Nhiều trình duyệt không giải mã được H.265 qua WebRTC. Theo SADD 3.9.2, khi gặp trường hợp này Core cấp URL `<camera>_h264`. MediaMTX chỉ chạy FFmpeg chuyển mã khi có người xem (`runOnDemand` trong `deploy/mediamtx/mediamtx.yml`), và dừng 15 giây sau khi người xem cuối cùng rời đi.

Script đo CPU mà MediaMTX và FFmpeg dùng cho mỗi phiên chuyển mã, để chốt giới hạn số phiên đồng thời (PR-01):

```bash
SIM_CODEC=h265 ./deploy/scripts/dev.sh sim 10
./tools/transcode-poc/measure.sh 1 60     # 1 phiên, 60 giây
./tools/transcode-poc/measure.sh 4 60     # 4 phiên
```

Kết quả được ghi vào `docs/poc/results/transcode-k<K>-<thời điểm>.csv`, thư mục này không được đưa vào Git. Hãy điền số liệu vào bảng trong [báo cáo PoC](../../docs/poc/mediamtx-report.md).
