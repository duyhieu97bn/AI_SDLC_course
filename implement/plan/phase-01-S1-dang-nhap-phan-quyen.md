# Phase S1 – Đăng nhập, cây địa điểm, phân quyền

> Sprint **S1** · Mốc **M1 – Alpha** · 09/11/2026 – 20/11/2026 (2 tuần) · Kế hoạch **29 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Đăng nhập, cây địa điểm và phân quyền theo phạm vi.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| TS-05 | Khung Device Gateway và bộ kiểm thử tương thích ONVIF | Enabler | EP-00 | Must | 5 | TS-02 |
| US-05 | Tổ chức, lọc và chuyển camera | Story | EP-01 | Must | 8 | TS-04 |
| US-30 | Đăng nhập tài khoản cá nhân | Story | EP-05 | Must | 5 | TS-04 |
| US-32 | Phân quyền theo vai trò và phạm vi | Story | EP-05 | Must | 8 | US-05, US-30 |
| US-41 | Đa ngôn ngữ và responsive | Story | EP-07 | Must | 3 | TS-01 |
| | **Tổng** | | | | **29** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-30, US-05 (domain + migration), TS-05 |
| Tuần 2 | US-32, US-41; hoàn thiện giao diện US-05 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### TS-05 Khung Device Gateway và bộ kiểm thử tương thích ONVIF (5 điểm)

*Là Đội phát triển, tôi muốn có dịch vụ Python giao tiếp ONVIF và bộ test chạy trên danh sách camera mẫu, để phát hiện sớm khác biệt giữa các hãng (R-01, AR-02).*

Truy vết: yêu cầu BR-01, BR-27 · use case UC-10, UC-40 · màn hình — · SADD: 3.3 Gateway modules; 3.7.3

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-05.1 | PY | FastAPI app, mTLS với Core, cấu trúc module | `device-gateway/app/main.py, device-gateway/app/security.py` | 6 |
| TS-05.2 | PY | WS-Discovery trong LAN | `device-gateway/app/discovery/ws_discovery.py` | 6 |
| TS-05.3 | PY | Đọc capabilities: profiles, encoder options, PTZ, imaging, events | `device-gateway/app/capabilities/reader.py` | 8 |
| TS-05.4 | PY | Bộ kiểm thử tương thích chạy trên danh sách camera mẫu (D-04) | `device-gateway/tests/compat/` | 8 |
| TS-05.5 | BE | DeviceControlPort và adapter GatewayClient (REST + mTLS, Resilience4j) | `core/src/main/java/vn/camhub/device/api/DeviceControlPort.java, core/src/main/java/vn/camhub/device/infrastructure/GatewayClient.java` | 6 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Báo cáo tương thích

### US-05 Tổ chức, lọc và chuyển camera (8 điểm)

*Là Site Manager, tôi muốn sắp camera theo địa điểm, khu vực, nhóm và chuyển camera khi cần, để tìm nhanh trong hàng trăm camera mà không mất video cũ.*

Truy vết: yêu cầu BR-05, RULE-07 · use case UC-14 · màn hình cameras.html (cây, Chuyển khu vực) · SADD: Core organization; CameraLocationHistory

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-05.1 | BE | Entity Site, Zone, CameraGroup, CameraLocationHistory; migration | `core/src/main/java/vn/camhub/organization/domain/, core/src/main/resources/db/migration/V1_2__organization.sql` | 6 |
| US-05.2 | BE | API cây phân cấp, lọc, POST /cameras/{id}/move (giữ video, ghi MOVE_CAMERA) | `core/src/main/java/vn/camhub/organization/web/SiteController.java, core/src/main/java/vn/camhub/organization/application/MoveCameraService.java` | 8 |
| US-05.3 | FE | Cây địa điểm, kéo thả camera, modal chuyển khu vực | `web/src/components/CameraTree.vue, web/src/views/CamerasView.vue` | 8 |
| US-05.4 | QA | Test lọc 500 camera ≤ 1 giây (dữ liệu giả), test chuyển camera | `tests/e2e/features/US-05_to_chuc_camera.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Lọc nhanh
- [ ] Một camera một khu vực
- [ ] Chuyển camera giữ video cũ

### US-30 Đăng nhập tài khoản cá nhân (5 điểm)

*Là Người dùng, tôi muốn đăng nhập bằng tài khoản riêng, để mọi thao tác gắn với tôi.*

Truy vết: yêu cầu BR-28, RULE-04 · use case UC-01, UC-03 · màn hình login.html · SADD: Core identity; Spring Security; JWT 15 phút + refresh cookie

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-30.1 | BE | Entity User, Session; migration bảng user, session | `core/src/main/java/vn/camhub/identity/domain/User.java, core/src/main/resources/db/migration/V1_1__identity.sql` | 5 |
| US-30.2 | BE | Đăng nhập, băm Argon2id, khóa sau 5 lần sai, JWT 15 phút + refresh cookie xoay vòng | `core/src/main/java/vn/camhub/identity/application/LoginService.java, core/src/main/java/vn/camhub/identity/web/AuthController.java` | 10 |
| US-30.3 | FE | Màn hình đăng nhập theo design/login.html, interceptor refresh token | `web/src/views/LoginView.vue, web/src/services/http.ts` | 6 |
| US-30.4 | QA | Feature file và test cho 3 kịch bản | `tests/e2e/features/US-30_dang_nhap.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đăng nhập thành công
- [ ] Khóa tài khoản
- [ ] Hết phiên

### US-32 Phân quyền theo vai trò và phạm vi (8 điểm)

*Là Admin, tôi muốn gán vai trò và phạm vi camera cho từng người, để mỗi người chỉ thấy và làm đúng việc của mình.*

Truy vết: yêu cầu BR-29 · use case UC-03, UC-05 · màn hình users.html · SADD: Core access (PermissionEvaluator, cache Redis 3.11.1)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-32.1 | BE | Role, Permission, RoleAssignment; seed 5 vai trò mặc định; migration | `core/src/main/java/vn/camhub/access/domain/, core/src/main/resources/db/migration/V1_3__access.sql` | 6 |
| US-32.2 | BE | PermissionEvaluator theo phạm vi + cache Redis có version (SADD 3.11.1); @PreAuthorize | `core/src/main/java/vn/camhub/access/application/PermissionEvaluator.java` | 10 |
| US-32.3 | BE | API gán quyền; Site Manager chỉ trong địa điểm của mình (INV-27) | `core/src/main/java/vn/camhub/access/web/AssignmentController.java` | 6 |
| US-32.4 | FE | Màn hình người dùng, modal gán quyền theo design/users.html | `web/src/views/UsersView.vue, web/src/components/AssignRoleDialog.vue` | 8 |
| US-32.5 | QA | Test phạm vi, thu hồi quyền ≤ 60 giây, Site Manager tạo người dùng | `tests/e2e/features/US-32_phan_quyen.feature` | 5 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Phạm vi theo địa điểm
- [ ] Thu hồi quyền có hiệu lực nhanh
- [ ] Site Manager tạo người dùng

### US-41 Đa ngôn ngữ và responsive (3 điểm)

*Là Người dùng, tôi muốn dùng giao diện tiếng Việt hoặc tiếng Anh trên mọi màn hình, để làm việc thuận tiện.*

Truy vết: yêu cầu NFR-07 · use case UC-63 · màn hình Mọi màn hình · SADD: Web vue-i18n

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-41.1 | FE | Khung vue-i18n (vi, en), chuyển ngôn ngữ trong hồ sơ, breakpoint responsive 360px | `web/src/i18n/vi.json, web/src/i18n/en.json, web/src/layouts/AppLayout.vue` | 6 |
| US-41.2 | QA | Kiểm tra cuộn ngang ở 360px cho mỗi màn hình mới (đưa vào DoD) | `tests/e2e/responsive.spec.ts` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đổi ngôn ngữ
- [ ] Màn hình điện thoại

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/access/application/PermissionEvaluator.java`
- `core/src/main/java/vn/camhub/access/domain/`
- `core/src/main/java/vn/camhub/access/web/AssignmentController.java`
- `core/src/main/java/vn/camhub/device/api/DeviceControlPort.java`
- `core/src/main/java/vn/camhub/device/infrastructure/GatewayClient.java`
- `core/src/main/java/vn/camhub/identity/application/LoginService.java`
- `core/src/main/java/vn/camhub/identity/domain/User.java`
- `core/src/main/java/vn/camhub/identity/web/AuthController.java`
- `core/src/main/java/vn/camhub/organization/application/MoveCameraService.java`
- `core/src/main/java/vn/camhub/organization/domain/`
- `core/src/main/java/vn/camhub/organization/web/SiteController.java`
- `core/src/main/resources/db/migration/V1_1__identity.sql`
- `core/src/main/resources/db/migration/V1_2__organization.sql`
- `core/src/main/resources/db/migration/V1_3__access.sql`

**Device Gateway (Python)**

- `device-gateway/app/capabilities/reader.py`
- `device-gateway/app/discovery/ws_discovery.py`
- `device-gateway/app/main.py`
- `device-gateway/app/security.py`
- `device-gateway/tests/compat/`

**Web App (Vue 3)**

- `web/src/components/AssignRoleDialog.vue`
- `web/src/components/CameraTree.vue`
- `web/src/i18n/en.json`
- `web/src/i18n/vi.json`
- `web/src/layouts/AppLayout.vue`
- `web/src/services/http.ts`
- `web/src/views/CamerasView.vue`
- `web/src/views/LoginView.vue`
- `web/src/views/UsersView.vue`

**Kiểm thử**

- `tests/e2e/features/US-05_to_chuc_camera.feature`
- `tests/e2e/features/US-30_dang_nhap.feature`
- `tests/e2e/features/US-32_phan_quyen.feature`
- `tests/e2e/responsive.spec.ts`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 57 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 28 |
| FE – Frontend Vue (2 người) | 28 |
| QA – Kiểm thử (1 người) | 15 |
| **Tổng** | **128** |

## 7. Điều kiện vào

- [ ] Phase S0 đạt điều kiện ra
- [ ] PO duyệt màn hình login, users, cameras trong design/

## 8. Điều kiện ra

- [ ] Đăng nhập cục bộ, khóa tài khoản, phiên 12 giờ chạy trên staging
- [ ] Cây Địa điểm → Khu vực → Nhóm, chuyển camera ghi lịch sử
- [ ] Phân quyền theo phạm vi có hiệu lực ≤ 60 giây
- [ ] Khung i18n vi/en và layout responsive dùng chung
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Admin đăng nhập, tạo địa điểm Kho Bắc Ninh và 3 khu vực
2. Tạo Site Manager Hương, gán phạm vi Chi nhánh Hà Nội; Hương chỉ thấy địa điểm của mình
3. Đổi ngôn ngữ sang English

## 10. Rủi ro

- PermissionEvaluator là nền cho mọi API; sai thiết kế sẽ tốn công sửa ở các sprint sau: review kiến trúc trước khi merge

## 11. Phase tiếp theo

[Phase S2 – 2FA, nhật ký, thêm camera và trạng thái](phase-02-S2-them-camera-trang-thai.md)
