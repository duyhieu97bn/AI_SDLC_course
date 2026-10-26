# Phase S9 – Story Could, kiểm thử tải và bảo mật, UAT – GA

> Sprint **S9** · Mốc **M3 – GA** · 15/03/2027 – 26/03/2027 (2 tuần) · Kế hoạch **16 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Story Could, kiểm thử tải và bảo mật, sửa lỗi pilot, UAT.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| TS-08 | Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA | Enabler | EP-00 | Must | 8 | Mọi story M1, M2 |
| US-07 | Nhập camera từ file | Story | EP-01 | Could | 3 | US-02 |
| US-15 | Âm thanh hai chiều | Story | EP-02 | Could | 5 | US-08 |
| | **Tổng** | | | | **16** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-07, US-15, TS-08 (chuẩn bị, chạy tải) |
| Tuần 2 | TS-08 (kiểm thử xâm nhập, sửa lỗi), UAT, phê duyệt |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### TS-08 Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA (8 điểm)

*Là Đội kiểm thử, tôi muốn chạy kiểm thử tải 500 camera/100 người dùng và kiểm thử xâm nhập theo OWASP ASVS L2, để đủ điều kiện nghiệm thu GA (PNFR-08, NFR-03).*

Truy vết: yêu cầu NFR-01 đến NFR-05 · use case — · màn hình — · SADD: 4 Bảo mật; 6.1

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-08.1 | QA | Kịch bản Gatling cho API chính; giả lập 500 luồng RTSP | `tests/load/` | 14 |
| TS-08.2 | QA | Chạy kiểm thử tải 1 giờ, báo cáo NFR-01 đến NFR-04 | `docs/test/load-report.md` | 6 |
| TS-08.3 | OPS | Hỗ trợ kiểm thử xâm nhập (môi trường, tài khoản), diễn tập khôi phục lần 2 | `docs/runbook/restore-drill-02.md` | 8 |
| TS-08.4 | BE | Sửa lỗi bảo mật và hiệu năng phát hiện | `(theo báo cáo)` | 16 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đạt NFR dưới tải
- [ ] Không còn lỗi bảo mật nghiêm trọng

### US-07 Nhập camera từ file (3 điểm)

*Là Technician, tôi muốn nhập danh sách camera từ file, để triển khai địa điểm lớn nhanh hơn.*

Truy vết: yêu cầu BR-07 · use case UC-13 · màn hình cameras.html (Nhập từ file) · SADD: Core device

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-07.1 | BE | Nhập file .xlsx/.csv, xem trước kèm lỗi, báo cáo kết quả | `core/src/main/java/vn/camhub/device/application/CameraImportService.java` | 5 |
| US-07.2 | FE | Modal nhập từ file | `web/src/components/ImportCamerasDialog.vue` | 3 |
| US-07.3 | QA | Test bỏ qua dòng lỗi | `tests/e2e/features/US-07_nhap_file.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Bỏ qua dòng lỗi

### US-15 Âm thanh hai chiều (5 điểm)

*Là Operator, tôi muốn nghe và nói qua camera, để cảnh báo người tại hiện trường.*

Truy vết: yêu cầu BR-13 · use case UC-25 · màn hình live.html (Đàm thoại) · SADD: MediaMTX (âm thanh); Web WebRTC

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-15.1 | OPS | Bật luồng âm thanh hai chiều trên MediaMTX (backchannel) | `deploy/mediamtx/mediamtx.yml` | 4 |
| US-15.2 | FE | Nhấn giữ để nói (getUserMedia → WebRTC) | `web/src/components/TalkButton.vue` | 6 |
| US-15.3 | BE | Ghi TALK_AUDIO kèm thời lượng | `core/src/main/java/vn/camhub/liveview/application/TalkAuditService.java` | 2 |
| US-15.4 | QA | Test với camera có loa | `tests/e2e/features/US-15_dam_thoai.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Nhấn giữ để nói

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/device/application/CameraImportService.java`
- `core/src/main/java/vn/camhub/liveview/application/TalkAuditService.java`

**Web App (Vue 3)**

- `web/src/components/ImportCamerasDialog.vue`
- `web/src/components/TalkButton.vue`

**Triển khai (Docker Compose, cấu hình)**

- `deploy/mediamtx/mediamtx.yml`

**Kiểm thử**

- `tests/e2e/features/US-07_nhap_file.feature`
- `tests/e2e/features/US-15_dam_thoai.feature`
- `tests/load/`

**Tài liệu**

- `docs/runbook/restore-drill-02.md`
- `docs/test/load-report.md`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 23 |
| FE – Frontend Vue (2 người) | 9 |
| QA – Kiểm thử (1 người) | 23 |
| OPS – DevOps (bán thời gian, kiêm PY hoặc BE) | 12 |
| **Tổng** | **67** |

## 7. Điều kiện vào

- [ ] Beta (M2) được PO chấp nhận
- [ ] Pilot đang chạy, có kênh thu lỗi

## 8. Điều kiện ra

- [ ] Đạt tiêu chí nghiệm thu BRS mục 7 (toàn bộ BR-01 đến BR-40)
- [ ] NFR-01 đến NFR-04 đạt dưới tải 500 camera/100 người dùng
- [ ] Không còn lỗi bảo mật nghiêm trọng/cao
- [ ] Người dùng pilot đánh giá ≥ 4/5; ký phê duyệt (mốc M3 – GA)
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Trình bày báo cáo kiểm thử tải và kiểm thử xâm nhập
2. UAT theo các kịch bản SC-01 đến SC-04 với người dùng pilot

## 10. Rủi ro

- Chỉ còn khoảng 14 điểm dự phòng cho sửa lỗi pilot (BQ-02): đề xuất thêm S10 hoặc chuyển US-15 sang phiên bản sau
