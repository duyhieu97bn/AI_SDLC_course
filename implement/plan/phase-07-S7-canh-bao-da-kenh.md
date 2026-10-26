# Phase S7 – Cảnh báo đa kênh và xử lý; xem lại từ thiết bị

> Sprint **S7** · Mốc **M2 – Beta pilot** · 15/02/2027 – 26/02/2027 (2 tuần) · Kế hoạch **29 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Cảnh báo đa kênh và xử lý; xem lại từ thiết bị.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-22 | Xem lại từ thiết bị | Story | EP-03 | Should | 8 | US-17, TS-05 |
| US-24 | Cấu hình hình ảnh | Story | EP-04 | Should | 3 | US-23 |
| US-36 | Cảnh báo chuyển động đa kênh | Story | EP-06 | Should | 8 | US-34, US-26 |
| US-37 | Xử lý và chuyển giao cảnh báo | Story | EP-06 | Should | 5 | US-34 |
| US-38 | Mở xem lại từ cảnh báo | Story | EP-06 | Should | 2 | US-17, US-37 |
| US-42 | Trung tâm thông báo trên web | Story | EP-07 | Should | 3 | US-34 |
| | **Tổng** | | | | **29** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-36 (Gateway PullPoint + đánh giá quy tắc), US-42 |
| Tuần 2 | US-36 (kênh), US-37, US-38, US-22, US-24 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-22 Xem lại từ thiết bị (8 điểm)

*Là Operator, tôi muốn xem video trên thẻ nhớ hoặc đầu ghi, để có dữ liệu cả khi máy chủ không ghi được.*

Truy vết: yêu cầu BR-20 · use case UC-31 · màn hình playback.html (Nguồn: Thiết bị) · SADD: Device Gateway edge_playback (Profile G); MediaMTX

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-22.1 | PY | Tìm kiếm và lấy URL phát lại trên thiết bị (ONVIF Profile G) | `device-gateway/app/edge_playback/search.py` | 8 |
| US-22.2 | BE | Timeline nguồn DEVICE; phát qua MediaMTX path tạm | `core/src/main/java/vn/camhub/recording/application/EdgePlaybackService.java` | 6 |
| US-22.3 | FE | Chọn nguồn CamHub/Thiết bị, vô hiệu khi không hỗ trợ | `web/src/views/PlaybackView.vue` | 3 |
| US-22.4 | QA | Test với camera Profile G và đầu ghi | `tests/e2e/features/US-22_xem_lai_thiet_bi.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Nguồn thiết bị
- [ ] Không hỗ trợ

### US-24 Cấu hình hình ảnh (3 điểm)

*Là Technician, tôi muốn chỉnh sáng, tương phản, hồng ngoại, WDR, để hình ảnh rõ hơn mà không cần đến hiện trường.*

Truy vết: yêu cầu BR-22 · use case UC-40 · màn hình camera-detail.html (Hình ảnh) · SADD: Core configuration; Device Gateway control

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-24.1 | PY | Imaging service: sáng, tương phản, IR, WDR | `device-gateway/app/control/imaging.py` | 3 |
| US-24.2 | FE | Tab Hình ảnh có xem trước, khôi phục giá trị trước | `web/src/views/camera/ImageTab.vue` | 3 |
| US-24.3 | QA | Test khôi phục | `tests/e2e/features/US-24_hinh_anh.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Khôi phục

### US-36 Cảnh báo chuyển động đa kênh (8 điểm)

*Là Operator, tôi muốn nhận cảnh báo chuyển động qua web, email, Telegram và Zalo, để phản ứng kịp thời ngoài giờ.*

Truy vết: yêu cầu BR-32 · use case UC-50, UC-51, UC-53 · màn hình alerts.html (Quy tắc, Kênh nhận); system.html (Kênh thông báo) · SADD: Device Gateway events (PullPoint); RabbitMQ; Core alerting, notification; Redis throttle; Media Worker snapshot

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-36.1 | PY | Đăng ký PullPoint với từng camera, chuẩn hóa MotionDetected | `device-gateway/app/events/pullpoint.py` | 8 |
| US-36.2 | BE | Đánh giá quy tắc MOTION theo lịch, chống spam Redis SET NX EX, yêu cầu ảnh sự kiện | `core/src/main/java/vn/camhub/alerting/application/MotionRuleEvaluator.java` | 6 |
| US-36.3 | PY | Media Worker chụp ảnh sự kiện lên MinIO | `media-worker/app/snapshot_job.py` | 3 |
| US-36.4 | BE | Kênh Telegram, Zalo; liên kết kênh nhận của người dùng (UserChannelBinding) | `core/src/main/java/vn/camhub/notification/infrastructure/TelegramSender.java, ZaloSender.java` | 8 |
| US-36.5 | FE | Tab Quy tắc cảnh báo, Kênh nhận của tôi, Kênh thông báo (Hệ thống) | `web/src/views/alerts/RulesTab.vue, web/src/views/alerts/ChannelsTab.vue` | 6 |
| US-36.6 | QA | Test 4 kịch bản | `tests/e2e/features/US-36_canh_bao_chuyen_dong.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Trong khung giờ
- [ ] Ngoài khung giờ
- [ ] Chống spam
- [ ] Camera không hỗ trợ

### US-37 Xử lý và chuyển giao cảnh báo (5 điểm)

*Là Operator, tôi muốn nhận, ghi chú, chuyển giao và đóng cảnh báo, để cả ca biết cảnh báo nào đã có người xử lý.*

Truy vết: yêu cầu BR-33 · use case UC-52 · màn hình alerts.html · SADD: Core alerting (Alert, AlertStatusChange, AlertHandover)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-37.1 | BE | acknowledge, reassign (INV-28, AlertHandover), close bắt buộc outcome | `core/src/main/java/vn/camhub/alerting/application/AlertWorkflowService.java` | 6 |
| US-37.2 | FE | Panel chi tiết cảnh báo, modal chuyển giao, modal đóng | `web/src/views/alerts/AlertDetail.vue` | 5 |
| US-37.3 | QA | Test 3 kịch bản | `tests/e2e/features/US-37_xu_ly_canh_bao.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Nhận xử lý
- [ ] Chuyển giao
- [ ] Đóng bắt buộc kết quả

### US-38 Mở xem lại từ cảnh báo (2 điểm)

*Là Operator, tôi muốn mở ngay video tại thời điểm cảnh báo, để xác minh trong vài giây.*

Truy vết: yêu cầu BR-38 · use case UC-52, UC-31 · màn hình alerts.html → playback.html · SADD: Web; Core recording

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-38.1 | FE | Nút Xem lại tại thời điểm cảnh báo trừ 10 giây | `web/src/views/alerts/AlertDetail.vue` | 2 |
| US-38.2 | QA | Test mở xem lại | `tests/e2e/features/US-38_xem_lai_tu_canh_bao.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Xem lại từ cảnh báo

### US-42 Trung tâm thông báo trên web (3 điểm)

*Là Operator, tôi muốn thấy cảnh báo mới ngay trên web, để không bỏ sót khi đang xem camera.*

Truy vết: yêu cầu BR-32 · use case UC-51, UC-52 · màn hình Thanh trên cùng (chuông); alerts.html · SADD: WebSocket /user/queue/alerts

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-42.1 | FE | Chuông + toast + âm báo, nhận /user/queue/alerts | `web/src/components/NotificationBell.vue` | 4 |
| US-42.2 | QA | Test cảnh báo mới | `tests/e2e/features/US-42_trung_tam_thong_bao.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Cảnh báo mới

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/alerting/application/AlertWorkflowService.java`
- `core/src/main/java/vn/camhub/alerting/application/MotionRuleEvaluator.java`
- `core/src/main/java/vn/camhub/notification/infrastructure/TelegramSender.java`
- `core/src/main/java/vn/camhub/recording/application/EdgePlaybackService.java`

**Device Gateway (Python)**

- `device-gateway/app/control/imaging.py`
- `device-gateway/app/edge_playback/search.py`
- `device-gateway/app/events/pullpoint.py`

**Media Worker (Python + FFmpeg)**

- `media-worker/app/snapshot_job.py`

**Web App (Vue 3)**

- `web/src/components/NotificationBell.vue`
- `web/src/views/PlaybackView.vue`
- `web/src/views/alerts/AlertDetail.vue`
- `web/src/views/alerts/ChannelsTab.vue`
- `web/src/views/alerts/RulesTab.vue`
- `web/src/views/camera/ImageTab.vue`

**Kiểm thử**

- `tests/e2e/features/US-22_xem_lai_thiet_bi.feature`
- `tests/e2e/features/US-24_hinh_anh.feature`
- `tests/e2e/features/US-36_canh_bao_chuyen_dong.feature`
- `tests/e2e/features/US-37_xu_ly_canh_bao.feature`
- `tests/e2e/features/US-38_xem_lai_tu_canh_bao.feature`
- `tests/e2e/features/US-42_trung_tam_thong_bao.feature`

**Khác**

- `ZaloSender.java`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 26 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 22 |
| FE – Frontend Vue (2 người) | 23 |
| QA – Kiểm thử (1 người) | 13 |
| **Tổng** | **84** |

## 7. Điều kiện vào

- [ ] S6 đạt điều kiện ra
- [ ] Đã có Telegram bot; Zalo OA đã xác thực (OQ-P02), nếu chưa có thì làm Zalo sau cùng

## 8. Điều kiện ra

- [ ] Cảnh báo chuyển động theo lịch, chống spam, gửi web/email/Telegram/Zalo
- [ ] Nhận, chuyển giao, đóng cảnh báo
- [ ] Xem lại video trên thẻ nhớ/đầu ghi
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Kịch bản SC-02: cảnh báo đêm qua Telegram, mở xem lại, PTZ, chuyển giao, đóng

## 10. Rủi ro

- Nghỉ Tết Nguyên đán (dự kiến 01/02–12/02/2027) đã được tính bằng cách lùi S7; cần xác nhận lịch nghỉ chính thức
- Zalo OA chưa sẵn sàng (BQ-03)

## 11. Phase tiếp theo

[Phase S8 – Đồng bộ nhiều camera, cấu hình mẫu, vai trò tùy chỉnh, LDAP/AD – pilot](phase-08-S8-pilot-ldap-vai-tro.md)
