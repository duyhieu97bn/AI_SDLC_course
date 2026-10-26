# Phase S2 – 2FA, nhật ký, thêm camera và trạng thái

> Sprint **S2** · Mốc **M1 – Alpha** · 23/11/2026 – 04/12/2026 (2 tuần) · Kế hoạch **28 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

2FA, nhật ký; thêm camera (thủ công, dò tìm) và theo dõi trạng thái.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-01 | Dò tìm camera ONVIF | Story | EP-01 | Must | 8 | TS-05, US-02, US-05 |
| US-02 | Thêm camera thủ công | Story | EP-01 | Must | 5 | TS-04, TS-05, US-05 |
| US-04 | Trạng thái camera | Story | EP-01 | Must | 5 | US-02 |
| US-31 | Xác thực hai lớp | Story | EP-05 | Must | 5 | US-30 |
| US-33 | Nhật ký thao tác | Story | EP-05 | Must | 5 | TS-04 |
| | **Tổng** | | | | **28** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-33, US-31, US-02 |
| Tuần 2 | US-01, US-04 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-01 Dò tìm camera ONVIF (8 điểm)

*Là Technician, tôi muốn hệ thống tự tìm camera trong mạng của địa điểm, để không phải nhập IP từng camera.*

Truy vết: yêu cầu BR-01 · use case UC-10 · màn hình cameras.html (Dò tìm ONVIF) · SADD: Core device; Device Gateway discovery

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-01.1 | BE | API POST /sites/{id}/discovery, đánh dấu thiết bị đã thêm theo MAC, thêm hàng loạt | `core/src/main/java/vn/camhub/device/application/DiscoveryService.java` | 6 |
| US-01.2 | PY | Dò tìm theo địa điểm, timeout 30 giây, trả DiscoveredDevice | `device-gateway/app/discovery/service.py` | 4 |
| US-01.3 | FE | Wizard dò tìm 4 bước theo design/cameras.html | `web/src/components/DiscoveryWizard.vue` | 8 |
| US-01.4 | QA | Test 3 kịch bản với camera thật và giả lập | `tests/e2e/features/US-01_do_tim.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Tìm thấy camera
- [ ] Camera đã thêm
- [ ] Một camera sai mật khẩu

### US-02 Thêm camera thủ công (5 điểm)

*Là Technician, tôi muốn thêm camera bằng IP/cổng/tài khoản hoặc URL RTSP, để kết nối được camera không bật ONVIF.*

Truy vết: yêu cầu BR-02 · use case UC-11 · màn hình cameras.html (Thêm thủ công) · SADD: Core device; Device Gateway capabilities; MediaMTX path

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-02.1 | BE | Entity Camera, StreamProfile, DeviceCapabilities; mật khẩu mã hóa; migration | `core/src/main/java/vn/camhub/device/domain/Camera.java, core/src/main/resources/db/migration/V2_2__device.sql` | 6 |
| US-02.2 | BE | API test-connection, tạo camera; tạo path trên MediaMTX | `core/src/main/java/vn/camhub/device/web/CameraController.java, core/src/main/java/vn/camhub/device/infrastructure/MediaMtxClient.java` | 6 |
| US-02.3 | PY | Endpoint kiểm tra kết nối ONVIF/RTSP, lấy ảnh xem trước | `device-gateway/app/control/connection.py` | 5 |
| US-02.4 | FE | Modal thêm thủ công (ONVIF/RTSP), kiểm tra kết nối | `web/src/components/AddCameraManualDialog.vue` | 5 |
| US-02.5 | QA | Test 2 kịch bản | `tests/e2e/features/US-02_them_thu_cong.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Kiểm tra kết nối thành công
- [ ] Kiểm tra kết nối thất bại

### US-04 Trạng thái camera (5 điểm)

*Là Operator, tôi muốn thấy camera nào đang mất kết nối, để báo kỹ thuật kịp thời.*

Truy vết: yêu cầu BR-04 · use case UC-16 · màn hình dashboard.html; cameras.html · SADD: Core device; Device Gateway events; WebSocket /topic camera-status

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-04.1 | PY | Theo dõi kết nối định kỳ, phát DeviceOnline/DeviceOffline lên RabbitMQ | `device-gateway/app/events/health_monitor.py` | 5 |
| US-04.2 | BE | Cập nhật trạng thái, lastOfflineAt; đẩy WebSocket /topic/org/{id}/camera-status | `core/src/main/java/vn/camhub/device/application/CameraStatusService.java, core/src/main/java/vn/camhub/platform/ws/WebSocketConfig.java` | 6 |
| US-04.3 | FE | Badge trạng thái realtime trong danh sách camera và cây | `web/src/stores/cameraStatus.ts` | 4 |
| US-04.4 | QA | Test cập nhật ≤ 60 giây | `tests/e2e/features/US-04_trang_thai.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Cập nhật trạng thái
- [ ] Tổng quan theo địa điểm

### US-31 Xác thực hai lớp (5 điểm)

*Là Admin, tôi muốn bắt buộc 2FA cho các vai trò quan trọng, để giảm rủi ro lộ mật khẩu.*

Truy vết: yêu cầu BR-36 · use case UC-02 · màn hình login.html (2FA); system.html (Bảo mật) · SADD: Core identity (TOTP, RecoveryCode)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-31.1 | BE | TOTP, mã dự phòng (RecoveryCode), bắt buộc 2FA theo Role.requireTwoFactor | `core/src/main/java/vn/camhub/identity/application/TwoFactorService.java` | 8 |
| US-31.2 | FE | Bước nhập mã 2FA, thiết lập 2FA (QR), cài đặt bắt buộc 2FA ở trang Hệ thống | `web/src/views/LoginView.vue, web/src/views/system/SecurityTab.vue` | 6 |
| US-31.3 | QA | Test bắt buộc 2FA, mã dự phòng dùng một lần | `tests/e2e/features/US-31_2fa.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Bắt buộc 2FA
- [ ] Mã dự phòng

### US-33 Nhật ký thao tác (5 điểm)

*Là Admin, tôi muốn xem ai đã làm gì, để truy vết khi có sự cố.*

Truy vết: yêu cầu BR-30, RULE-06 · use case UC-08 · màn hình audit.html · SADD: Core audit; bảng audit_log phân vùng, trigger chặn sửa/xóa

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-33.1 | BE | Bảng audit_log phân vùng theo tháng, trigger chặn UPDATE/DELETE, quyền DB chỉ INSERT/SELECT | `core/src/main/resources/db/migration/V2_1__audit.sql` | 5 |
| US-33.2 | BE | Listener AuditableEvent → audit_log; API tra cứu, xuất CSV | `core/src/main/java/vn/camhub/audit/application/AuditRecorder.java, core/src/main/java/vn/camhub/audit/web/AuditController.java` | 6 |
| US-33.3 | FE | Màn hình nhật ký theo design/audit.html | `web/src/views/AuditView.vue` | 4 |
| US-33.4 | QA | Test không sửa/xóa được nhật ký | `tests/e2e/features/US-33_nhat_ky.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Tra cứu
- [ ] Không sửa được

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/audit/application/AuditRecorder.java`
- `core/src/main/java/vn/camhub/audit/web/AuditController.java`
- `core/src/main/java/vn/camhub/device/application/CameraStatusService.java`
- `core/src/main/java/vn/camhub/device/application/DiscoveryService.java`
- `core/src/main/java/vn/camhub/device/domain/Camera.java`
- `core/src/main/java/vn/camhub/device/infrastructure/MediaMtxClient.java`
- `core/src/main/java/vn/camhub/device/web/CameraController.java`
- `core/src/main/java/vn/camhub/identity/application/TwoFactorService.java`
- `core/src/main/java/vn/camhub/platform/ws/WebSocketConfig.java`
- `core/src/main/resources/db/migration/V2_1__audit.sql`
- `core/src/main/resources/db/migration/V2_2__device.sql`

**Device Gateway (Python)**

- `device-gateway/app/control/connection.py`
- `device-gateway/app/discovery/service.py`
- `device-gateway/app/events/health_monitor.py`

**Web App (Vue 3)**

- `web/src/components/AddCameraManualDialog.vue`
- `web/src/components/DiscoveryWizard.vue`
- `web/src/stores/cameraStatus.ts`
- `web/src/views/AuditView.vue`
- `web/src/views/LoginView.vue`
- `web/src/views/system/SecurityTab.vue`

**Kiểm thử**

- `tests/e2e/features/US-01_do_tim.feature`
- `tests/e2e/features/US-02_them_thu_cong.feature`
- `tests/e2e/features/US-04_trang_thai.feature`
- `tests/e2e/features/US-31_2fa.feature`
- `tests/e2e/features/US-33_nhat_ky.feature`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 43 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 14 |
| FE – Frontend Vue (2 người) | 27 |
| QA – Kiểm thử (1 người) | 15 |
| **Tổng** | **99** |

## 7. Điều kiện vào

- [ ] S1 đạt điều kiện ra
- [ ] Bộ kiểm thử tương thích (TS-05) đã chạy trên camera mẫu

## 8. Điều kiện ra

- [ ] 2FA bắt buộc theo vai trò
- [ ] Nhật ký không sửa/xóa được ở mức DB
- [ ] Thêm camera thủ công và bằng dò tìm trong LAN
- [ ] Trạng thái camera cập nhật realtime ≤ 60 giây
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Minh (Technician) bị yêu cầu thiết lập 2FA
2. Dò tìm Kho Bắc Ninh, thêm 3 camera, 1 camera sai mật khẩu
3. Rút dây mạng một camera, trạng thái đổi trong 60 giây; xem nhật ký

## 10. Rủi ro

- Một số hãng không trả lời WS-Discovery: hướng dẫn dùng thêm thủ công (US-02)

## 11. Phase tiếp theo

[Phase S3 – Xem trực tiếp, bố cục, PTZ](phase-03-S3-xem-truc-tiep.md)
