# Trạng thái Phase S1–S4

> Cập nhật: 24/10/2026 · Kế hoạch: [phase-01](phase-01-S1-dang-nhap-phan-quyen.md), [phase-02](phase-02-S2-them-camera-trang-thai.md), [phase-03](phase-03-S3-xem-truc-tiep.md), [phase-04](phase-04-S4-ghi-hinh-xem-lai.md)

## Kết quả

| Phase | Nội dung chính | Bằng chứng |
| --- | --- | --- |
| S1 Đăng nhập, phân quyền | Đăng nhập JWT 15 phút + refresh cookie xoay vòng, khóa sau 5 lần sai, Argon2id; 5 vai trò hệ thống, phạm vi Tổ chức/Địa điểm/Nhóm/Camera (INV-22, INV-27); cây tổ chức; người dùng | `AuthIT`, `AccessIT`; `tests/e2e/specs/s1.spec.js` |
| S2 Thêm camera, trạng thái | Dò ONVIF (WS-Discovery, unicast), thêm RTSP có xem thử, thêm hàng loạt; trạng thái qua Gateway → RabbitMQ → WebSocket; 2FA TOTP + mã khôi phục; nhật ký thao tác bất biến | `DeviceIT`, `TwoFactorIT`, `AuditIT`; `s2.spec.js` |
| S3 Xem trực tiếp | WebRTC (WHEP) dự phòng HLS, lưới 1–16 (≥ 9 ô dùng luồng phụ), bố cục lưu được, PTZ có khóa 30 giây theo thứ hạng vai trò, preset, chụp ảnh; thu hồi quyền dừng phiên | `LiveIT`; `s3.spec.js` |
| S4 Ghi hình, xem lại | Ghi liên tục/lịch/sự kiện (fMP4 60 giây), dòng thời gian + lịch, phát lại 0,5–16x, từng khung hình; lưu trữ theo ngày, giữ lại phục vụ điều tra, xóa 02:00; cấu hình luồng theo khả năng camera (US-23, US-29) | `RecordingIT`; `s4.spec.js` |

## Quyết định và lệch kế hoạch cần PO xác nhận

- **Site Manager có thêm TALK_AUDIO và CONFIGURE_CAMERA**: thiếu TALK_AUDIO thì Site Manager không tạo được Operator (INV-27: không gán vai trò vượt quyền mình).
- **OQ-04**: thời gian lưu mặc định tạm đặt 30 ngày, Admin đổi được.
- **US-29** gần xong: chỉ hiển thị tham số camera hỗ trợ, kiểm tra trước khi gửi. Còn thiếu mẫu cấu hình (template) áp hàng loạt.
- **WebSocket qua Nginx (sửa ở S5)**: Nginx chỉ định tuyến `/ws/`, nên `/ws` bị chuyển hướng 301. WebSocket không theo chuyển hướng, vì vậy từ S2 tới S5 trình duyệt không kết nối được realtime; test vẫn đạt nhờ tải lại trang và hỏi lại định kỳ. Đã sửa `deploy/nginx/nginx.conf` (`location = /ws`). Đã kiểm lại: cập nhật clip và thông báo tới trình duyệt ngay.
- **MinIO**: vẫn dùng `bitnamilegacy/minio` đóng băng (xem status-S0). Cần ADR chọn kho S3 trước GA.
