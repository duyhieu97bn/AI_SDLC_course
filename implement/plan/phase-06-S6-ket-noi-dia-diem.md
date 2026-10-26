# Phase S6 – Bộ kết nối địa điểm, đầu ghi, chất lượng luồng

> Sprint **S6** · Mốc **M2 – Beta pilot** · 18/01/2027 – 29/01/2027 (2 tuần) · Kế hoạch **28 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Bộ kết nối địa điểm, đầu ghi NVR, chất lượng luồng, tiện ích xem.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| TS-06 | Tunnel Server và Site Connector | Enabler | EP-00 | Should | 8 | TS-02, TS-05 |
| US-03 | Thêm đầu ghi NVR/DVR | Story | EP-01 | Should | 5 | US-02 |
| US-06 | Kích hoạt bộ kết nối địa điểm | Story | EP-01 | Should | 5 | TS-06 |
| US-11 | Tự chuyển luồng theo băng thông | Story | EP-02 | Should | 5 | US-09 |
| US-14 | Chụp ảnh, phóng to số, toàn màn hình | Story | EP-02 | Should | 2 | US-08 |
| US-28 | Đổi mật khẩu, khởi động lại camera | Story | EP-04 | Should | 3 | US-02 |
| | **Tổng** | | | | **28** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | TS-06, US-06 |
| Tuần 2 | US-03, US-11, US-14, US-28 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### TS-06 Tunnel Server và Site Connector (8 điểm)

*Là Đội phát triển, tôi muốn có đường hầm ngược qua WSS 443 từ địa điểm về máy chủ, để Device Gateway và MediaMTX truy cập được camera sau NAT (ADR-10).*

Truy vết: yêu cầu BR-06 · use case UC-15, UC-10 · màn hình — · SADD: ADR-10; 3.9.5

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-06.1 | OPS | Tunnel Server (chisel) sau Nginx, xác thực theo Connector | `deploy/tunnel/` | 6 |
| TS-06.2 | PY | Site Connector: client đường hầm, dò tìm WS-Discovery tại chỗ, báo băng thông | `site-connector/app/` | 12 |
| TS-06.3 | PY | Device Gateway định tuyến lệnh ONVIF qua đường hầm | `device-gateway/app/transport/tunnel_router.py` | 4 |
| TS-06.4 | QA | Test địa điểm chỉ mở cổng 443 ra ngoài | `tests/integration/tunnel_test.md` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Kết nối chỉ qua cổng 443

### US-03 Thêm đầu ghi NVR/DVR (5 điểm)

*Là Technician, tôi muốn thêm một đầu ghi và nhận mọi kênh, để không phải thêm từng camera.*

Truy vết: yêu cầu BR-03 · use case UC-12 · màn hình cameras.html (Thêm đầu ghi) · SADD: Core device (Recorder); Device Gateway

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-03.1 | PY | Đọc danh sách kênh của NVR/DVR | `device-gateway/app/discovery/recorder_channels.py` | 5 |
| US-03.2 | BE | Recorder, tạo camera theo kênh, trạng thái theo đầu ghi | `core/src/main/java/vn/camhub/device/domain/Recorder.java` | 5 |
| US-03.3 | FE | Modal thêm đầu ghi, chọn kênh | `web/src/components/AddRecorderDialog.vue` | 4 |
| US-03.4 | QA | Test 2 kịch bản | `tests/e2e/features/US-03_dau_ghi.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Chọn kênh
- [ ] Đầu ghi mất kết nối

### US-06 Kích hoạt bộ kết nối địa điểm (5 điểm)

*Là Admin, tôi muốn kết nối camera ở chi nhánh không có IP tĩnh, để không phải cấu hình router.*

Truy vết: yêu cầu BR-06 · use case UC-15 · màn hình system.html (Bộ kết nối) · SADD: Tunnel Server; Site Connector; Core device (Connector)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-06.1 | BE | Connector: mã kích hoạt 1 lần, hết hạn 24 giờ, trạng thái, lastSeenAt | `core/src/main/java/vn/camhub/device/domain/Connector.java, core/src/main/java/vn/camhub/device/web/ConnectorController.java` | 6 |
| US-06.2 | FE | Tab Bộ kết nối, modal tạo (mã + lệnh cài) | `web/src/views/system/ConnectorsTab.vue` | 4 |
| US-06.3 | QA | Test kích hoạt và mã hết hạn | `tests/e2e/features/US-06_bo_ket_noi.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Kích hoạt thành công
- [ ] Mã hết hạn

### US-11 Tự chuyển luồng theo băng thông (5 điểm)

*Là Operator, tôi muốn hình ảnh không bị đứng khi mạng yếu, để vẫn theo dõi được.*

Truy vết: yêu cầu BR-10 · use case UC-20 · màn hình live.html (Mô phỏng mạng yếu) · SADD: SADD 3.11.5 (getStats)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-11.1 | FE | Đo getStats, chuyển luồng có trễ 5 giây, quay lại khi ≥ 1,5 lần bitrate trong 30 giây; chọn thủ công | `web/src/player/AdaptiveStream.ts` | 8 |
| US-11.2 | QA | Test với giới hạn băng thông (tc/netem) | `tests/e2e/features/US-11_chuyen_luong.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Tự hạ chất lượng
- [ ] Chế độ thủ công

### US-14 Chụp ảnh, phóng to số, toàn màn hình (2 điểm)

*Là Operator, tôi muốn chụp nhanh khung hình, để lưu bằng chứng tức thời.*

Truy vết: yêu cầu BR-12 · use case UC-24 · màn hình live.html · SADD: Web player

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-14.1 | FE | Chụp ảnh PNG, zoom số 8x, toàn màn hình | `web/src/components/VideoTile.vue` | 4 |
| US-14.2 | QA | Test chụp ảnh | `tests/e2e/features/US-14_chup_anh.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Chụp ảnh

### US-28 Đổi mật khẩu, khởi động lại camera (3 điểm)

*Là Technician, tôi muốn đổi mật khẩu và khởi động lại camera từ xa, để xử lý sự cố nhanh.*

Truy vết: yêu cầu BR-26 · use case UC-42 · màn hình cameras.html (Đổi mật khẩu); camera-detail.html (Khởi động lại) · SADD: Device Gateway control; Core device

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-28.1 | PY | SetUser (đổi mật khẩu), SystemReboot | `device-gateway/app/control/maintenance.py` | 3 |
| US-28.2 | BE | API đổi mật khẩu hàng loạt (cập nhật mật khẩu đã lưu khi thành công), khởi động lại | `core/src/main/java/vn/camhub/device/application/MaintenanceService.java` | 4 |
| US-28.3 | FE | Modal đổi mật khẩu, xác nhận khởi động lại | `web/src/components/ChangeCameraPasswordDialog.vue` | 2 |
| US-28.4 | QA | Test 2 kịch bản | `tests/e2e/features/US-28_bao_tri.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đổi mật khẩu hàng loạt
- [ ] Khởi động lại

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/device/application/MaintenanceService.java`
- `core/src/main/java/vn/camhub/device/domain/Connector.java`
- `core/src/main/java/vn/camhub/device/domain/Recorder.java`
- `core/src/main/java/vn/camhub/device/web/ConnectorController.java`

**Device Gateway (Python)**

- `device-gateway/app/control/maintenance.py`
- `device-gateway/app/discovery/recorder_channels.py`
- `device-gateway/app/transport/tunnel_router.py`

**Site Connector (Python)**

- `site-connector/app/`

**Web App (Vue 3)**

- `web/src/components/AddRecorderDialog.vue`
- `web/src/components/ChangeCameraPasswordDialog.vue`
- `web/src/components/VideoTile.vue`
- `web/src/player/AdaptiveStream.ts`
- `web/src/views/system/ConnectorsTab.vue`

**Triển khai (Docker Compose, cấu hình)**

- `deploy/tunnel/`

**Kiểm thử**

- `tests/e2e/features/US-03_dau_ghi.feature`
- `tests/e2e/features/US-06_bo_ket_noi.feature`
- `tests/e2e/features/US-11_chuyen_luong.feature`
- `tests/e2e/features/US-14_chup_anh.feature`
- `tests/e2e/features/US-28_bao_tri.feature`
- `tests/integration/tunnel_test.md`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 15 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 24 |
| FE – Frontend Vue (2 người) | 22 |
| QA – Kiểm thử (1 người) | 14 |
| OPS – DevOps (bán thời gian, kiêm PY hoặc BE) | 6 |
| **Tổng** | **81** |

## 7. Điều kiện vào

- [ ] Alpha (M1) được PO chấp nhận
- [ ] Chọn được địa điểm pilot có camera của ít nhất 3 hãng

## 8. Điều kiện ra

- [ ] Camera sau NAT kết nối qua cổng 443
- [ ] Thêm đầu ghi NVR/DVR
- [ ] Tự chuyển luồng khi mạng yếu
- [ ] Đổi mật khẩu hàng loạt, khởi động lại camera
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Kích hoạt bộ kết nối tại một chi nhánh không có IP tĩnh, dò tìm camera qua đường hầm
2. Giới hạn băng thông, ô video tự hạ chất lượng

## 10. Rủi ro

- Băng thông qua đường hầm không đủ (AR-06): đo thực tế tại địa điểm pilot
- Trước Tết Nguyên đán: chốt bản Beta để triển khai pilot sau Tết

## 11. Phase tiếp theo

[Phase S7 – Cảnh báo đa kênh và xử lý; xem lại từ thiết bị](phase-07-S7-canh-bao-da-kenh.md)
