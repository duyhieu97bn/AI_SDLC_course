# Phase S3 – Xem trực tiếp, bố cục, PTZ

> Sprint **S3** · Mốc **M1 – Alpha** · 07/12/2026 – 18/12/2026 (2 tuần) · Kế hoạch **29 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Xem trực tiếp: lưới, bố cục, PTZ, preset.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| US-08 | Xem trực tiếp trên trình duyệt | Story | EP-02 | Must | 8 | TS-03, US-02, US-32 |
| US-09 | Lưới nhiều camera | Story | EP-02 | Must | 5 | US-08 |
| US-10 | Lưu bố cục xem | Story | EP-02 | Must | 3 | US-09 |
| US-12 | Điều khiển PTZ | Story | EP-02 | Must | 5 | US-08 |
| US-13 | Preset PTZ | Story | EP-02 | Must | 3 | US-12 |
| US-29 | Chỉ hiển thị tham số được hỗ trợ | Story | EP-04 | Must | 5 | TS-05, US-02 |
| | **Tổng** | | | | **29** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | US-08, US-29, bắt đầu US-09 |
| Tuần 2 | US-09, US-10, US-12, US-13 |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### US-08 Xem trực tiếp trên trình duyệt (8 điểm)

*Là Operator, tôi muốn xem camera trên trình duyệt mà không cài gì, để làm việc từ bất kỳ máy nào.*

Truy vết: yêu cầu BR-08 · use case UC-20 · màn hình live.html · SADD: Core liveview; MediaMTX WHEP/HLS; /internal/media/authorize

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-08.1 | BE | POST /cameras/{id}/live-session: kiểm quyền, cấp token HMAC 60 giây; /internal/media/authorize hoàn chỉnh | `core/src/main/java/vn/camhub/liveview/application/LiveSessionService.java` | 8 |
| US-08.2 | FE | Hoàn thiện WhepPlayer, tự chuyển HLS khi WebRTC thất bại | `web/src/player/WhepPlayer.ts` | 6 |
| US-08.3 | OPS | Cấu hình chuyển mã theo yêu cầu ({camera}_h264), giới hạn số phiên | `deploy/mediamtx/mediamtx.yml` | 4 |
| US-08.4 | QA | Ma trận 4 trình duyệt, test không có quyền, test HLS dự phòng | `tests/e2e/features/US-08_xem_truc_tiep.feature` | 6 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Phát nhanh
- [ ] Không có quyền
- [ ] Mạng chặn WebRTC

### US-09 Lưới nhiều camera (5 điểm)

*Là Operator, tôi muốn xem nhiều camera cùng lúc, để giám sát toàn khu vực.*

Truy vết: yêu cầu BR-09 · use case UC-20 · màn hình live.html · SADD: Web player

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-09.1 | FE | Lưới 1/4/9/16, kéo thả từ cây, nhấp đúp phóng to, luồng phụ cho lưới lớn | `web/src/views/LiveView.vue, web/src/components/VideoGrid.vue, web/src/components/VideoTile.vue` | 10 |
| US-09.2 | QA | Test 3 kịch bản | `tests/e2e/features/US-09_luoi.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đổi lưới
- [ ] Phóng to một ô
- [ ] Camera mất kết nối trong lưới

### US-10 Lưu bố cục xem (3 điểm)

*Là Operator, tôi muốn lưu bố cục và danh sách camera đang xem, để mở lại ngay ở ca sau.*

Truy vết: yêu cầu BR-34 · use case UC-21 · màn hình live.html (Lưu bố cục) · SADD: Core liveview (ViewLayout)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-10.1 | BE | ViewLayout, LayoutTile; API CRUD; loại camera mất quyền (INV-13); migration | `core/src/main/java/vn/camhub/liveview/domain/ViewLayout.java, core/src/main/resources/db/migration/V3_1__liveview.sql` | 5 |
| US-10.2 | FE | Lưu/mở bố cục, mở bố cục gần nhất khi đăng nhập | `web/src/stores/layouts.ts` | 3 |
| US-10.3 | QA | Test 2 kịch bản | `tests/e2e/features/US-10_bo_cuc.feature` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Mở lại bố cục
- [ ] Quyền bị thu hồi

### US-12 Điều khiển PTZ (5 điểm)

*Là Operator, tôi muốn xoay, nghiêng, zoom camera, để theo dõi đối tượng di chuyển.*

Truy vết: yêu cầu BR-11 · use case UC-22 · màn hình live.html (PTZ) · SADD: Device Gateway control; Redis khóa PTZ (3.11.4)

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-12.1 | PY | Lệnh PTZ ContinuousMove/Stop/Zoom | `device-gateway/app/control/ptz.py` | 5 |
| US-12.2 | BE | API PTZ, khóa Redis theo Role.rank (Lua script), thông báo WebSocket khi mất khóa | `core/src/main/java/vn/camhub/liveview/application/PtzLockService.java` | 6 |
| US-12.3 | FE | Bảng PTZ, kéo chuột trên khung hình, phím mũi tên | `web/src/components/PtzPanel.vue` | 5 |
| US-12.4 | QA | Test tranh chấp điều khiển | `tests/e2e/features/US-12_ptz.feature` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Chỉ hiện với camera PTZ
- [ ] Tranh chấp điều khiển

### US-13 Preset PTZ (3 điểm)

*Là Operator, tôi muốn gọi nhanh vị trí đặt sẵn, để chuyển đến điểm cần xem trong 1 thao tác.*

Truy vết: yêu cầu BR-35 · use case UC-23 · màn hình live.html (Preset) · SADD: Core device (PtzPreset); Device Gateway

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-13.1 | PY | GetPresets/SetPreset/GotoPreset | `device-gateway/app/control/ptz.py` | 3 |
| US-13.2 | BE | PtzPreset (source DEVICE/CAMHUB), API | `core/src/main/java/vn/camhub/device/domain/PtzPreset.java` | 3 |
| US-13.3 | FE | Danh sách preset, lưu vị trí | `web/src/components/PtzPanel.vue` | 2 |
| US-13.4 | QA | Test lưu và gọi preset | `tests/e2e/features/US-13_preset.feature` | 1 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Lưu và gọi preset

### US-29 Chỉ hiển thị tham số được hỗ trợ (5 điểm)

*Là Technician, tôi muốn chỉ thấy tùy chọn camera thực sự hỗ trợ, để không cấu hình sai.*

Truy vết: yêu cầu BR-27 · use case UC-40 · màn hình camera-detail.html · SADD: Device Gateway capabilities; DeviceCapabilities

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| US-29.1 | PY | Chuẩn hóa options của camera thành DeviceCapabilities (codec, độ phân giải, fps, bitrate, imaging, số vùng che) | `device-gateway/app/capabilities/normalizer.py` | 6 |
| US-29.2 | BE | Lưu và trả capabilities; validate thay đổi theo capabilities | `core/src/main/java/vn/camhub/configuration/application/CapabilityValidator.java` | 4 |
| US-29.3 | QA | Test với 5 hãng từ bộ kiểm thử tương thích | `device-gateway/tests/compat/test_capabilities.py` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Ẩn tùy chọn không hỗ trợ

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/src/main/java/vn/camhub/configuration/application/CapabilityValidator.java`
- `core/src/main/java/vn/camhub/device/domain/PtzPreset.java`
- `core/src/main/java/vn/camhub/liveview/application/LiveSessionService.java`
- `core/src/main/java/vn/camhub/liveview/application/PtzLockService.java`
- `core/src/main/java/vn/camhub/liveview/domain/ViewLayout.java`
- `core/src/main/resources/db/migration/V3_1__liveview.sql`

**Device Gateway (Python)**

- `device-gateway/app/capabilities/normalizer.py`
- `device-gateway/app/control/ptz.py`
- `device-gateway/tests/compat/test_capabilities.py`

**Web App (Vue 3)**

- `web/src/components/PtzPanel.vue`
- `web/src/components/VideoGrid.vue`
- `web/src/components/VideoTile.vue`
- `web/src/player/WhepPlayer.ts`
- `web/src/stores/layouts.ts`
- `web/src/views/LiveView.vue`

**Triển khai (Docker Compose, cấu hình)**

- `deploy/mediamtx/mediamtx.yml`

**Kiểm thử**

- `tests/e2e/features/US-08_xem_truc_tiep.feature`
- `tests/e2e/features/US-09_luoi.feature`
- `tests/e2e/features/US-10_bo_cuc.feature`
- `tests/e2e/features/US-12_ptz.feature`
- `tests/e2e/features/US-13_preset.feature`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 26 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 14 |
| FE – Frontend Vue (2 người) | 26 |
| QA – Kiểm thử (1 người) | 18 |
| OPS – DevOps (bán thời gian, kiêm PY hoặc BE) | 4 |
| **Tổng** | **88** |

## 7. Điều kiện vào

- [ ] S2 đạt điều kiện ra
- [ ] Kết luận PoC MediaMTX đã áp dụng vào cấu hình

## 8. Điều kiện ra

- [ ] Xem trực tiếp trên 4 trình duyệt ≤ 3 giây, HLS dự phòng
- [ ] Lưới 1/4/9/16, bố cục lưu theo người dùng
- [ ] PTZ, preset, khóa điều khiển theo thứ hạng
- [ ] Tùy chọn cấu hình chỉ theo capabilities
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Operator mở bố cục Ca đêm, lưới 9, nhấp đúp phóng to
2. Hai người cùng điều khiển PTZ, người thứ hạng thấp bị từ chối
3. Viewer không có quyền bị chặn ở API (403)

## 10. Rủi ro

- Safari iOS hạn chế WebRTC/codec: ưu tiên kiểm thử sớm trong sprint

## 11. Phase tiếp theo

[Phase S4 – Ghi hình, timeline, lưu trữ, cấu hình luồng](phase-04-S4-ghi-hinh-xem-lai.md)
