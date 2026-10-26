# Phase S4 – Ghi hình, timeline, lưu trữ, cấu hình luồng

> Sprint **S4** · Mốc **M1 – Alpha** · 21/12/2026 – 01/01/2027 (2 tuần) · Kế hoạch **29 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Ghi hình, timeline, phát lại, thời gian lưu trữ; cấu hình luồng.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-16 | Chế độ ghi hình | Story | EP-03 | Must | 5 | TS-03, US-02 |
| US-17 | Xem lại theo dòng thời gian | Story | EP-03 | Must | 8 | US-16 |
| US-18 | Điều khiển phát lại | Story | EP-03 | Must | 3 | US-17 |
| US-21 | Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa | Story | EP-03 | Must | 8 | US-16 |
| US-23 | Cấu hình luồng video | Story | EP-04 | Must | 5 | US-29 |
| | **Tổng** | | | | **29** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-16, US-23, US-21 (domain) |
| Tuần 2 | US-17, US-18, US-21 (job + giao diện) |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-16 Chế độ ghi hình (5 điểm)

*Là Admin, tôi muốn đặt chế độ ghi cho từng camera, để cân bằng lưu trữ và nhu cầu bằng chứng.*

Truy vết: yêu cầu BR-14 · use case UC-30 · màn hình camera-detail.html (Ghi hình) · SADD: Core recording; MediaMTX record API

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-16.1 | BE | RecordingPolicy, WeeklySchedule; điều khiển ghi trên MediaMTX theo lịch/sự kiện; migration | `core/src/main/java/vn/camhub/recording/domain/RecordingPolicy.java, core/src/main/resources/db/migration/V4_1__recording.sql` | 8 |
| US-16.2 | FE | Tab Ghi hình: chế độ, trước/sau sự kiện, lịch tuần | `web/src/views/camera/RecordingTab.vue` | 5 |
| US-16.3 | QA | Test ghi theo sự kiện, áp cho nhiều camera | `tests/e2e/features/US-16_che_do_ghi.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Ghi theo sự kiện
- [ ] Áp cho nhiều camera

### US-17 Xem lại theo dòng thời gian (8 điểm)

*Là Operator, tôi muốn chọn ngày giờ trên timeline, để tìm đúng đoạn cần xem.*

Truy vết: yêu cầu BR-15 · use case UC-31 · màn hình playback.html · SADD: Core recording (recording_segment); MediaMTX playback

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-17.1 | BE | Nhận hook đoạn ghi → recording_segment (phân vùng tháng); API timeline; ghi VIEW_PLAYBACK | `core/src/main/java/vn/camhub/recording/web/TimelineController.java, core/src/main/java/vn/camhub/recording/application/SegmentIndexer.java` | 8 |
| US-17.2 | FE | Timeline thu phóng 24 giờ – 10 phút, lịch có dữ liệu, nhảy tới đoạn gần nhất | `web/src/components/Timeline.vue, web/src/views/PlaybackView.vue` | 10 |
| US-17.3 | QA | Test phát ≤ 3 giây, không có dữ liệu, hiển thị timeline | `tests/e2e/features/US-17_timeline.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Phát lại nhanh
- [ ] Không có dữ liệu
- [ ] Hiển thị timeline

### US-18 Điều khiển phát lại (3 điểm)

*Là Operator, tôi muốn tua nhanh và xem từng khung hình, để rà video dài nhanh hơn.*

Truy vết: yêu cầu BR-16 · use case UC-31 · màn hình playback.html · SADD: Web player

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-18.1 | FE | Tốc độ 0,5x–16x, từng khung hình, phím tắt | `web/src/player/PlaybackControls.vue` | 5 |
| US-18.2 | QA | Test phím tắt | `tests/e2e/features/US-18_dieu_khien_phat.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Tua và phím tắt

### US-21 Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa (8 điểm)

*Là Admin, tôi muốn đặt thời gian lưu và giữ lại đoạn cần điều tra, để kiểm soát dung lượng và tuân thủ quy định.*

Truy vết: yêu cầu BR-19, RULE-02 · use case UC-34, UC-35, UC-36 · màn hình system.html (Lưu trữ); camera-detail.html; playback.html (Giữ lại) · SADD: Core recording (RetentionPolicy, EvidenceHold); SADD 3.11.2

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-21.1 | BE | RetentionPolicy, CameraRetentionOverride, EvidenceHold; migration | `core/src/main/java/vn/camhub/recording/domain/, core/src/main/resources/db/migration/V4_2__retention.sql` | 5 |
| US-21.2 | BE | Job xóa 02:00 theo SADD 3.11.2 (bỏ qua đoạn giữ lại, xóa lô 500) | `core/src/main/java/vn/camhub/recording/application/RetentionPurgeJob.java` | 6 |
| US-21.3 | FE | Cài đặt lưu trữ mặc định, ghi đè theo camera, modal giữ lại | `web/src/views/system/StorageTab.vue, web/src/components/EvidenceHoldDialog.vue` | 5 |
| US-21.4 | QA | Test ghi đè, giữ lại, quyền HOLD_EVIDENCE | `tests/e2e/features/US-21_luu_tru.feature` | 4 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Ghi đè theo camera
- [ ] Giữ lại phục vụ điều tra
- [ ] Quyền giữ lại

### US-23 Cấu hình luồng video (5 điểm)

*Là Technician, tôi muốn chỉnh độ phân giải, fps, bitrate, chuẩn nén từ CamHub, để không phải vào giao diện của từng camera.*

Truy vết: yêu cầu BR-21 · use case UC-40 · màn hình camera-detail.html (Luồng video) · SADD: Core configuration; Device Gateway control

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-23.1 | PY | Ghi cấu hình encoder (SetVideoEncoderConfiguration), trả lỗi theo tham số | `device-gateway/app/control/encoder.py` | 5 |
| US-23.2 | BE | API GET/PUT /cameras/{id}/config/stream, kết quả từng tham số, CHANGE_CONFIG | `core/src/main/java/vn/camhub/configuration/web/CameraConfigController.java` | 5 |
| US-23.3 | FE | Tab Luồng video và bảng kết quả áp dụng | `web/src/views/camera/StreamTab.vue` | 4 |
| US-23.4 | QA | Test áp dụng thành công và một phần | `tests/e2e/features/US-23_luong_video.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Áp dụng thành công
- [ ] Áp dụng một phần

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/configuration/web/CameraConfigController.java`
- `core/src/main/java/vn/camhub/recording/application/RetentionPurgeJob.java`
- `core/src/main/java/vn/camhub/recording/application/SegmentIndexer.java`
- `core/src/main/java/vn/camhub/recording/domain/`
- `core/src/main/java/vn/camhub/recording/domain/RecordingPolicy.java`
- `core/src/main/java/vn/camhub/recording/web/TimelineController.java`
- `core/src/main/resources/db/migration/V4_1__recording.sql`
- `core/src/main/resources/db/migration/V4_2__retention.sql`

**Device Gateway (Python)**

- `device-gateway/app/control/encoder.py`

**Web App (Vue 3)**

- `web/src/components/EvidenceHoldDialog.vue`
- `web/src/components/Timeline.vue`
- `web/src/player/PlaybackControls.vue`
- `web/src/views/PlaybackView.vue`
- `web/src/views/camera/RecordingTab.vue`
- `web/src/views/camera/StreamTab.vue`
- `web/src/views/system/StorageTab.vue`

**Kiểm thử**

- `tests/e2e/features/US-16_che_do_ghi.feature`
- `tests/e2e/features/US-17_timeline.feature`
- `tests/e2e/features/US-18_dieu_khien_phat.feature`
- `tests/e2e/features/US-21_luu_tru.feature`
- `tests/e2e/features/US-23_luong_video.feature`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 32 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 5 |
| FE – Frontend Vue (2 người) | 29 |
| QA – Kiểm thử (1 người) | 15 |
| **Tổng** | **81** |

## 7. Điều kiện vào

- [ ] S3 đạt điều kiện ra

## 8. Điều kiện ra

- [ ] Ghi liên tục/lịch/sự kiện
- [ ] Timeline và phát lại ≤ 3 giây
- [ ] Job xóa quá hạn chạy đúng và bỏ qua đoạn giữ lại
- [ ] Cấu hình luồng video trả kết quả theo từng tham số
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Đặt ghi theo sự kiện, tạo chuyển động, xem lại đúng đoạn có ghi trước 10 giây
2. Đánh dấu giữ lại một đoạn, chạy job xóa với thời gian lưu 1 ngày, đoạn giữ lại còn nguyên

## 10. Rủi ro

- Nghỉ Tết Dương lịch 01/01/2027 làm giảm khoảng 1 ngày công
- Thời gian lưu mặc định chưa chốt (OQ-04): dùng 30 ngày làm cấu hình

## 11. Phase tiếp theo

[Phase S5 – Xuất clip, vùng, cảnh báo hệ thống, tổng quan, sao lưu – hoàn tất Alpha](phase-05-S5-xuat-clip-canh-bao-alpha.md)
