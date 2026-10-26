# Phase S5 – Xuất clip, vùng, cảnh báo hệ thống, tổng quan, sao lưu – hoàn tất Alpha

> Sprint **S5** · Mốc **M1 – Alpha** · 04/01/2027 – 15/01/2027 (2 tuần) · Kế hoạch **31 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Xuất clip, vùng chuyển động, cảnh báo hệ thống, tổng quan, sao lưu.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-20 | Xuất clip | Story | EP-03 | Must | 8 | US-17 |
| US-26 | Vùng chuyển động và vùng che riêng tư | Story | EP-04 | Must | 8 | US-23 |
| US-34 | Cảnh báo mất kết nối | Story | EP-06 | Must | 5 | US-04 |
| US-35 | Cảnh báo dung lượng lưu trữ | Story | EP-06 | Must | 2 | US-16 |
| US-39 | Bảng tổng quan hệ thống | Story | EP-07 | Must | 3 | US-04, US-35 |
| US-40 | Sao lưu và khôi phục | Story | EP-07 | Must | 5 | TS-02 |
| | **Tổng** | | | | **31** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-20, US-34 |
| Tuần 2 | US-26, US-35, US-39, US-40 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-20 Xuất clip (8 điểm)

*Là Site Manager, tôi muốn xuất đoạn video ra file, để gửi cho bên liên quan.*

Truy vết: yêu cầu BR-18, RULE-01 · use case UC-33 · màn hình playback.html (Xuất clip) · SADD: Core export; RabbitMQ; Media Worker FFmpeg; MinIO

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-20.1 | BE | ExportJob + state machine; POST /exports (≤ 60 phút, EXPORT_VIDEO); phát ExportRequested | `core/src/main/java/vn/camhub/export/domain/ExportJob.java, core/src/main/java/vn/camhub/export/web/ExportController.java, core/src/main/resources/db/migration/V5_1__export.sql` | 8 |
| US-20.2 | PY | Media Worker: nhận việc, FFmpeg ghép/cắt/mã H.264, tải lên MinIO, phát kết quả | `media-worker/app/export_job.py` | 8 |
| US-20.3 | BE | Job hết hạn 24 giờ; URL MinIO ký 5 phút | `core/src/main/java/vn/camhub/export/application/ExportExpiryJob.java` | 3 |
| US-20.4 | FE | Modal xuất clip, danh sách clip đã xuất, thông báo WebSocket | `web/src/components/ExportDialog.vue` | 5 |
| US-20.5 | QA | Test 4 kịch bản | `tests/e2e/features/US-20_xuat_clip.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Xuất thành công
- [ ] Quá 60 phút
- [ ] Không có quyền
- [ ] Liên kết hết hạn

### US-26 Vùng chuyển động và vùng che riêng tư (8 điểm)

*Là Technician, tôi muốn vẽ vùng phát hiện chuyển động và vùng che, để giảm cảnh báo giả và bảo vệ quyền riêng tư.*

Truy vết: yêu cầu BR-24, RULE-03 · use case UC-40 · màn hình camera-detail.html (Vùng) · SADD: Core configuration (MotionZone, PrivacyMask); Device Gateway

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-26.1 | PY | Ghi vùng chuyển động (cell/polygon), độ nhạy, vùng che | `device-gateway/app/control/zones.py` | 8 |
| US-26.2 | BE | MotionZone, PrivacyMask; validate giới hạn số vùng theo capabilities | `core/src/main/java/vn/camhub/configuration/domain/MotionZone.java` | 4 |
| US-26.3 | FE | Công cụ vẽ vùng (lưới ô, đa giác) theo design/camera-detail.html | `web/src/components/ZoneEditor.vue` | 8 |
| US-26.4 | QA | Test vùng che trên video ghi, giới hạn số vùng | `tests/e2e/features/US-26_vung.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Vùng che trên video ghi
- [ ] Giới hạn camera

### US-34 Cảnh báo mất kết nối (5 điểm)

*Là Technician, tôi muốn được báo khi camera mất kết nối, để xử lý trước khi mất dữ liệu.*

Truy vết: yêu cầu BR-31 · use case UC-50, UC-51 · màn hình alerts.html · SADD: Core alerting (Bộ hẹn giờ); notification (Web, Email)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-34.1 | BE | AlertRule, Alert, AlertStatusChange; quy tắc CAMERA_OFFLINE/SITE_OFFLINE; tự RECOVERED; migration | `core/src/main/java/vn/camhub/alerting/domain/, core/src/main/resources/db/migration/V5_2__alerting.sql` | 8 |
| US-34.2 | BE | Notification web + email (SMTP), thử lại 3 lần | `core/src/main/java/vn/camhub/notification/application/NotificationDispatcher.java` | 5 |
| US-34.3 | FE | Danh sách cảnh báo cơ bản | `web/src/views/AlertsView.vue` | 4 |
| US-34.4 | QA | Test thời gian chờ, gộp địa điểm, tự phục hồi | `tests/e2e/features/US-34_mat_ket_noi.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Sau thời gian chờ
- [ ] Gộp theo địa điểm
- [ ] Tự phục hồi

### US-35 Cảnh báo dung lượng lưu trữ (2 điểm)

*Là Admin, tôi muốn được báo khi lưu trữ sắp đầy, để không bị dừng ghi.*

Truy vết: yêu cầu BR-37 · use case UC-50, UC-51 · màn hình alerts.html; system.html (Lưu trữ) · SADD: Core recording (StorageVolume); alerting

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-35.1 | BE | StorageVolume, job đo dung lượng, ước tính số ngày, quy tắc STORAGE_THRESHOLD | `core/src/main/java/vn/camhub/recording/application/StorageMonitorJob.java` | 4 |
| US-35.2 | QA | Test vượt ngưỡng | `tests/e2e/features/US-35_luu_tru_canh_bao.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Vượt ngưỡng

### US-39 Bảng tổng quan hệ thống (3 điểm)

*Là Admin, tôi muốn xem sức khỏe hệ thống, để phát hiện sớm vấn đề.*

Truy vết: yêu cầu NFR-04 · use case UC-60 · màn hình dashboard.html · SADD: Core platform; /system/overview

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-39.1 | BE | GET /system/overview tổng hợp từ các module | `core/src/main/java/vn/camhub/platform/web/SystemOverviewController.java` | 3 |
| US-39.2 | FE | Trang Tổng quan theo design/dashboard.html | `web/src/views/DashboardView.vue` | 5 |
| US-39.3 | QA | Test hiển thị chỉ số | `tests/e2e/features/US-39_tong_quan.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Hiển thị chỉ số

### US-40 Sao lưu và khôi phục (5 điểm)

*Là Đội vận hành IT, tôi muốn hệ thống sao lưu tự động và có runbook khôi phục, để khôi phục được khi có sự cố.*

Truy vết: yêu cầu NFR-10, NFR-11 · use case UC-61, UC-62 · màn hình system.html (Sao lưu, chỉ xem) · SADD: pgBackRest; SADD 3.12.3

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-40.1 | OPS | pgBackRest: bản đầy đủ hằng ngày, WAL liên tục, giữ 30 bản; sao chép MinIO hằng giờ | `deploy/backup/` | 8 |
| US-40.2 | OPS | Runbook khôi phục và diễn tập lần đầu | `docs/runbook/restore.md` | 6 |
| US-40.3 | BE | GET /system/backups (chỉ xem) | `core/src/main/java/vn/camhub/platform/web/BackupStatusController.java` | 2 |
| US-40.4 | FE | Tab Sao lưu chỉ xem | `web/src/views/system/BackupTab.vue` | 2 |
| US-40.5 | QA | Kiểm chứng RTO ≤ 4 giờ, RPO ≤ 1 giờ trong diễn tập | `docs/runbook/restore-drill-01.md` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Sao lưu định kỳ
- [ ] Diễn tập khôi phục
- [ ] Admin không khôi phục được

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/alerting/domain/`
- `core/src/main/java/vn/camhub/configuration/domain/MotionZone.java`
- `core/src/main/java/vn/camhub/export/application/ExportExpiryJob.java`
- `core/src/main/java/vn/camhub/export/domain/ExportJob.java`
- `core/src/main/java/vn/camhub/export/web/ExportController.java`
- `core/src/main/java/vn/camhub/notification/application/NotificationDispatcher.java`
- `core/src/main/java/vn/camhub/platform/web/BackupStatusController.java`
- `core/src/main/java/vn/camhub/platform/web/SystemOverviewController.java`
- `core/src/main/java/vn/camhub/recording/application/StorageMonitorJob.java`
- `core/src/main/resources/db/migration/V5_1__export.sql`
- `core/src/main/resources/db/migration/V5_2__alerting.sql`

**Device Gateway (Python)**

- `device-gateway/app/control/zones.py`

**Media Worker (Python + FFmpeg)**

- `media-worker/app/export_job.py`

**Web App (Vue 3)**

- `web/src/components/ExportDialog.vue`
- `web/src/components/ZoneEditor.vue`
- `web/src/views/AlertsView.vue`
- `web/src/views/DashboardView.vue`
- `web/src/views/system/BackupTab.vue`

**Triển khai (Docker Compose, cấu hình)**

- `deploy/backup/`

**Kiểm thử**

- `tests/e2e/features/US-20_xuat_clip.feature`
- `tests/e2e/features/US-26_vung.feature`
- `tests/e2e/features/US-34_mat_ket_noi.feature`
- `tests/e2e/features/US-35_luu_tru_canh_bao.feature`
- `tests/e2e/features/US-39_tong_quan.feature`

**Tài liệu**

- `docs/runbook/restore-drill-01.md`
- `docs/runbook/restore.md`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 37 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 16 |
| FE – Frontend Vue (2 người) | 24 |
| QA – Kiểm thử (1 người) | 15 |
| OPS – DevOps (bán thời gian, kiêm PY hoặc BE) | 14 |
| **Tổng** | **106** |

## 7. Điều kiện vào

- [ ] S4 đạt điều kiện ra

## 8. Điều kiện ra

- [ ] Toàn bộ 26 story Must chạy trên staging (mốc M1 – Alpha)
- [ ] Xuất clip MP4 qua Media Worker
- [ ] Cảnh báo mất kết nối và dung lượng gửi qua web, email
- [ ] Diễn tập khôi phục lần 1 đạt RTO ≤ 4 giờ
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Kịch bản SC-03: trích xuất bằng chứng từ 2 camera, xuất clip, xem nhật ký
2. Kịch bản SC-04: camera mất kết nối, cảnh báo, khởi động lại
3. Trang Tổng quan

## 10. Rủi ro

- Sprint đã ở 31/30 điểm: nếu trễ, chuyển US-39 sang S6 nhưng vẫn tính là Must của Alpha

## 11. Phase tiếp theo

[Phase S6 – Bộ kết nối địa điểm, đầu ghi, chất lượng luồng](phase-06-S6-ket-noi-dia-diem.md)
