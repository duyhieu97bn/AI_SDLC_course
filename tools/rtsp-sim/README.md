# Bộ giả lập RTSP (TS-03.1)

Phát N luồng giống camera vào MediaMTX, để đo tải khi chưa có đủ camera thật.

Bộ giả lập chỉ mã hóa **một** file mẫu (720p, 25 fps, GOP 2 giây, 2 Mbit/s, có âm thanh AAC). Mỗi luồng đọc lại file đó bằng `-c copy`, nên gần như không tốn CPU. Nhờ vậy, tải đo được là tải của MediaMTX chứ không phải của FFmpeg.

```bash
cd deploy
./scripts/dev.sh sim 20                       # 20 luồng H.264: sim-1..sim-20
SIM_CODEC=h265 ./scripts/dev.sh sim 10        # 10 luồng H.265, để thử chuyển mã
./scripts/dev.sh logs rtsp-sim
```

Bộ giả lập publish bằng tài khoản nội bộ `camhub-internal`, lấy từ secret `media_internal_secret`. Core chỉ cho phép tài khoản này publish. Trình duyệt không bao giờ nhận được tài khoản này.

**Giới hạn:** luồng giả lập chưa kiểm chứng được ONVIF, PTZ hay sự kiện chuyển động. Những phần này cần camera thật (rủi ro trong phase S0).

Với kịch bản 450 luồng, nên chạy bộ giả lập trên một máy khác máy MediaMTX, để CPU của hai bên không ảnh hưởng lẫn nhau. Khi đó đặt `SIM_TARGET=rtsp://<ip-media>:8554` và mở cổng 8554 của MediaMTX cho máy giả lập.
