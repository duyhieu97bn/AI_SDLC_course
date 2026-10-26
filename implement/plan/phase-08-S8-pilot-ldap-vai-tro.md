# Phase S8 – Đồng bộ nhiều camera, cấu hình mẫu, vai trò tùy chỉnh, LDAP/AD – pilot

> Sprint **S8** · Mốc **M2 – Beta pilot** · 01/03/2027 – 12/03/2027 (2 tuần) · Kế hoạch **26 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Đồng bộ nhiều camera, cấu hình mẫu, vai trò tùy chỉnh, LDAP/AD.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-19 | Xem lại đồng bộ nhiều camera | Story | EP-03 | Should | 5 | US-17 |
| US-25 | Cấu hình mạng và thời gian | Story | EP-04 | Should | 3 | US-23 |
| US-27 | Cấu hình mẫu | Story | EP-04 | Should | 5 | US-23, US-24, US-16 |
| US-43 | Vai trò tùy chỉnh | Story | EP-05 | Should | 5 | US-32 |
| US-44 | Đăng nhập bằng LDAP/Active Directory | Story | EP-05 | Should | 8 | US-30, US-32 |
| | **Tổng** | | | | **26** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-44, US-43 |
| Tuần 2 | US-19, US-25, US-27; triển khai pilot |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-19 Xem lại đồng bộ nhiều camera (5 điểm)

*Là Operator, tôi muốn phát lại nhiều camera cùng mốc thời gian, để theo dấu một người qua nhiều góc quay.*

Truy vết: yêu cầu BR-17 · use case UC-32 · màn hình playback.html (Xem đồng bộ) · SADD: Web player

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-19.1 | FE | Chế độ đồng bộ tối đa 4 camera, timeline chung, bù lệch ≤ 1 giây | `web/src/views/PlaybackView.vue, web/src/player/SyncController.ts` | 8 |
| US-19.2 | QA | Test đồng bộ và giới hạn 4 | `tests/e2e/features/US-19_dong_bo.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đồng bộ 4 camera
- [ ] Giới hạn 4 camera

### US-25 Cấu hình mạng và thời gian (3 điểm)

*Là Technician, tôi muốn đặt IP, NTP, múi giờ cho camera, để thời gian video chính xác.*

Truy vết: yêu cầu BR-23 · use case UC-40 · màn hình camera-detail.html (Mạng & thời gian) · SADD: Core configuration; Device Gateway control

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-25.1 | PY | SetNetworkInterfaces, SetNTP, SetSystemDateAndTime | `device-gateway/app/control/network.py` | 3 |
| US-25.2 | BE | Cập nhật địa chỉ sau khi đổi IP; đánh dấu lệch giờ > 5 giây | `core/src/main/java/vn/camhub/configuration/application/NetworkConfigService.java` | 3 |
| US-25.3 | FE | Tab Mạng & thời gian, cảnh báo đổi IP | `web/src/views/camera/NetworkTab.vue` | 2 |
| US-25.4 | QA | Test 2 kịch bản | `tests/e2e/features/US-25_mang.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đổi IP
- [ ] Lệch giờ

### US-27 Cấu hình mẫu (5 điểm)

*Là Technician, tôi muốn tạo mẫu cấu hình và áp cho nhiều camera, để cấu hình hàng loạt trong một lần.*

Truy vết: yêu cầu BR-25 · use case UC-41 · màn hình templates.html · SADD: Core configuration (ConfigTemplate, TemplateApplication)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-27.1 | BE | ConfigTemplate, TemplateApplication; áp song song, kết quả SUCCESS/PARTIAL/FAILED; migration | `core/src/main/java/vn/camhub/configuration/application/TemplateApplyService.java, core/src/main/resources/db/migration/V8_1__templates.sql` | 8 |
| US-27.2 | FE | Trang Cấu hình mẫu theo design/templates.html | `web/src/views/TemplatesView.vue` | 5 |
| US-27.3 | QA | Test áp mẫu 4 camera | `tests/e2e/features/US-27_cau_hinh_mau.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Áp mẫu cho 4 camera

### US-43 Vai trò tùy chỉnh (5 điểm)

*Là Admin, tôi muốn tạo vai trò riêng từ tập quyền, để phù hợp với vị trí công việc của tổ chức.*

Truy vết: yêu cầu BR-39 · use case UC-04 · màn hình users.html (Vai trò) · SADD: Core access (Role, MANAGE_ROLES)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-43.1 | BE | Tạo/sao chép/sửa/xóa vai trò tùy chỉnh, MANAGE_ROLES, INV-20 đến INV-22 | `core/src/main/java/vn/camhub/access/application/RoleService.java` | 6 |
| US-43.2 | FE | Tab Vai trò, bảng quyền | `web/src/views/users/RolesTab.vue` | 5 |
| US-43.3 | QA | Test 3 kịch bản | `tests/e2e/features/US-43_vai_tro.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Tạo từ vai trò có sẵn
- [ ] Không xóa vai trò đang dùng
- [ ] Vai trò mặc định

### US-44 Đăng nhập bằng LDAP/Active Directory (8 điểm)

*Là Admin, tôi muốn người dùng đăng nhập bằng tài khoản của tổ chức, để quản lý tài khoản tập trung và vô hiệu hóa ngay khi nghỉ việc.*

Truy vết: yêu cầu BR-40, RULE-05 · use case UC-01, UC-06, UC-07 · màn hình users.html (LDAP); login.html · SADD: Core identity (DirectoryConnection, DirectoryGroupMapping); LDAPS

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-44.1 | BE | DirectoryConnection, DirectoryGroupMapping; LDAPS bind (Spring Security LDAP); migration | `core/src/main/java/vn/camhub/identity/infrastructure/LdapAuthenticator.java, core/src/main/resources/db/migration/V8_2__directory.sql` | 8 |
| US-44.2 | BE | Job đồng bộ mỗi giờ: tạo/cập nhật/vô hiệu hóa người dùng, cập nhật RoleAssignment (INV-26) | `core/src/main/java/vn/camhub/identity/application/DirectorySyncJob.java` | 6 |
| US-44.3 | FE | Tab LDAP/AD, kiểm tra kết nối, ánh xạ nhóm | `web/src/views/users/LdapTab.vue` | 5 |
| US-44.4 | QA | Test với AD thử nghiệm (Samba AD hoặc OpenLDAP) | `tests/e2e/features/US-44_ldap.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đăng nhập lần đầu
- [ ] Nghỉ việc
- [ ] Máy chủ thư mục lỗi

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/access/application/RoleService.java`
- `core/src/main/java/vn/camhub/configuration/application/NetworkConfigService.java`
- `core/src/main/java/vn/camhub/configuration/application/TemplateApplyService.java`
- `core/src/main/java/vn/camhub/identity/application/DirectorySyncJob.java`
- `core/src/main/java/vn/camhub/identity/infrastructure/LdapAuthenticator.java`
- `core/src/main/resources/db/migration/V8_1__templates.sql`
- `core/src/main/resources/db/migration/V8_2__directory.sql`

**Device Gateway (Python)**

- `device-gateway/app/control/network.py`

**Web App (Vue 3)**

- `web/src/player/SyncController.ts`
- `web/src/views/PlaybackView.vue`
- `web/src/views/TemplatesView.vue`
- `web/src/views/camera/NetworkTab.vue`
- `web/src/views/users/LdapTab.vue`
- `web/src/views/users/RolesTab.vue`

**Kiểm thử**

- `tests/e2e/features/US-19_dong_bo.feature`
- `tests/e2e/features/US-25_mang.feature`
- `tests/e2e/features/US-27_cau_hinh_mau.feature`
- `tests/e2e/features/US-43_vai_tro.feature`
- `tests/e2e/features/US-44_ldap.feature`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 31 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 3 |
| FE – Frontend Vue (2 người) | 25 |
| QA – Kiểm thử (1 người) | 11 |
| **Tổng** | **70** |

## 7. Điều kiện vào

- [ ] S7 đạt điều kiện ra
- [ ] IT cấp tài khoản dịch vụ LDAP chỉ đọc (D-06)

## 8. Điều kiện ra

- [ ] Toàn bộ story Should chạy trên staging (mốc M2 – Beta)
- [ ] Pilot 20–50 camera tại 1 địa điểm bắt đầu chạy ổn định
- [ ] Đăng nhập AD và ánh xạ nhóm
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Kịch bản SC-01: lắp địa điểm mới, áp cấu hình mẫu cho 12 camera
2. Nhân viên đăng nhập bằng tài khoản AD; vô hiệu hóa trong AD làm mất quyền ở lần đồng bộ kế tiếp

## 10. Rủi ro

- Pilot cần chạy ổn định 2 tuần (PRD M2) nhưng S9 chỉ có 2 tuần: theo dõi pilot song song với S9

## 11. Phase tiếp theo

[Phase S9 – Story Could, kiểm thử tải và bảo mật, UAT – GA](phase-09-S9-kiem-thu-ga.md)
