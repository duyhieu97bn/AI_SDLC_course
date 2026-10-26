# Trạng thái Phase S6–S7

> Cập nhật: 25/10/2026 · Kế hoạch: [phase-06](phase-06-S6-ket-noi-dia-diem.md), [phase-07](phase-07-S7-canh-bao-da-kenh.md)

## S6 – Kết nối địa điểm

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| TS-06 Tunnel Server + Site Connector | Xong | chisel 1.12.1 sau Nginx `/tunnel/`; `site-connector/` (21 test). Đã kiểm với Core thật ở profile `site` (mạng `site-lan` không có route ra ngoài): kích hoạt → dò WS-Discovery qua Connector 8,1 giây → thêm camera ONVIF qua SOCKS5 → MediaMTX kéo RTSP qua cổng chuyển tiếp (h264 1280×720) → trạng thái ONLINE. Quy trình: [tests/integration/tunnel_test.md](../../tests/integration/tunnel_test.md) |
| US-03 Đầu ghi NVR/DVR | Xong | `RecorderIT` (16 kênh, chọn 3 → 3 camera tên theo kênh; đầu ghi mất kết nối → 3 camera OFFLINE); e2e với `onvif-nvr-1` |
| US-06 Kích hoạt bộ kết nối | Xong | `ConnectorIT` (4 test: kích hoạt, mã quá 24 giờ, mã dùng lại, thu hồi, mất liên lạc 90 giây, authfile); e2e trạng thái "Trực tuyến" cập nhật realtime |
| US-11 Tự chuyển luồng | Xong (unit) | `AdaptiveStream.spec.ts`: hạ luồng sau 5 giây thiếu băng thông, lên lại khi ≥ 1,5 lần trong 30 giây. Chỉ hạ khi có mất gói thật. **Chưa thử với tc/netem** |
| US-14 Chụp ảnh, phóng to số, toàn màn hình | Xong | e2e: PNG `Bãi xe 1_<thời điểm>.png`, phóng to 8x |
| US-28 Đổi mật khẩu, khởi động lại | Xong | `RecorderIT` (4 camera, 1 lỗi giữ mật khẩu cũ; khởi động lại theo dõi tới ONLINE); e2e |

## S7 – Cảnh báo đa kênh

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| US-22 Xem lại từ thiết bị | Xong (giả lập) | Gateway Profile G (FindRecordings/FindEvents/GetReplayUri, 20 test); `EdgePlaybackIT`; e2e timeline thẻ nhớ và phát được trên `onvif-sim-2`. Camera không hỗ trợ: nguồn bị vô hiệu kèm lý do. Tua tới thời điểm dùng `?starttime=` kiểu Hikvision vì MediaMTX/ffmpeg không gửi được `Range: clock=` |
| US-24 Cấu hình hình ảnh | Xong | `EdgePlaybackIT` (kiểm tra khoảng giá trị, WDR); e2e kéo độ sáng rồi khôi phục |
| US-36 Cảnh báo chuyển động đa kênh | Xong | `MotionAlertIT`: 22:00–06:00, chống spam 3 lần/phút → 1 cảnh báo, camera không phát hiện chuyển động; e2e chuỗi thật: PullPoint → Gateway → RabbitMQ → Core → ảnh từ Media Worker → Telegram (chat-mock) kèm ảnh |
| US-37 Xử lý và chuyển giao | Xong | `MotionAlertIT` (INV-28, đóng bắt buộc kết quả); e2e Lan → Minh |
| US-38 Xem lại từ cảnh báo | Xong | e2e: liên kết xem lại = thời điểm cảnh báo − 10 giây, có nút xem trực tiếp |
| US-42 Trung tâm thông báo | Xong | e2e: chuông tăng số và toast trên trang Xem trực tiếp; âm báo bật/tắt |

## Lỗi phát hiện và đã sửa trong S6–S7

- **Realtime:** Nginx trả 403 cho WebSocket vì Origin không khớp. Đã sửa `X-Forwarded-Proto` và `Host $http_host`.
- **Chỉ mục đoạn ghi:** khi MediaMTX dừng đột ngột, hook báo thời lượng 0 và Core đã lấy "bây giờ" làm điểm kết thúc. Kết quả là một đoạn "dài 52 phút" mà máy chủ playback trả 404. Nay giới hạn ở 60 giây và ghi cảnh báo.
- **Tự chuyển luồng:** trước đây đổi luồng nhầm ngay sau khi kết nối, do 0 gói tin bị coi là băng thông 0.

## Còn mở

- Khả năng của camera được lưu lúc thêm. Thiết bị đổi firmware/cấu hình (ví dụ bật lưu trữ thẻ nhớ) thì cần làm mới thông tin thiết bị; nên thêm thao tác "Làm mới thông tin" (đề xuất S8/S9).
- Zalo phụ thuộc OQ-P02 (OA đã xác minh). Mã gửi đã có và đã thử với chat-mock.
- Camera thật cho US-22/US-26/US-36: chưa kiểm (chỉ có bộ giả lập).
