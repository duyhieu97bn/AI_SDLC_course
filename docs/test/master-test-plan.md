# CamHub – Kế hoạch kiểm thử tổng thể (Master Test Plan)

| Thuộc tính | Giá trị |
| --- | --- |
| Mã tài liệu | CAMHUB-MTP-v1.0 |
| Phiên bản | 1.0 |
| Ngày phát hành | 27/10/2026 |
| Trạng thái | Bản nháp – chờ review |
| Phân loại | Nội bộ |
| Người soạn | Đội kiểm thử (QA) |
| Người phê duyệt | QA Lead, Product Owner, Sponsor (mục 16) |
| Chuẩn áp dụng | IEEE 829-2008 (Software and System Test Documentation) |

Tài liệu gồm hai phần:

- **Phần A (mục 0)** phân tích tài liệu đầu vào: yêu cầu, thiết kế, giao diện, API, user story và rủi ro. Đây là cơ sở cho mọi ca kiểm thử.
- **Phần B (mục 1–16)** là kế hoạch kiểm thử theo 16 mục của IEEE 829-2008.

Ma trận truy vết từ yêu cầu đến ca kiểm thử ở mục 6.8.

**Quy ước.**

- Mã yêu cầu giữ nguyên như tài liệu nguồn: BR-xx, NFR-xx, PNFR-xx, RULE-xx, US-xx, TS-xx, UC-xx, INV-xx.
- Mã ca kiểm thử mới đặt trong tài liệu này: INT-API-xx, E2E-xx, UAT-xx, PERF-xx, SEC-xx, ACC-xx, UI-xx, API-xx, REG-xx.
- Trạng thái ca kiểm thử:

| Trạng thái | Nghĩa |
| --- | --- |
| **Tự động – đạt** | Có test tự động trong repo và lần chạy gần nhất đạt |
| **Đạt (giả lập)** | Đạt với bộ giả lập; chưa thử trên thiết bị thật |
| **Kế hoạch** | Chưa có |
| **Chặn** | Thiếu điều kiện (môi trường, thiết bị, bên thứ ba) |

---

## Mục lục

- Phần A – Phân tích tài liệu đầu vào
  - 0.1 Danh mục tài liệu đầu vào
  - 0.2 Yêu cầu từ BRS/PRD
  - 0.3 Thành phần thiết kế từ SADD
  - 0.4 Thành phần giao diện
  - 0.5 Thành phần API
  - 0.6 User story và tiêu chí chấp nhận
  - 0.7 Đánh giá rủi ro
- Phần B – Kế hoạch kiểm thử (IEEE 829-2008)
  1. Định danh kế hoạch
  2. Giới thiệu
  3. Đối tượng kiểm thử
  4. Tính năng được kiểm thử
  5. Tính năng không kiểm thử
  6. Cách tiếp cận (gồm ca kiểm thử từng mức, chiến lược thực thi, quản lý lỗi, ma trận truy vết)
  7. Tiêu chí đạt/không đạt
  8. Tiêu chí tạm dừng và tiếp tục
  9. Sản phẩm bàn giao
  10. Công việc kiểm thử
  11. Nhu cầu môi trường
  12. Trách nhiệm
  13. Nhân sự và đào tạo
  14. Lịch trình
  15. Rủi ro và dự phòng
  16. Phê duyệt
- Phụ lục A – Bất nhất phát hiện khi phân tích tài liệu
- Phụ lục B – Lệnh chạy bộ kiểm thử

---

# Phần A – Phân tích tài liệu đầu vào

## 0.1 Danh mục tài liệu đầu vào

| Loại | Tài liệu | Phiên bản | Nguồn | Thông tin chính dùng cho kiểm thử |
| --- | --- | --- | --- | --- |
| **SRS (BRS)** | [requirements/BRD_IP_Camera_Web.md](../../requirements/BRD_IP_Camera_Web.md) | 0.6, 21/10/2026 | Business Analyst | 40 yêu cầu nghiệp vụ BR-01..40; 12 yêu cầu phi chức năng NFR-01..12, mỗi yêu cầu có phương pháp kiểm chứng T/A/I/D; quy tắc RULE-01..07; tiêu chí nghiệm thu §7; rủi ro R-01..05 |
| **PRD** | [requirements/PRD_IP_Camera_Web.md](../../requirements/PRD_IP_Camera_Web.md) | 0.4, 21/10/2026 | Product Owner / BA | 7 epic, 44 user story kèm tiêu chí chấp nhận định lượng; PNFR-01..09; mốc M1–M3; hành trình HT-01..04; KPI BO-01..05 |
| **Use case** | [requirements/Use_Case_IP_Camera_Web.md](../../requirements/Use_Case_IP_Camera_Web.md) | 0.2, 21/10/2026 | BA | 39 use case UC-01..63; tác nhân, quyền; luồng chính và ngoại lệ của 11 UC cốt lõi |
| **Mô hình miền** | [requirements/Class_Diagram_IP_Camera_Web.md](../../requirements/Class_Diagram_IP_Camera_Web.md) | 0.4, 21/10/2026 | BA | Thực thể, enum, 28 bất biến INV-01..28. Bất biến là oracle trực tiếp cho kiểm thử |
| **SAD (IEEE 1016-2009)** | [requirements/SADD_IP_Camera_Web.md](../../requirements/SADD_IP_Camera_Web.md) | 0.1, 21/10/2026 | Solution Architect | Các view kiến trúc; API §3.7; luồng §3.9; máy trạng thái §3.10; thuật toán §3.11; triển khai §3.12; bảo mật §4; ADR-01..11; truy vết NFR → kiểm chứng §6.1; rủi ro AR-01..06 |
| **Thiết kế UI** | [design/*.html](../../design/index.html) | 21/10/2026 | Thiết kế | 11 màn hình HTML tĩnh (đăng nhập, tổng quan, camera, chi tiết camera, xem trực tiếp, xem lại, cảnh báo, mẫu cấu hình, người dùng, nhật ký, hệ thống) |
| **Đặc tả API** | Mã nguồn controller; OpenAPI sinh tự động (springdoc, SADD §3.7) | Theo mã nguồn 27/10/2026 | Đội phát triển | Core: 153 endpoint REST và 6 kênh STOMP. Device Gateway: 37 endpoint. Nginx: 14 location |
| **User story** | [implement/User_Stories_IP_Camera_Web.md](../../implement/User_Stories_IP_Camera_Web.md) | 27/10/2026 | Product Owner | 44 US và 8 enabler TS-01..08, 274 điểm; kịch bản Gherkin trong [tests/e2e/features/](../../tests/e2e/features/) (52 tệp, 105 kịch bản) |
| **Kế hoạch phát triển** | [implement/plan/](../../implement/plan/README.md) | 27/10/2026 | Đội phát triển | Phase S0–S9 (sprint 2 tuần); cổng G0–G3; trạng thái thực tế trong `status-S*.md` |
| **Bằng chứng kiểm thử đã có** | [docs/poc/mediamtx-report.md](../poc/mediamtx-report.md), [docs/compat/report.md](../compat/report.md), [security-review.md](security-review.md), [restore-drill-01](../runbook/restore-drill-01.md) và [restore-drill-02](../runbook/restore-drill-02.md) | 23–27/10/2026 | Đội phát triển | Kết quả PoC first frame; tương thích ONVIF; tự rà soát bảo mật S-01..07; diễn tập khôi phục |

## 0.2 Yêu cầu từ BRS/PRD

### 0.2.1 Yêu cầu chức năng (BR-01..40)

Ưu tiên quy đổi theo MoSCoW: Must → Cao, Should → Trung bình, Could → Thấp.

Phương pháp kiểm chứng (KC) theo BRS:

| Mã | Phương pháp |
| --- | --- |
| T | Kiểm thử |
| D | Trình diễn |
| A | Phân tích |
| I | Kiểm tra |

| Mã | Loại | Mô tả | Ưu tiên | KC | Nguồn |
| --- | --- | --- | --- | --- | --- |
| BR-01 | Chức năng | Tự dò tìm camera ONVIF trong LAN của bộ kết nối | Cao | T | BRS 4.1 BP-01 · US-01 |
| BR-02 | Chức năng | Thêm camera thủ công (IP, cổng, tài khoản hoặc URL luồng) | Cao | T | BP-01 · US-02 |
| BR-03 | Chức năng | Nhận toàn bộ kênh của đầu ghi NVR/DVR | Trung bình | T | BP-01 · US-03 |
| BR-04 | Chức năng | Trạng thái trực tuyến/mất kết nối và thời điểm mất gần nhất | Cao | D | BP-01 · US-04 |
| BR-05 | Chức năng | Tổ chức theo địa điểm/khu vực/nhóm; lọc theo tên, trạng thái, hãng | Cao | D | BP-01 · US-05 |
| BR-06 | Chức năng | Kết nối camera mạng khác qua Internet, không mở cổng router | Trung bình | T | BP-01 · US-06 |
| BR-07 | Chức năng | Thêm hàng loạt camera từ file | Thấp | T | BP-01 · US-07 |
| BR-08 | Chức năng | Xem trực tiếp trên trình duyệt, không cài phần mềm | Cao | T | BP-02 · US-08 |
| BR-09 | Chức năng | Lưới 1/4/9/16 ô | Cao | D | BP-02 · US-09 |
| BR-10 | Chức năng | Tự chuyển luồng thấp khi thiếu băng thông; chọn thủ công | Trung bình | T | BP-02 · US-11 |
| BR-11 | Chức năng | Điều khiển PTZ | Cao | D | BP-02 · US-12 |
| BR-12 | Chức năng | Chụp ảnh, phóng to kỹ thuật số, toàn màn hình | Trung bình | D | BP-02 · US-14 |
| BR-13 | Chức năng | Nghe và đàm thoại hai chiều | Thấp | D | BP-02 · US-15 |
| BR-14 | Chức năng | Ghi liên tục, theo lịch, theo sự kiện | Cao | T | BP-03 · US-16 |
| BR-15 | Chức năng | Xem lại theo dòng thời gian, đánh dấu sự kiện | Cao | T | BP-03 · US-17 |
| BR-16 | Chức năng | Phát lại 0,5x–16x, tạm dừng, từng khung hình | Cao | T | BP-03 · US-18 |
| BR-17 | Chức năng | Xem lại đồng bộ nhiều camera | Trung bình | D | BP-03 · US-19 |
| BR-18 | Chức năng | Xuất đoạn video thành tệp phát được | Cao | T | BP-03 · US-20 |
| BR-19 | Chức năng | Thời gian lưu theo camera, tự xóa video quá hạn | Cao | T | BP-03 · US-21 |
| BR-20 | Chức năng | Xem lại video trên thẻ SD hoặc đầu ghi | Trung bình | T | BP-03 · US-22 |
| BR-21 | Chức năng | Cấu hình độ phân giải, fps, bitrate, codec | Cao | T | BP-04 · US-23 |
| BR-22 | Chức năng | Cấu hình hình ảnh (sáng, tương phản, IR, WDR) | Trung bình | T | BP-04 · US-24 |
| BR-23 | Chức năng | Cấu hình IP, NTP, múi giờ | Trung bình | T | BP-04 · US-25 |
| BR-24 | Chức năng | Vùng chuyển động, độ nhạy, vùng che riêng tư | Cao | D | BP-04 · US-26 |
| BR-25 | Chức năng | Cấu hình mẫu, áp hàng loạt | Trung bình | T | BP-04 · US-27 |
| BR-26 | Chức năng | Đổi mật khẩu, khởi động lại camera từ xa | Trung bình | T | BP-04 · US-28 |
| BR-27 | Chức năng | Chỉ hiện tham số được hỗ trợ; báo lỗi theo tham số | Cao | T | BP-04 · US-29 |
| BR-28 | Chức năng | Tài khoản cá nhân cho mỗi người dùng | Cao | T | BP-05 · US-30 |
| BR-29 | Chức năng | Phân quyền theo vai trò và phạm vi | Cao | T | BP-05 · US-32 |
| BR-30 | Chức năng | Nhật ký thao tác (ai, khi nào, đối tượng) | Cao | T | BP-05 · US-33 |
| BR-31 | Chức năng | Cảnh báo camera mất kết nối | Cao | T | BP-06 · US-34 |
| BR-32 | Chức năng | Cảnh báo chuyển động qua web, email, Telegram, Zalo | Trung bình | T | BP-06 · US-36, US-42 |
| BR-33 | Chức năng | Xác nhận và ghi kết quả xử lý cảnh báo | Trung bình | D | BP-06 · US-37 |
| BR-34 | Chức năng | Lưu bố cục xem theo người dùng | Cao | D | BP-02 · US-10 |
| BR-35 | Chức năng | Lưu và gọi preset PTZ | Cao | D | BP-02 · US-13 |
| BR-36 | Chức năng | 2FA; bắt buộc theo vai trò | Cao | T | BP-05 · US-31 |
| BR-37 | Chức năng | Cảnh báo dung lượng lưu trữ | Cao | T | BP-06 · US-35 |
| BR-38 | Chức năng | Mở xem lại từ cảnh báo | Trung bình | D | BP-06 · US-38 |
| BR-39 | Chức năng | Vai trò tùy chỉnh | Trung bình | T | BP-05 · US-43 |
| BR-40 | Chức năng | Đăng nhập LDAP/AD, gán vai trò theo nhóm | Trung bình | T | BP-05 · US-44 |

Thống kê: 23 Must, 15 Should, 2 Could.

### 0.2.2 Yêu cầu phi chức năng

| Mã | Loại | Chỉ tiêu đo được | Ưu tiên | KC | Nguồn |
| --- | --- | --- | --- | --- | --- |
| NFR-01 | Hiệu năng | Hình trực tiếp ≤ 3 giây sau khi mở; độ trễ trong LAN ≤ 2 giây | Cao | T | BRS 4.5 |
| NFR-02 | Hiệu năng | Xem lại bắt đầu phát ≤ 3 giây | Cao | T | BRS 4.5 |
| NFR-03 | Quy mô | ≥ 500 camera, 100 người dùng đồng thời | Cao | T | BRS 4.5 |
| NFR-04 | Sẵn sàng | ≥ 99,5%/tháng, không tính bảo trì có lịch | Cao | A | BRS 4.5 |
| NFR-05 | Bảo mật | Mọi kết nối HTTPS; mật khẩu camera được mã hóa | Cao | I, T | BRS 4.5 |
| NFR-06 | Tuân thủ | Xử lý hình ảnh theo Nghị định 13/2023/NĐ-CP | Cao | I | BRS 4.5 |
| NFR-07 | Khả dụng | Tiếng Việt và tiếng Anh; dùng được từ 360 px | Cao | D | BRS 4.5 |
| NFR-08 | Tương thích | 2 phiên bản mới nhất của Chrome, Edge, Safari, Firefox | Cao | T | BRS 4.5 |
| NFR-09 | Nhật ký | Lưu nhật ký thao tác ≥ 12 tháng | Trung bình | I | BRS 4.5 |
| NFR-10 | Liên tục | RTO ≤ 4 giờ | Cao | T | BRS 4.5 |
| NFR-11 | Liên tục | RPO ≤ 1 giờ | Cao | T | BRS 4.5 |
| NFR-12 | Liên tục | Gián đoạn ghi không vượt RTO; camera có thẻ nhớ tiếp tục ghi tại chỗ | Trung bình | A | BRS 4.5 |
| PNFR-02 | Phát luồng | WebRTC; dự phòng HLS khi mạng chặn WebRTC | Cao | T | PRD 8 |
| PNFR-03 | Codec | Nhận H.264/H.265; tự chuyển mã H.265 khi trình duyệt không giải mã được | Cao | T | PRD 8 |
| PNFR-04 | Định dạng xuất | MP4 (H.264, AAC) | Cao | T | PRD 8 |
| PNFR-05 | Giao thức | ONVIF Profile S/G/T; RTSP | Cao | T | PRD 8 |
| PNFR-06 | Quy mô | ≤ 16 luồng đồng thời mỗi người dùng | Trung bình | T | PRD 8 |
| PNFR-07 | Bảo mật | TLS ≥ 1.2; Argon2/bcrypt; AES-256 cho mật khẩu camera | Cao | I, T | PRD 8 |
| PNFR-08 | Bảo mật | Pentest trước go-live; không còn lỗi nghiêm trọng/cao | Cao | T | PRD 8 |
| PNFR-01, PNFR-09 | — | Trùng với NFR-08 và NFR-04/10/11 | — | — | PRD 8 |
| SaaS-1..4 | Ràng buộc | Mọi bản ghi gắn Tổ chức; phân quyền kiểm Tổ chức trước; cấu hình theo Tổ chức; lưu trữ tách theo Tổ chức | Cao | I, T | PRD 4.1 |

### 0.2.3 Quy tắc nghiệp vụ

| Mã | Loại | Mô tả | Ưu tiên | Nguồn |
| --- | --- | --- | --- | --- |
| RULE-01 | Quy tắc | Chỉ người có quyền xuất video được tải video; mỗi lần xuất ghi nhật ký | Cao | BRS 4.2 |
| RULE-02 | Quy tắc | Tự xóa video quá hạn, trừ đoạn đang giữ phục vụ điều tra | Cao | BRS 4.2 |
| RULE-03 | Quy tắc | Vùng che riêng tư không hiển thị chưa che với người dưới quyền Admin | Cao | BRS 4.2 |
| RULE-04 | Quy tắc | Khóa tài khoản sau 5 lần sai liên tiếp | Cao | BRS 4.2 |
| RULE-05 | Quy tắc | Vô hiệu hóa tài khoản người nghỉ việc trong 24 giờ | Trung bình | BRS 4.2 |
| RULE-06 | Quy tắc | Không vai trò nào sửa/xóa được nhật ký | Cao | BRS 4.2 |
| RULE-07 | Quy tắc | Chuyển camera giữ nguyên video; quyền tính theo vị trí hiện tại | Trung bình | BRS 4.2 |

28 bất biến miền INV-01..28 (Class Diagram §10) là quy tắc dữ liệu kiểm thử được. Mỗi bất biến được truy vết qua US tương ứng ở mục 6.8.

### 0.2.4 Ngưỡng định lượng trong tiêu chí chấp nhận

Các ngưỡng dưới đây dùng làm oracle khi kiểm thử. Người kiểm thử không phải tự suy diễn ngưỡng.

| US | Ngưỡng |
| --- | --- |
| US-01 | Dò tìm ≤ 30 s |
| US-02 | Kiểm tra kết nối ≤ 10 s |
| US-04 | Cập nhật trạng thái ≤ 60 s |
| US-05 | Lọc ≤ 1 s với 500 camera |
| US-06 | Mã kích hoạt dùng 1 lần, hết hạn 24 h; chỉ đi ra cổng 443 |
| US-10 | ≥ 10 bố cục mỗi người dùng |
| US-11 | Chuyển luồng sau 5 s thiếu băng thông |
| US-14 | Ảnh PNG; phóng to tối đa 8x |
| US-16 | Lịch theo khung 30 phút; trước sự kiện 10 s, sau 30 s |
| US-17 | Dòng thời gian thu phóng 24 h → 1 phút |
| US-18 | Phím mũi tên tua ±10 s |
| US-19 | Tối đa 4 camera; lệch ≤ 1 s |
| US-20 | Clip ≤ 60 phút, MP4 H.264; liên kết hết hạn 24 h |
| US-21 | Thời gian lưu 1–365 ngày; xóa hằng ngày |
| US-23 | Kết quả áp dụng ≤ 15 s |
| US-25 | Báo lệch giờ > 5 s |
| US-26 | Độ nhạy 1–100 |
| US-30 | Mật khẩu ≥ 10 ký tự; khóa sau 5 lần sai; phiên hết hạn sau 12 h không hoạt động |
| US-31 | TOTP; 10 mã dự phòng |
| US-32 | Đổi quyền có hiệu lực ≤ 60 s |
| US-33 | Lưu ≥ 12 tháng |
| US-34 | Chờ mặc định 5 phút |
| US-35 | Ngưỡng 85% / 95% |
| US-36 | ≤ 1 thông báo / camera / 2 phút |
| US-38 | Mở xem lại tại thời điểm cảnh báo trừ 10 s (xem Phụ lục A) |
| US-40 | Sao lưu mỗi giờ; giữ ≥ 30 bản |
| US-41 | 360 px; lưới trên điện thoại ≤ 4 ô |
| US-44 | Đồng bộ mỗi giờ; dùng LDAPS |

## 0.3 Thành phần thiết kế từ SADD (IEEE 1016-2009)

| View | Phần tử | Loại | Giao diện | Máy trạng thái | Phụ thuộc |
| --- | --- | --- | --- | --- | --- |
| **Composition** | Core Backend: 11 module (`identity`, `access`, `organization`, `device`, `liveview`, `recording`, `export`, `configuration`, `alerting`, `notification`, `audit`) và `platform` | Modular monolith, Java 21 / Spring Boot 3.5 | REST `/api/v1`, STOMP `/ws`, hook `/internal/media/*` | Alert, ExportJob, Camera, Connector | PostgreSQL, Redis, RabbitMQ, Device Gateway, MediaMTX, LDAP |
| **Composition** | Device Gateway | Dịch vụ Python 3.12 / FastAPI | REST nội bộ `/v1/*` (token nội bộ), WS `/talk/ws/{id}` | Phiên đàm thoại | Camera (ONVIF, RTSP), RabbitMQ, Tunnel |
| **Composition** | MediaMTX 1.12.3 | Media server | WHEP, HLS, playback, RTSP; external auth | — | Core (authorize, segment hook), lưu trữ |
| **Composition** | Media Worker | Python + FFmpeg | AMQP `jobs` / `job.results`, `/health` | ExportJob (phía worker) | RabbitMQ, MinIO, lưu trữ video |
| **Composition** | Site Connector, Tunnel Server | Agent tại địa điểm; chisel server | WSS 443 chỉ đi ra; `/api/v1/connector-agent/*` | Connector | Core, camera tại địa điểm |
| **Composition** | Web App | SPA Vue 3 | Gọi REST, STOMP, WHEP/HLS, WS đàm thoại | Trạng thái ô video, nút đàm thoại | Nginx |
| **Component & Connector** | Nginx | Reverse proxy | TLS 1.2/1.3, CSP, giới hạn 30 r/s mỗi IP, chặn `/internal` và `/actuator` | — | Core, MediaMTX, Gateway, Tunnel |
| **Component & Connector** | Kênh sự kiện | AMQP | `device.events` (MotionDetected, DeviceOnline/Offline); `jobs`; `job.results`; outbox giao dịch; consumer idempotent theo `messageId` | Outbox: chờ → đã gửi | RabbitMQ |
| **Interaction** | 3.9.1 Đăng nhập LDAP + 2FA | Sequence | `/auth/login` → LDAPS bind → `/auth/2fa/verify` | Session | LDAP |
| **Interaction** | 3.9.2 Mở xem trực tiếp | Sequence | live-session → token HMAC 60 s → WHEP → `/internal/media/authorize` → RTSP | Ô video: kết nối → phát / lỗi → HLS | MediaMTX |
| **Interaction** | 3.9.3 Chuyển động → cảnh báo → thông báo | Sequence | PullPoint → `MotionDetected` → throttle Redis → Alert → snapshot → kênh gửi (thử lại 3 lần) → WS | Alert, Notification | Gateway, Worker, SMTP/Telegram/Zalo |
| **Interaction** | 3.9.4 Xuất clip | Sequence | `POST /exports` (202) → `ExportRequested` → FFmpeg → MinIO → `ExportCompleted` → liên kết ký 5 phút | ExportJob | Worker, MinIO |
| **Interaction** | 3.9.5 Dò tìm qua Site Connector | Sequence | discovery → tunnel → WS-Discovery → danh sách thiết bị | — | Connector |
| **Interaction** | 3.11 Thuật toán | Quy tắc | Phân quyền (cache 60 s); xóa video quá hạn lúc 02:00; throttle; khóa PTZ 30 s theo hạng vai trò; chuyển luồng theo băng thông | — | Redis |
| **Information** | Lược đồ PostgreSQL | Schema, Flyway | `organization_id` ở mọi bảng + RLS; `audit_log` và `recording_segment` phân vùng theo tháng; cột `*_enc` AES-256-GCM với khóa phong bì theo Tổ chức | — | 74 bảng (tính cả phân vùng) |
| **Information** | Lưu trữ đối tượng, video | MinIO, NAS | Bucket theo Tổ chức; `/recordings/{org}/{camera}/...` | — | — |
| **Interface** | REST API | REST/JSON, RFC 7807 | 153 endpoint Core, mã lỗi chuẩn hóa (mục 0.5) | — | — |
| **State Dynamics** | Alert | Máy trạng thái | acknowledge, reassign, close(outcome), recover | NEW → IN_PROGRESS → CLOSED; NEW/IN_PROGRESS → RECOVERED | INV-14, INV-28 |
| **State Dynamics** | ExportJob | Máy trạng thái | worker nhận, tải lên, lỗi, hết hạn | QUEUED → PROCESSING → READY → EXPIRED; PROCESSING → FAILED → QUEUED | INV-08 |
| **State Dynamics** | Camera / Recorder | Máy trạng thái | test kết nối; mất > 60 s; kết nối lại | UNKNOWN → ONLINE ⇄ OFFLINE | US-04, US-34 |
| **State Dynamics** | Connector | Máy trạng thái | activate(code); mất tunnel | PENDING_ACTIVATION → ONLINE ⇄ OFFLINE; mã hết hạn → xóa | US-06 |
| **State Dynamics** | User | Enum + bất biến | 5 lần sai; mở khóa; vô hiệu từ thư mục | ACTIVE → LOCKED → ACTIVE; → DISABLED | INV-12, INV-24 |

**Kỹ thuật kiểm thử áp dụng cho các view.**

- Mỗi chuyển trạng thái hợp lệ có ít nhất 1 ca kiểm thử.
- Mỗi chuyển không hợp lệ, ví dụ đóng cảnh báo không có kết quả hoặc giao lại cảnh báo đã đóng, có ít nhất 1 ca âm.
- Kỹ thuật dùng: kiểm thử chuyển trạng thái (state transition testing), độ phủ 0-switch.

## 0.4 Thành phần giao diện

| Thành phần | Trang | Tương tác | Trạng thái | Trợ năng | Luồng người dùng |
| --- | --- | --- | --- | --- | --- |
| LoginView | `/login` | Mật khẩu → OTP → cài 2FA (QR) → mã dự phòng | Đang xử lý, lỗi theo mã (sai mật khẩu, bị khóa, LDAP lỗi) | Nhãn form, focus | UC-01/02 |
| DashboardView | `/dashboard` | Trạng thái theo địa điểm; danh sách camera mất kết nối; cảnh báo mở | Trống, lỗi, cập nhật thời gian thực | Chưa có ARIA | HT-04 |
| LiveView + VideoTile, PtzPanel, TalkButton | `/live` | Kéo camera vào lưới 1/4/9/16; nhấp đúp phóng to; PTZ; preset; nhấn giữ để nói (chuột/chạm/Space); lưu bố cục | Đang kết nối, đang phát, mất kết nối, không có quyền, lỗi → HLS; nút nói: kết nối/đang nói/lỗi | ARIA tương đối đầy đủ | HT-02, UC-20..25 |
| CamerasView + CameraTree, các hộp thoại thêm/nhập/chuyển | `/cameras` | Cây địa điểm/khu vực/nhóm; lọc; thêm thủ công / dò tìm / NVR / nhập tệp; chuyển khu vực; đổi mật khẩu hàng loạt | Đang tải, trống, lỗi; chưa kiểm tra / đang kiểm tra / không tín hiệu; báo cáo lỗi từng dòng | Cây có ARIA | HT-01, UC-10..16 |
| CameraDetailView (7 tab), ZoneEditor | `/cameras/:id` | Ghi hình, luồng, hình ảnh, vùng (SVG), mạng & giờ, lưu trữ, khởi động lại | Ngoại tuyến, áp dụng một phần (lỗi theo tham số), đang chờ camera | Một phần | UC-30, UC-40 |
| PlaybackView + TimelineBar, SyncPlayback, ExportDialog | `/playback` | Lịch tháng; dòng thời gian (thu phóng); tốc độ 0,5–16x; phím tắt; nguồn CamHub/thiết bị; giữ bằng chứng; xuất clip; đồng bộ ≤ 4 camera | Không có dữ liệu (nhảy tới đoạn gần nhất); đang mã hóa; lỗi | TimelineBar có ARIA | HT-03, UC-31..34 |
| ExportsView | `/exports` | Danh sách clip của tôi, tải xuống | Đang xử lý, sẵn sàng, lỗi, hết hạn | — | UC-33 |
| AlertsView (danh sách, quy tắc, kênh) + AlertDetail | `/alerts` | Xác nhận, giao lại, đóng (bắt buộc kết quả), xem ảnh, xem lại; quy tắc chuyển động; liên kết kênh | Mở, đang xử lý, chưa giao, trống | Một phần | HT-02, UC-50..53 |
| NotificationBell, ToastHost | Thanh trên | Số chưa đọc, danh sách, đánh dấu đã đọc, toast có liên kết | Trống | Toast có `aria-live` | US-42 |
| TemplatesView | `/templates` | Tạo/sửa mẫu, áp cho camera, kết quả từng camera, áp lại phần lỗi | Đang áp, một phần, lỗi, camera ngoại tuyến | Chưa có ARIA | UC-41 |
| UsersView (người dùng, vai trò, LDAP) | `/users` | Tạo/sửa người dùng, gán vai trò và phạm vi, vai trò tùy chỉnh, kết nối thư mục, ánh xạ nhóm | Hoạt động, bị khóa, ngoài phạm vi, đang kiểm tra | Một phần | UC-03..07 |
| AuditView | `/audit` | Lọc, phân trang, xuất CSV | Kết quả thất bại, đang xuất | Chưa có ARIA | UC-08 |
| SystemView | `/system` | Lưu trữ mặc định, quy tắc cảnh báo hệ thống, 2FA theo vai trò, kênh thông báo, bộ kết nối, sao lưu (chỉ xem) | Chưa cấu hình, lỗi, mã sắp hết hạn | Chưa có ARIA | UC-15, UC-50, UC-60 |
| ProfileView | `/profile` | Ngôn ngữ, bật/tắt 2FA, tạo lại mã dự phòng | Lỗi | — | UC-63 |
| Toàn cục | Mọi trang | Chuyển ngôn ngữ vi/en (665 khóa mỗi ngôn ngữ, khớp nhau); giao diện tối/sáng; điểm ngắt 1100/860/480 px | `/forbidden` khi thiếu quyền | Không có công cụ kiểm tra trợ năng tự động | US-41 |

## 0.5 Thành phần API

Bảng dưới gom theo nhóm. Danh sách đầy đủ lấy từ OpenAPI sinh tự động (`/v3/api-docs` trên môi trường dev).

Mọi endpoint `/api/v1/**` cần JWT, trừ các endpoint ghi "công khai". Mã lỗi theo RFC 7807 kèm thuộc tính `code`.

Mã lỗi chung áp dụng cho mọi nhóm, không lặp lại trong bảng:

| HTTP | Mã |
| --- | --- |
| 401 | UNAUTHENTICATED |
| 403 | FORBIDDEN |
| 404 | NOT_FOUND |
| 400 | VALIDATION |
| 409 | DUPLICATE / IN_USE |
| 422 | Mã lỗi thiết bị: AUTH_FAILED, UNREACHABLE, NOT_ONVIF, NOT_SUPPORTED, LIMIT_EXCEEDED, INVALID_ZONE, BUSY |
| 502 | DEVICE_ERROR |
| 503 | GATEWAY_UNAVAILABLE |

| Nhóm (số endpoint) | Endpoint tiêu biểu | Phương thức | Yêu cầu | Phản hồi | Lỗi đặc thù | Xác thực |
| --- | --- | --- | --- | --- | --- | --- |
| Xác thực (5) | `/api/v1/auth/login`, `/2fa/setup`, `/2fa/verify`, `/refresh`, `/logout` | POST | email, password; challengeId, code | Token truy cập + cookie refresh (HttpOnly, Secure, SameSite=Strict), hoặc `{twoFactorRequired, challengeId}` | 401 INVALID_CREDENTIALS; 423 ACCOUNT_LOCKED; 403 ACCOUNT_DISABLED; 503 DIRECTORY_UNAVAILABLE; 401 INVALID_2FA_CODE / TWO_FACTOR_EXPIRED / SESSION_EXPIRED | Công khai |
| 2FA cá nhân (6) | `/api/v1/me/2fa/*`, `/users/{id}/2fa/reset` | GET, POST | code | Trạng thái, QR, mã dự phòng | 409 TWO_FACTOR_REQUIRED | Có |
| Người dùng, vai trò, phạm vi (16) | `/users`, `/users/{id}/assignments`, `/roles`, `/me/permissions` | GET, POST, PATCH, PUT, DELETE | Người dùng, vai trò, phạm vi | Đối tượng, danh sách | 409 EMAIL_EXISTS; 400 WEAK_PASSWORD; 403 ASSIGNMENT_OUT_OF_SCOPE / ROLE_EXCEEDS_OWN_PERMISSIONS; 409 SYSTEM_ROLE / ROLE_IN_USE | MANAGE_USERS / MANAGE_ROLES |
| Thư mục LDAP (6) | `/directory`, `/directory/test`, `/directory/mappings`, `/directory/sync` | GET, PUT, POST | Cấu hình, ánh xạ nhóm | Kết quả kiểm tra, đồng bộ | 400 INVALID_DIRECTORY / DIRECTORY_NOT_CONFIGURED; 503 DIRECTORY_UNAVAILABLE | MANAGE_USERS (toàn Tổ chức) |
| Tổ chức (15) | `/sites`, `/sites/{id}/zones`, `/camera-groups`, `/cameras/{id}/move` | GET, POST, PATCH, DELETE, PUT | Tên, địa chỉ | Cây địa điểm | 409 SITE_NOT_EMPTY / ZONE_NOT_EMPTY | MANAGE_SYSTEM / CONFIGURE_CAMERA |
| Camera, thiết bị (17) | `/cameras`, `/cameras/test-connection`, `/cameras/bulk`, `/cameras/import[/preview]`, `/sites/{id}/discovery`, `/recorders`, `/cameras/password`, `/cameras/{id}/reboot` | GET, POST, PATCH, DELETE | Camera, tệp CSV/XLSX | Phân trang (≤ 1000), BulkResult theo từng mục | 400 HOST_REQUIRED / INVALID_FILE / NO_CHANNEL; 409 ONVIF_REQUIRED; 504 CONNECTOR_TIMEOUT; 503 CONNECTOR_OFFLINE | CONFIGURE_CAMERA |
| Bộ kết nối (7) | `/connectors`, `/connectors/{id}/activation-code`, `/connector-agent/activate`, `/heartbeat`, `/tasks/{id}/result` | GET, POST, DELETE | Mã kích hoạt; Basic connectorId:secret | Mã kích hoạt, cấu hình tunnel | 404 INVALID_CODE; 410 ACTIVATION_EXPIRED; 409 ALREADY_ACTIVATED; 401 CONNECTOR_UNAUTHORIZED | MANAGE_SYSTEM; agent công khai có mã/bí mật |
| Xem trực tiếp, PTZ, đàm thoại, bố cục (14) | `/cameras/{id}/live-session`, `/ptz`, `/ptz/stop`, `/presets`, `/talk`, `/talk/{sid}/end`, `/layouts` | GET, POST, PUT, DELETE | stream MAIN/SUB; pan/tilt/zoom −1..1 | WHEP/HLS URL + token 60 s; phiên WS đàm thoại dùng 1 lần | 409 PTZ_LOCKED / PTZ_NOT_SUPPORTED / TALK_BUSY / TALK_NOT_SUPPORTED / LAYOUT_LIMIT; 400 INVALID_LAYOUT | VIEW_LIVE / CONTROL_PTZ / TALK_AUDIO |
| Ghi hình, xem lại, lưu trữ (17) | `/cameras/{id}/recording-policy`, `/timeline`, `/recording-days`, `/playback-session`, `/edge-timeline`, `/edge-playback-session`, `/retention-policy`, `/evidence-holds`, `/retention/purge` | GET, PUT, POST | Chế độ, lịch, from/to | Đoạn ghi, phiên phát | 400 INVALID_SCHEDULE / RANGE_TOO_LONG (> 31 ngày) / INVALID_RANGE; 404 NO_RECORDING; 409 EDGE_NOT_SUPPORTED | CONFIGURE_CAMERA / VIEW_PLAYBACK / HOLD_EVIDENCE |
| Xuất clip (4) | `/exports`, `/exports/{id}/download-link`, `/exports/{id}/file` | GET, POST | cameraId, from, to | 202 + exportId; liên kết ký HMAC 5 phút | 400 EXPORT_RANGE_TOO_LONG; 429 TOO_MANY_EXPORTS; 409 EXPORT_NOT_READY; 410 EXPORT_EXPIRED; 403 LINK_INVALID | EXPORT_VIDEO; tệp tải qua chữ ký |
| Cảnh báo, quy tắc (14) | `/alerts`, `/alerts/{id}/acknowledge`, `/reassign`, `/close`, `/snapshot`, `/motion-rules`, `/alert-rules/{type}` | GET, POST, PUT, DELETE | outcome, note, assignee | Cảnh báo, lịch sử | 409 ALERT_NOT_NEW / NOT_ASSIGNEE / ALERT_CLOSED; 400 OUTCOME_REQUIRED / REASON_REQUIRED / MOTION_NOT_SUPPORTED | Theo khả năng thấy cảnh báo; MANAGE_SYSTEM |
| Thông báo, kênh (9) | `/me/notifications*`, `/me/channels/{c}`, `/notification-channels/{c}` | GET, POST, PUT, DELETE | Địa chỉ kênh, bí mật | Danh sách, số chưa đọc | 400 INVALID_CHANNEL / INVALID_ADDRESS / CHANNEL_SECRET_REQUIRED | Có / MANAGE_SYSTEM |
| Cấu hình camera (10) | `/cameras/{id}/config/{network,time,image,zones,zones/motion,zones/masks,stream}` | GET, PUT | Tham số theo khả năng camera | ApplyResult theo từng tham số | 400 OUT_OF_RANGE / NOT_SUPPORTED / INVALID_NETWORK / UNKNOWN_CONFIGURATION | CONFIGURE_CAMERA |
| Mẫu cấu hình (7) | `/config-templates`, `/{id}/apply`, `/template-applications/{id}`, `/reapply-failed` | GET, POST, PUT, DELETE | Mẫu, danh sách camera | Kết quả theo camera | 400 INVALID_TEMPLATE / INVALID_SELECTION / NOTHING_FAILED | CONFIGURE_CAMERA |
| Nhật ký (2) | `/audit-logs`, `/audit-logs/export` | GET | Bộ lọc, trang ≤ 500 | Trang; CSV có BOM, chặn CSV injection | — | MANAGE_SYSTEM |
| Hệ thống (2) | `/system/overview`, `/system/backups` | GET | — | Tổng quan, bản sao lưu | — | MANAGE_SYSTEM |
| Nội bộ (2 + 1 PoC) | `/internal/media/authorize`, `/internal/media/segments`, `/api/v1/poc/media-token` | POST | Hook MediaMTX | 200 / 401 / 202 | Nginx trả 404 từ bên ngoài | Nội bộ |
| STOMP | `/ws`; kênh `/topic/org/{org}/camera-status`, `connector-status`; `/user/queue/notifications`, `exports`, `reboot`, `ptz` | CONNECT, SUBSCRIBE | Bearer JWT | Đẩy sự kiện | Đăng ký kênh của Tổ chức khác → AccessDenied | Có |
| Device Gateway (37) | `/v1/discovery`, `/v1/devices/*`, `/v1/events/subscriptions`, `/v1/monitor/targets`, `/v1/talk/sessions`, WS `/talk/ws/{id}` | POST, GET, PUT, DELETE, WS | `X-Internal-Token` | 424 kèm mã thiết bị; 422 VALIDATION_ERROR (không trả lại giá trị đầu vào) | 401 GATEWAY_UNAUTHORIZED; WS đóng 4404 / 4502 | Token nội bộ |

## 0.6 User story và tiêu chí chấp nhận

Toàn bộ 52 mục backlog. Cột tiêu chí chấp nhận tóm tắt AC; kịch bản Gherkin đầy đủ nằm trong `tests/e2e/features/<ID>.feature`.

| Mã | Mô tả | Tiêu chí chấp nhận | Ưu tiên | Phụ thuộc / sprint |
| --- | --- | --- | --- | --- |
| TS-01 | Khung dự án, CI/CD | AC1 build/test/lint/kiến trúc chạy cho 4 thành phần khi mở PR; AC2 phụ thuộc vòng giữa module làm build lỗi | Cao | S0 |
| TS-02 | Docker Compose dev/staging | AC1 mọi container healthy ≤ 3 phút, đăng nhập qua HTTPS | Cao | S0 |
| TS-03 | PoC MediaMTX | AC1 first frame trung vị ≤ 3 s, độ trễ ≤ 2 s, CPU ≤ 70%; AC2 token WHEP sai bị từ chối | Cao | S0 |
| TS-04 | Nền tảng Core: RLS, outbox, mã hóa, sự kiện nhật ký | AC1 RLS cô lập Tổ chức kể cả khi quên điều kiện lọc; AC2 outbox gửi bù khi broker hồi phục | Cao | S0 |
| TS-05 | Device Gateway + bộ kiểm thử tương thích ONVIF | AC1 báo cáo theo model, ≥ 5 hãng | Cao | S1 |
| TS-06 | Tunnel Server + Site Connector | AC1 chỉ dùng cổng 443 đi ra, đọc được RTSP | Trung bình | S6 |
| TS-07 | Giám sát, log tập trung | AC1 Core ngừng > 1 phút → báo động vận hành | Cao | S0 |
| TS-08 | Kiểm thử tải, hỗ trợ pentest | AC1 NFR-01/02 đạt P95 với 500 camera / 100 người dùng / 1 h; AC2 không còn lỗi bảo mật nghiêm trọng/cao | Cao | S9 · mọi US M1, M2 |
| US-01 | Dò tìm ONVIF | AC1 3 camera hiện ≤ 30 s (IP/hãng/model/MAC); AC2 camera đã có đánh dấu "Đã thêm"; AC3 sai mật khẩu: camera khác vẫn thêm, có nút thử lại | Cao | S2 · TS-05 |
| US-02 | Thêm thủ công | AC1 kiểm tra kết nối có hình xem trước ≤ 10 s; AC2 không kết nối được: chỉ lưu khi xác nhận | Cao | S2 |
| US-03 | Thêm NVR/DVR | AC1 3 kênh thành 3 camera đặt tên theo kênh; AC2 NVR mất kết nối → 3 camera ngoại tuyến | Trung bình | S6 |
| US-04 | Trạng thái camera | AC1 ngoại tuyến hiện ≤ 60 s kèm "từ lúc"; AC2 địa điểm hiện "5 / 7" | Cao | S2 |
| US-05 | Tổ chức, lọc, chuyển camera | AC1 lọc 500 camera ≤ 1 s; AC2 mỗi camera 1 khu vực, giữ nhóm; AC3 chuyển giữ video, ghi MOVE_CAMERA | Cao | S1 |
| US-06 | Kích hoạt bộ kết nối | AC1 kích hoạt → trực tuyến + lần liên lạc cuối; AC2 mã hết hạn bị từ chối | Trung bình | S6 · TS-06 |
| US-07 | Nhập camera từ file | AC1 nhập dòng hợp lệ, báo lỗi từng dòng | Thấp | S9 · US-02 |
| US-08 | Xem trực tiếp | AC1 hình ≤ 3 s, không plugin, trễ ≤ 2 s; AC2 không quyền: 403, không có luồng; AC3 WebRTC bị chặn → HLS | Cao | S3 · TS-03 |
| US-09 | Lưới nhiều camera | AC1 lưới 9 dùng luồng phụ; AC2 phóng to dùng luồng chính; AC3 ô ngoại tuyến hiện trạng thái, không đen | Cao | S3 |
| US-10 | Lưu bố cục | AC1 mở lại bố cục gần nhất; AC2 camera bị thu quyền bị bỏ khỏi bố cục | Cao | S3 |
| US-11 | Chuyển luồng theo băng thông | AC1 thiếu băng thông → luồng phụ + nhãn "chất lượng thấp"; AC2 chế độ thủ công không tự chuyển | Trung bình | S6 |
| US-12 | PTZ | AC1 ẩn PTZ với camera không hỗ trợ; AC2 tranh chấp: từ chối, hiện tên người đang điều khiển | Cao | S3 |
| US-13 | Preset PTZ | AC1 lưu/gọi về đúng vị trí | Cao | S3 · US-12 |
| US-14 | Chụp ảnh, phóng to, toàn màn hình | AC1 PNG đặt tên camera + thời điểm | Trung bình | S6 |
| US-15 | Âm thanh hai chiều | AC1 nhấn giữ để nói tới loa camera, ghi TALK_AUDIO + thời lượng | Thấp | S9 · US-08 |
| US-16 | Chế độ ghi | AC1 ghi theo sự kiện đủ trước/sau; AC2 áp hàng loạt 10 camera hiện REC | Cao | S4 |
| US-17 | Xem lại theo dòng thời gian | AC1 phát ≤ 3 s; AC2 không có dữ liệu: nhảy tới đoạn gần nhất + thông báo; AC3 3 màu loại đoạn, lịch đánh dấu ngày | Cao | S4 · US-16 |
| US-18 | Điều khiển phát lại | AC1 16x, tạm dừng, phím tắt ±10 s | Cao | S4 |
| US-19 | Xem lại đồng bộ | AC1 4 camera đồng bộ, lệch ≤ 1 s; AC2 camera thứ 5 bị chặn kèm thông báo | Trung bình | S8 |
| US-20 | Xuất clip | AC1 có thông báo sẵn sàng, MP4 phát được, ghi EXPORT_VIDEO; AC2 > 60 phút bị từ chối; AC3 không quyền: ẩn nút; AC4 liên kết hết hạn: "Hết hạn" | Cao | S5 |
| US-21 | Lưu trữ, giữ bằng chứng, tự xóa | AC1 thời gian lưu riêng camera được áp; AC2 đoạn đang giữ không bị xóa; AC3 không quyền: ẩn nút giữ | Cao | S4 |
| US-22 | Xem lại từ thiết bị | AC1 dòng thời gian thẻ SD phát được; AC2 không hỗ trợ: nguồn bị vô hiệu kèm lý do | Trung bình | S7 |
| US-23 | Cấu hình luồng | AC1 "Đã áp dụng" ≤ 15 s; AC2 áp một phần: lỗi theo từng tham số | Cao | S4 · US-29 |
| US-24 | Cấu hình hình ảnh | AC1 khôi phục về giá trị trước | Trung bình | S7 |
| US-25 | Mạng và thời gian | AC1 đổi IP có cảnh báo, rồi cập nhật địa chỉ; AC2 báo lệch giờ | Trung bình | S8 |
| US-26 | Vùng chuyển động, vùng che | AC1 vùng che áp cả trực tiếp và video ghi; AC2 vượt giới hạn vùng của camera bị chặn | Cao | S5 |
| US-27 | Cấu hình mẫu | AC1 kết quả theo camera (đạt/một phần/lỗi) + nút "Áp lại" | Trung bình | S8 |
| US-28 | Đổi mật khẩu, khởi động lại | AC1 đổi hàng loạt: camera lỗi giữ mật khẩu cũ; AC2 theo dõi khởi động lại tới khi trực tuyến | Trung bình | S6 |
| US-29 | Chỉ hiện tham số được hỗ trợ | AC1 ẩn codec không hỗ trợ | Cao | S3 |
| US-30 | Đăng nhập tài khoản cá nhân | AC1 trang đích theo vai trò, ghi LOGIN; AC2 khóa sau 5 lần, ghi LOGIN_FAILED; AC3 hết phiên phải đăng nhập lại | Cao | S1 |
| US-31 | 2FA | AC1 vai trò bắt buộc: phải cài 2FA mới vào; AC2 mã dự phòng dùng 1 lần | Cao | S2 · US-30 |
| US-32 | Phân quyền vai trò và phạm vi | AC1 phạm vi địa điểm chỉ thấy camera địa điểm đó; AC2 thu quyền dừng xem ≤ 60 s; AC3 Site Manager chỉ gán trong phạm vi, không gán Admin | Cao | S1 |
| US-33 | Nhật ký thao tác | AC1 hiện người/thời điểm/IP/đối tượng/kết quả; AC2 sửa/xóa bị từ chối | Cao | S2 |
| US-34 | Cảnh báo mất kết nối | AC1 quá thời gian chờ → CAMERA_OFFLINE; AC2 cả địa điểm → một SITE_OFFLINE; AC3 tự đóng khi kết nối lại | Cao | S5 · US-04 |
| US-35 | Cảnh báo dung lượng | AC1 cảnh báo kèm dung lượng trống và số ngày còn lại | Cao | S5 |
| US-36 | Cảnh báo chuyển động đa kênh | AC1 trong lịch: Telegram kèm tên/giờ/ảnh/liên kết; AC2 ngoài lịch: không gửi; AC3 chống spam: gửi 1 lần, vẫn đánh dấu sự kiện; AC4 camera không hỗ trợ: ẩn tùy chọn kèm lý do | Trung bình | S7 |
| US-37 | Xử lý, chuyển giao cảnh báo | AC1 xác nhận → Đang xử lý, có người phụ trách; AC2 chuyển giao có thông báo, giữ lịch sử; AC3 đóng bắt buộc chọn kết quả | Trung bình | S7 |
| US-38 | Xem lại từ cảnh báo | AC1 phát từ thời điểm cảnh báo trừ 10 s, có nút xem trực tiếp | Trung bình | S7 |
| US-39 | Bảng tổng quan | AC1 trực tuyến/tổng, cảnh báo mở, danh sách ngoại tuyến | Cao | S5 |
| US-40 | Sao lưu, khôi phục | AC1 sao lưu theo lịch, giữ ≥ 30 bản; AC2 diễn tập RTO ≤ 4 h, RPO ≤ 1 h; AC3 Admin chỉ xem, không có nút khôi phục | Cao | S5 |
| US-41 | Đa ngôn ngữ, responsive | AC1 chuyển toàn bộ sang tiếng Anh; AC2 360 px không cuộn ngang, lưới ≤ 4 | Cao | S1 |
| US-42 | Trung tâm thông báo | AC1 chuông tăng số, toast có liên kết | Trung bình | S7 |
| US-43 | Vai trò tùy chỉnh | AC1 nhân bản vai trò và bỏ bớt quyền; AC2 vai trò đang dùng: từ chối xóa, báo số người; AC3 vai trò hệ thống không sửa được | Trung bình | S8 |
| US-44 | Đăng nhập LDAP/AD | AC1 lần đầu tạo người dùng, vai trò theo nhóm; AC2 người nghỉ việc bị vô hiệu; AC3 thư mục lỗi: báo lỗi, Admin cục bộ vẫn vào được | Trung bình | S8 |

## 0.7 Đánh giá rủi ro

Thang điểm:

- Khả năng (K) và Ảnh hưởng (A) chấm từ 1 đến 5.
- Điểm = K × A.

| Điểm | Mức | Cách xử lý |
| --- | --- | --- |
| ≥ 15 | Cao | Kiểm thử sâu, đưa vào hồi quy và cổng phát hành |
| 8–14 | Trung bình | Kiểm thử đầy đủ AC |
| ≤ 7 | Thấp | Kiểm thử AC chính |

| Mã | Rủi ro | K | A | Điểm | Chiến lược giảm thiểu (kiểm thử) |
| --- | --- | --- | --- | --- | --- |
| RISK-01 (R-01, AR-02) | Camera một số hãng không tuân thủ đủ ONVIF | 4 | 5 | **20** | Bộ tương thích `compat` chạy trên ≥ 5 hãng (TS-05); kiểm thử RTSP dự phòng; danh sách tương thích công bố |
| RISK-02 (AR-01, PR-01) | MediaMTX không đạt 500 luồng ghi; chuyển mã H.265 tốn CPU | 3 | 5 | **15** | PERF-04 trên staging với 50 camera thật + 450 luồng giả lập; PERF-07 giới hạn phiên chuyển mã |
| RISK-03 (R-04) | Rò rỉ hình ảnh hoặc tài khoản camera | 2 | 5 | 10 | SEC-01..12; pentest ASVS L2 độc lập; RLS (TenancyIT); token media 60 s |
| RISK-04 | Lỗi phân quyền theo phạm vi (IDOR giữa địa điểm/Tổ chức) | 3 | 5 | **15** | Ma trận vai trò × phạm vi × endpoint (SEC-02); AccessIT; e2e US-32; kiểm thử âm với 5 vai trò |
| RISK-05 (R-02, PR-03) | Băng thông địa điểm thấp, trễ WebRTC qua Internet | 3 | 4 | 12 | PERF-08 với tc/netem; HLS dự phòng (US-08 AC3); đo tại pilot |
| RISK-06 (AR-03) | Media node là điểm lỗi duy nhất của ghi hình | 3 | 4 | 12 | PERF-10 tắt media node: kiểm NFR-12 và cảnh báo vận hành |
| RISK-07 (R-05) | Vi phạm Nghị định 13/2023 (lưu, xóa, che riêng tư) | 2 | 5 | 10 | Kiểm thử RULE-02/03, US-21, US-26; Pháp chế rà soát (NFR-06, phương pháp I) |
| RISK-08 | Mất khóa chính → bản sao lưu không giải mã được | 2 | 5 | 10 | Bước 4b của diễn tập khôi phục (đã có); diễn tập hằng quý |
| RISK-09 | Chưa có môi trường staging và camera thật (D-01, D-04) | 5 | 4 | **20** | Rủi ro dự án lớn nhất cho kiểm thử: NFR-01..03 và tương thích mới đo trên laptop dev. Xem mục 15 |
| RISK-10 | CI chưa từng chạy (repo chưa đẩy lên); e2e, smoke, tải không có trong CI | 4 | 3 | 12 | Đẩy repo, bật CI; thêm job e2e + smoke chạy hằng đêm (mục 6.2) |
| RISK-11 | Kiểm thử e2e phụ thuộc dữ liệu do test trước để lại (đã gặp ở S9) | 3 | 3 | 9 | Mỗi spec tự dọn dữ liệu; chạy lại toàn bộ trên DB sạch trước phát hành |
| RISK-12 | Thông báo ngoài (Zalo OA, Telegram, SMTP) chưa thử thật (D-03, OQ-P02) | 4 | 3 | 12 | Thử với tài khoản thật trên staging; giả lập `chat-mock` cho hồi quy |
| RISK-13 | LDAP/AD thật khác OpenLDAP (sAMAccountName, nhóm lồng nhau) | 3 | 3 | 9 | Thử với AD của tổ chức ở pilot (D-06) |
| RISK-14 | Thiếu kiểm thử trợ năng và trình duyệt Safari/Firefox | 3 | 2 | 6 | ACC-01..06 bằng axe-core; ma trận trình duyệt UI-09 |
| RISK-15 (R-03, AR-04) | Dung lượng lưu trữ tăng nhanh | 4 | 3 | 12 | Kiểm thử xóa quá hạn (UC-36), cảnh báo dung lượng (US-35), ước tính số ngày còn lại |

---

# Phần B – Kế hoạch kiểm thử (IEEE 829-2008)

| Mục | Điều khoản IEEE 829 | Nội dung |
| --- | --- | --- |
| 1 | 5.1 | Định danh kế hoạch |
| 2 | 5.2 | Giới thiệu |
| 3 | 5.3 | Đối tượng kiểm thử |
| 4 | 5.4 | Tính năng được kiểm thử |
| 5 | 5.5 | Tính năng không kiểm thử |
| 6 | 5.6 | Cách tiếp cận |
| 7 | 5.7 | Tiêu chí đạt/không đạt |
| 8 | 5.8 | Tiêu chí tạm dừng và tiếp tục |
| 9 | 5.9 | Sản phẩm bàn giao |
| 10 | 5.10 | Công việc kiểm thử |
| 11 | 5.11 | Nhu cầu môi trường |
| 12 | 5.12 | Trách nhiệm |
| 13 | 5.13 | Nhân sự và đào tạo |
| 14 | 5.14 | Lịch trình |
| 15 | 5.15 | Rủi ro và dự phòng |
| 16 | 5.16 | Phê duyệt |

## 1. Định danh kế hoạch

| Thuộc tính | Giá trị |
| --- | --- |
| Mã tài liệu | CAMHUB-MTP-v1.0 |
| Loại | Kế hoạch kiểm thử tổng thể (MTP), bao trùm các kế hoạch theo mức |
| Đối tượng | CamHub 1.0, bản mã nguồn ngày 27/10/2026 (nhánh `main`) |
| Kế hoạch con | Kiểm thử tải: [tests/load/README.md](../../tests/load/README.md). Diễn tập khôi phục: [restore.md](../runbook/restore.md). Pentest: do nhà cung cấp độc lập lập kế hoạch (mục 6.1.7) |
| Lịch sử | 1.0 (27/10/2026): bản đầu, lập sau khi S0–S9 hoàn thành trên môi trường dev |

## 2. Giới thiệu

### 2.1 Mục đích

Kế hoạch này nhằm:

- Định nghĩa chiến lược kiểm thử tổng thể cho CamHub 1.0.
- Xác định các mức, loại và chu kỳ kiểm thử.
- Phân vai trò, trách nhiệm và lịch trình.
- Đặt tiêu chí vào/ra cho từng mức.
- Xác định môi trường, công cụ và tài nguyên.
- Truy vết từ yêu cầu (BR/NFR/RULE) qua user story (US) và use case (UC) tới ca kiểm thử và bằng chứng.

Kế hoạch là căn cứ để quyết định qua **cổng G2 (Beta)** và **G3 (GA)**.

### 2.2 Phạm vi

**Trong phạm vi:**

- Kiểm thử chức năng 40 BR, 44 US, 8 TS và 39 UC.
- Kiểm thử phi chức năng:
  - hiệu năng (NFR-01..03);
  - sẵn sàng (NFR-04, NFR-12);
  - bảo mật (NFR-05, PNFR-07/08);
  - khả dụng và đa ngôn ngữ (NFR-07);
  - tương thích trình duyệt (NFR-08);
  - tương thích thiết bị ONVIF/RTSP (PNFR-05);
  - liên tục kinh doanh (NFR-10/11).
- Kiểm thử API: REST Core, STOMP, API nội bộ Device Gateway, hook MediaMTX.
- Kiểm thử giao diện: so với thiết kế HTML, responsive 360 px, trạng thái màn hình.
- Kiểm thử tích hợp: Core ↔ Gateway ↔ MediaMTX ↔ Worker ↔ RabbitMQ ↔ PostgreSQL/Redis/MinIO; LDAP; SMTP/Telegram/Zalo; Site Connector qua tunnel.
- Kiểm thử đầu-cuối theo hành trình HT-01..04 và kịch bản vận hành SC-01..04.
- Kiểm thử hồi quy tự động; kiểm thử chấp nhận (UAT) với người dùng pilot.
- Kiểm thử trợ năng mức cơ bản (WCAG 2.1 AA, mục 6.1.8).

**Ngoài phạm vi** (chi tiết ở mục 5):

- Các tính năng ngoài bản 1.0 (OOS-1..8).
- Chức năng khôi phục trong ứng dụng (không tồn tại theo thiết kế, UCQ-03). Quy trình khôi phục của Đội vận hành IT vẫn được kiểm thử qua diễn tập.
- Firmware camera và hạ tầng mạng của địa điểm.
- Kiểm thử tải trên 2 lần tải đỉnh (trên 200 người dùng / 1000 camera).

Pentest do bên độc lập thực hiện. Kế hoạch này chỉ chuẩn bị môi trường, tài khoản và tiếp nhận, sửa và kiểm lại lỗi.

### 2.3 Mục tiêu

| Mã | Mục tiêu | Tiêu chí thành công | Truy vết |
| --- | --- | --- | --- |
| OBJ-01 | Xác nhận yêu cầu chức năng | 100% BR-01..40 và US-01..44 có ca kiểm thử. 100% AC Must đạt. ≥ 95% AC Should đạt; phần còn lại có ngoại lệ được PO chấp nhận | BR-01..40, US-01..44 |
| OBJ-02 | Xác nhận hợp đồng API | 100% nhóm endpoint có kiểm thử dương và âm. Mọi mã lỗi đặc thù trong mục 0.5 có ít nhất 1 ca | API-01..18 |
| OBJ-03 | Xác nhận giao diện | 11 màn hình khớp chức năng với `design/*.html`. 360 px không cuộn ngang. Đủ hai ngôn ngữ | NFR-07, US-41 |
| OBJ-04 | Xác nhận trợ năng | Không còn vi phạm axe-core mức `serious`/`critical` trên 11 màn hình chính | ACC-01..06 |
| OBJ-05 | Xác nhận hiệu năng | First frame P95 ≤ 3 s (lưới 16). Xem lại P95 ≤ 3 s. API P95 < 500 ms; phiên xem trực tiếp P95 < 300 ms. Lỗi < 1% với 100 người dùng, 500 camera, 1 giờ | NFR-01..03 |
| OBJ-06 | Xác nhận bảo mật | Không còn lỗi nghiêm trọng/cao sau pentest ASVS L2. Quét thư viện 0 lỗ hổng CVSS ≥ 7 chưa xử lý | NFR-05, PNFR-07/08 |
| OBJ-07 | Xác nhận khả năng liên tục | Diễn tập khôi phục đạt RTO ≤ 4 h, RPO ≤ 1 h; bí mật giải mã được | NFR-10/11, US-40 |
| OBJ-08 | Xác nhận tương thích thiết bị | Camera của ≥ 5 hãng qua bộ `compat` (9 khả năng). Pilot có ≥ 3 hãng | PNFR-05, TS-05, AC-B2 |
| OBJ-09 | Chấp nhận của người dùng | Người dùng pilot chấm ≥ 4/5. UAT-01..44 đạt hoặc có ngoại lệ được chấp nhận | AC-B5 |

### 2.4 Tài liệu tham chiếu

| Tài liệu | Phiên bản | Nguồn |
| --- | --- | --- |
| BRS – Đặc tả yêu cầu nghiệp vụ | 0.6 | Business Analyst |
| PRD – CamHub | 0.4 | Product Owner |
| Use Case – CamHub | 0.2 | Business Analyst |
| Class Diagram (mô hình miền) | 0.4 | Business Analyst |
| SADD – CamHub (IEEE 1016-2009) | 0.1 | Solution Architect |
| Thiết kế giao diện `design/*.html` | 21/10/2026 | Thiết kế |
| OpenAPI (springdoc, sinh từ mã) | Theo bản build | Đội phát triển |
| User Stories – CamHub | 27/10/2026 | Product Owner |
| Kế hoạch phát triển `implement/plan` | 27/10/2026 | Đội phát triển |
| IEEE 829-2008 | 2008 | IEEE |
| OWASP ASVS 4.0 (L2) | 4.0.3 | OWASP |
| WCAG 2.1 | 2.1 | W3C |
| ONVIF Profile S/G/T; ONVIF Streaming Spec (backchannel §5.3) | — | ONVIF |
| Nghị định 13/2023/NĐ-CP | 2023 | Chính phủ |

## 3. Đối tượng kiểm thử

### 3.1 Phần mềm được kiểm thử

| Mã | Đối tượng | Phiên bản | Nguồn | Dạng |
| --- | --- | --- | --- | --- |
| SI-01 | Core Backend (`core/`) | 0.x, Java 21, Spring Boot 3.5.16 | Đội phát triển | Image `camhub/core`; REST + STOMP |
| SI-02 | Device Gateway (`device-gateway/`) | Python 3.12, FastAPI | Đội phát triển | Image `camhub/device-gateway`; REST nội bộ + WS đàm thoại |
| SI-03 | Media Worker (`media-worker/`) | Python 3.12, FFmpeg | Đội phát triển | Image `camhub/media-worker`; consumer AMQP |
| SI-04 | Site Connector (`site-connector/`) và Tunnel (`deploy/tunnel`) | Python; chisel 1.12.1 | Đội phát triển | Image chạy tại địa điểm |
| SI-05 | Web App (`web/`) | Vue 3 + Vite | Đội phát triển | Image `camhub/web` (Nginx + SPA) |
| SI-06 | Lược đồ CSDL (Flyway `core/src/main/resources/db/migration`) | PostgreSQL 16 | Đội phát triển | Migration đánh số; RLS; phân vùng |
| SI-07 | Cấu hình triển khai (`deploy/`) | Docker Compose | Đội phát triển / IT | `docker-compose.yml`, `nginx.conf`, `mediamtx.yml`, cấu hình RabbitMQ, Prometheus, pgBackRest |
| SI-08 | MediaMTX (thành phần bên thứ ba, cấu hình của dự án) | 1.12.3 | Bên thứ ba | Kiểm thử tích hợp và hiệu năng; không kiểm thử nội bộ |
| SI-09 | Tài liệu vận hành | — | Đội phát triển | Runbook khôi phục, README; kiểm tra bằng diễn tập |

### 3.2 Sản phẩm kiểm thử cung cấp kèm

| Mã | Sản phẩm | Định dạng | Vị trí |
| --- | --- | --- | --- |
| TA-01 | Ca kiểm thử chấp nhận dạng Gherkin | `.feature` (52 tệp, 105 kịch bản) | `tests/e2e/features/` |
| TA-02 | Script e2e | Playwright (JS), 51 test | `tests/e2e/specs/` |
| TA-03 | Kiểm thử đơn vị, tích hợp | JUnit 5 + Testcontainers (107), pytest (Gateway 204, Site Connector 21, Media Worker 43), Vitest (20) | `core/src/test`, `*/tests`, `web/src/**/*.spec.ts` |
| TA-04 | Kịch bản tải | Gatling 3.13.5 (Java DSL) | `tests/load/` |
| TA-05 | Smoke test | Bash, 12 bước | `tests/smoke/smoke.sh` |
| TA-06 | Bộ tương thích ONVIF | pytest marker `compat` | `device-gateway/tests/compat/` |
| TA-07 | Bộ giả lập | RTSP (`tools/rtsp-sim`), ONVIF có PTZ/sự kiện/thẻ SD/đầu ghi/backchannel (`tools/onvif-sim`), chat/Telegram (`tools/chat-mock`), OpenLDAP, Mailpit | `tools/`, `deploy/` |
| TA-08 | Dữ liệu kiểm thử | Bộ tạo dữ liệu demo (`DemoDataSeeder`, profile `demo`); 100 tài khoản tải (`LoadUsers`); tệp mẫu nhập camera | `core/.../demo`, `tests/load` |
| TA-09 | Script diễn tập khôi phục | Bash + Python (`verify-secrets.py`) | `deploy/backup/` |

## 4. Tính năng được kiểm thử

### 4.1 Ma trận ưu tiên kiểm thử

Điểm rủi ro = K × A × 4, quy về thang 100, dựa trên mục 0.7 và mức ưu tiên MoSCoW.

| Ưu tiên | Tính năng | User story | Yêu cầu | Điểm rủi ro |
| --- | --- | --- | --- | --- |
| **Cao** | F-02 Phân quyền vai trò và phạm vi | US-32, US-43 | BR-29, BR-39, SaaS-1..2 | 80 |
| **Cao** | F-06 Xem trực tiếp và lưới | US-08..11 | BR-08..10, BR-34, NFR-01, NFR-03 | 80 |
| **Cao** | F-08 Ghi hình và xem lại | US-16..19, US-22 | BR-14..17, BR-20, NFR-02 | 80 |
| **Cao** | F-04 Kết nối và quản lý camera | US-01..05, US-07 | BR-01..05, BR-07, PNFR-05 | 80 |
| **Cao** | F-01 Xác thực, 2FA, LDAP | US-30, US-31, US-44 | BR-28, BR-36, BR-40, RULE-04/05 | 75 |
| **Cao** | F-09 Xuất clip, lưu trữ, giữ bằng chứng | US-20, US-21 | BR-18, BR-19, RULE-01/02 | 70 |
| **Cao** | F-12 Nền tảng: sao lưu, giám sát, tổng quan, i18n | US-39..41, TS-02/04/07 | NFR-04, NFR-07, NFR-10/11 | 65 |
| **Trung bình** | F-11 Cảnh báo và thông báo | US-34..38, US-42 | BR-31..33, BR-37, BR-38 | 60 |
| **Trung bình** | F-10 Cấu hình camera và mẫu | US-23..29 | BR-21..27, RULE-03 | 60 |
| **Trung bình** | F-03 Nhật ký thao tác | US-33 | BR-30, RULE-06, NFR-09 | 55 |
| **Trung bình** | F-05 Kết nối địa điểm xa | US-06, TS-06 | BR-06 | 50 |
| **Thấp** | F-07 PTZ, preset, chụp ảnh, đàm thoại | US-12..15 | BR-11..13, BR-35 | 40 |

F-07 có PTZ/preset là Must, nhưng điểm rủi ro thấp vì đã có khóa PTZ và kiểm thử tương thích. Đàm thoại hai chiều là Could.

### 4.2 Ma trận tính năng × loại kiểm thử

| Tính năng | Đơn vị | Tích hợp | Hệ thống (e2e) | Chấp nhận | Hồi quy | Hiệu năng | Bảo mật | Trợ năng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-01 Xác thực | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (đăng nhập dưới tải) | ✓ | ✓ |
| F-02 Phân quyền | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (cache quyền) | ✓ | – |
| F-03 Nhật ký | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ | ✓ |
| F-04 Quản lý camera | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (lọc 500 camera) | ✓ | ✓ |
| F-05 Địa điểm xa | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (băng thông tunnel) | ✓ | – |
| F-06 Xem trực tiếp | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| F-07 PTZ, đàm thoại | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ | ✓ |
| F-08 Ghi hình, xem lại | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| F-09 Xuất clip, lưu trữ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (hàng đợi xuất) | ✓ | – |
| F-10 Cấu hình camera | ✓ | ✓ | ✓ | ✓ | ✓ | – | – | ✓ |
| F-11 Cảnh báo | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (bão sự kiện) | – | ✓ |
| F-12 Nền tảng | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

### 4.3 Mô tả tính năng

| Mã | Tính năng | Mô tả | Nội dung kiểm thử | Tiêu chí chấp nhận |
| --- | --- | --- | --- | --- |
| F-01 | Xác thực | Đăng nhập cục bộ/LDAP, khóa tài khoản, 2FA TOTP + mã dự phòng, phiên 12 h, làm mới token | Đăng nhập, khóa/mở, 2FA bắt buộc theo vai trò, mã dự phòng, LDAP và lỗi LDAP, hết phiên, đăng xuất | US-30 AC1–3, US-31 AC1–2, US-44 AC1–3 |
| F-02 | Phân quyền | 10 quyền × 4 loại phạm vi; 5 vai trò hệ thống + vai trò tùy chỉnh; RLS theo Tổ chức | Ma trận vai trò × phạm vi, thu quyền ≤ 60 s, Site Manager không vượt quyền, vai trò hệ thống bất biến | US-32 AC1–3, US-43 AC1–3, INV-10/20–22/27 |
| F-03 | Nhật ký | Nhật ký chỉ thêm, phân vùng tháng, lọc, xuất CSV | Đủ trường; không sửa/xóa được kể cả ở mức CSDL; chặn CSV injection; lưu 12 tháng | US-33 AC1–2, RULE-06, NFR-09 |
| F-04 | Quản lý camera | Dò tìm ONVIF, thêm thủ công, NVR, nhập tệp, cây địa điểm, trạng thái, chuyển camera | Thời gian dò ≤ 30 s, kiểm tra ≤ 10 s, trạng thái ≤ 60 s, lọc ≤ 1 s, chuyển giữ video | US-01..05, US-07 |
| F-05 | Địa điểm xa | Mã kích hoạt 24 h, tunnel WSS 443 chỉ đi ra, nhịp tim | Kích hoạt, mã hết hạn/dùng lại, mất tunnel → ngoại tuyến, đọc RTSP qua tunnel | US-06, TS-06 |
| F-06 | Xem trực tiếp | WHEP + HLS dự phòng, lưới 1/4/9/16, luồng chính/phụ, bố cục, chuyển luồng theo băng thông | First frame, quyền 403, ô ngoại tuyến, HLS dự phòng, bố cục, thu quyền | US-08..11 |
| F-07 | PTZ, chụp ảnh, đàm thoại | PTZ có khóa theo hạng, preset, ảnh PNG, zoom 8x, toàn màn hình, nhấn giữ để nói (G.711 backchannel) | Ẩn khi không hỗ trợ, tranh chấp PTZ, preset, tên tệp ảnh, một người nói mỗi camera, nhật ký thời lượng | US-12..15 |
| F-08 | Ghi hình, xem lại | Ghi liên tục/lịch/sự kiện (đoạn fMP4 60 s), dòng thời gian, tốc độ, đồng bộ 4 camera, nguồn thẻ SD | Pre/post sự kiện, phát ≤ 3 s, nhảy đoạn gần nhất, 16x, lệch ≤ 1 s, nguồn thiết bị | US-16..19, US-22 |
| F-09 | Xuất clip, lưu trữ | Xuất MP4 ≤ 60 phút, liên kết ký 5 phút, hết hạn 24 h; thời gian lưu theo camera; giữ bằng chứng | Giới hạn 60 phút, quyền, liên kết hết hạn, xóa quá hạn bỏ qua đoạn đang giữ | US-20, US-21, RULE-01/02 |
| F-10 | Cấu hình camera | Luồng, hình ảnh, mạng/NTP, vùng chuyển động/che, mẫu, đổi mật khẩu, khởi động lại | Chỉ tham số hỗ trợ, lỗi theo tham số, khôi phục, lệch giờ, giới hạn vùng, áp mẫu một phần | US-23..29, RULE-03 |
| F-11 | Cảnh báo | Mất kết nối camera/địa điểm, dung lượng, chuyển động theo lịch, chống spam, xác nhận/chuyển giao/đóng, trung tâm thông báo | Máy trạng thái Alert, throttle, 4 kênh gửi, thử lại 3 lần, mở xem lại từ cảnh báo | US-34..38, US-42 |
| F-12 | Nền tảng | Tổng quan, sao lưu pgBackRest (chỉ xem), giám sát Prometheus/Grafana/Loki, i18n, responsive, Compose | Số liệu tổng quan khớp, sao lưu ≥ 30 bản, diễn tập khôi phục, báo động khi Core ngừng, 360 px | US-39..41, TS-02, TS-04, TS-07 |

## 5. Tính năng không kiểm thử

| Mã | Tính năng | Lý do | Rủi ro | Kế hoạch sau |
| --- | --- | --- | --- | --- |
| F-NT-01 | Ứng dụng di động native (OOS-1) | Ngoài phạm vi 1.0 | Thấp | Bản sau. Trên di động chỉ kiểm thử web responsive |
| F-NT-02 | Phân tích hình ảnh AI, chuyển động phía máy chủ (OOS-2, OOS-3, `MotionSource.SERVER`) | Ngoài phạm vi 1.0 (CD-05, ADR-11) | Thấp | Bản sau |
| F-NT-03 | Camera analog không qua đầu ghi IP; nâng cấp firmware hàng loạt (OOS-4, OOS-5) | Ngoài phạm vi | Thấp | — |
| F-NT-04 | Tích hợp kiểm soát ra vào, báo cháy; thanh toán (OOS-6, OOS-7) | Ngoài phạm vi | Thấp | — |
| F-NT-05 | Vận hành SaaS nhiều khách hàng (OOS-8) | Bản sau. Cô lập dữ liệu theo Tổ chức (RLS) **vẫn được kiểm thử** vì là ràng buộc SaaS-1..4 | Trung bình | Kiểm thử đa Tổ chức ở bản SaaS |
| F-NT-06 | Khôi phục hệ thống trong ứng dụng (OOS-9) | Theo thiết kế không có chức năng này. Chỉ kiểm thử rằng Admin **không có** nút khôi phục (US-40 AC3); quy trình khôi phục kiểm qua diễn tập | Thấp | Diễn tập hằng quý |
| F-NT-07 | Mã nguồn nội bộ MediaMTX, FFmpeg, chisel, PostgreSQL | Thành phần bên thứ ba. Chỉ kiểm thử tích hợp, cấu hình và hiệu năng | Trung bình | Theo dõi bản vá (G-04) |
| F-NT-08 | Endpoint PoC `/api/v1/poc/media-token`, trang `/poc/player` | Chỉ bật với profile `poc` ở dev. Kiểm thử rằng staging/production **tắt** profile này (SEC-10) | Trung bình | Gỡ trước GA |
| F-NT-09 | Tải trên 2 lần tải đỉnh, kiểm thử phá hủy hạ tầng | Không yêu cầu trong BRS. Chỉ thử tăng đột biến và tắt media node (PERF-06, PERF-10) | Thấp | Khi lên SaaS |
| F-NT-10 | Watermark tên người xuất trên clip (OQ-P03) | Chưa có yêu cầu chốt | Thấp | Khi Pháp chế quyết định |

## 6. Cách tiếp cận

### 6.1 Mức và loại kiểm thử

#### 6.1.1 Kiểm thử đơn vị

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Đội phát triển |
| **Công cụ** | JUnit 5 + AssertJ + ArchUnit 1.4 (Core); pytest ≥ 8.3 (Gateway, Site Connector, Media Worker); Vitest 3 + jsdom (Web) |
| **Độ phủ** | ≥ 80% dòng lệnh cho lớp domain/application của Core và module Python; ≥ 70% cho `web/src/player`, `services`, `stores`. Hiện **chưa đo**: cần thêm JaCoCo, `pytest-cov`, `vitest --coverage` (công việc T-03, mục 10) |
| **Tiêu chí vào** | Mã đã review; build và lint đạt (ruff, mypy, eslint `--max-warnings=0`, vue-tsc) |
| **Tiêu chí ra** | 100% test đạt; độ phủ đạt ngưỡng; ArchitectureTest đạt (không phụ thuộc vòng, gọi chéo module chỉ qua gói `api`) |
| **Trọng tâm** | Mã hóa phong bì (CryptoServiceTest), token media HMAC, chống CSV injection, backoff của outbox, chuyển luồng theo băng thông, đồng bộ phát lại, lấy mẫu lại PCM, G.711, phân tích SOAP (XXE, WS-Security), chuẩn hóa sự kiện chuyển động, xử lý kênh NVR, tham số vùng |
| **Giả lập phụ thuộc** | Device Gateway dùng bộ giả lập ONVIF/RTSP trong tiến trình. Web giả lập `RTCPeerConnection` và `fetch` |

Hiện có: Core 5 lớp / 24 test; Gateway 122 hàm (204 test sau khi tham số hóa); Site Connector 21; Media Worker 43; Web 20.

#### 6.1.2 Kiểm thử tích hợp

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Đội phát triển, QA review ca kiểm thử |
| **Công cụ** | Spring Boot Test + Testcontainers 1.20 (PostgreSQL 16, RabbitMQ 3.13, Redis 7.4, OpenLDAP 2.6), MockMvc, Awaitility; pytest với bộ giả lập; `tests/integration` (tunnel) |
| **Phạm vi** | API REST Core với CSDL thật; RLS; outbox → RabbitMQ; Core ↔ Gateway (cổng `DeviceControlPort`); LDAP; Worker ↔ MinIO/FFmpeg; Site Connector ↔ tunnel |
| **Tiêu chí vào** | Kiểm thử đơn vị đạt; migration Flyway chạy sạch trên CSDL rỗng |
| **Tiêu chí ra** | 100% test tích hợp đạt (`./gradlew integrationTest`); không có test chập chờn chưa xử lý |
| **Trọng tâm** | Hợp đồng API, mã lỗi RFC 7807, quyền theo phạm vi, ghi nhật ký đúng một lần, idempotent theo `messageId`, máy trạng thái |

**Ca kiểm thử tích hợp API:**

| Mã | Endpoint | Loại | Kiểm tra | Kết quả mong đợi | Bằng chứng / trạng thái |
| --- | --- | --- | --- | --- | --- |
| INT-API-01 | `POST /api/v1/auth/login` | Dương | 200, access token, cookie `camhub_refresh` HttpOnly/Secure/SameSite=Strict, nhật ký LOGIN | Đăng nhập thành công | AuthIT – tự động, đạt |
| INT-API-02 | `POST /api/v1/auth/login` | Âm | 401 INVALID_CREDENTIALS, ghi LOGIN_FAILED; lần sai thứ 5 → 423 ACCOUNT_LOCKED | Từ chối, khóa tài khoản | AuthIT – tự động, đạt |
| INT-API-03 | `POST /api/v1/auth/2fa/verify` | Dương/âm | Mã đúng → token; mã sai → 401 INVALID_2FA_CODE; mã dự phòng dùng lần 2 → 401 | 2FA đúng quy tắc | TwoFactorIT – tự động, đạt |
| INT-API-04 | `POST /api/v1/auth/refresh` | Âm | Phiên bị thu hồi hoặc quá 12 h không hoạt động → 401 SESSION_EXPIRED, xóa cookie | Hết phiên | AuthIT – tự động, đạt |
| INT-API-05 | `GET /api/v1/cameras?q=&status=` | Dương, hiệu năng | 500 camera, lọc theo tên/trạng thái < 1 s; chỉ trả camera trong phạm vi | US-05 AC1 | AccessIT – tự động, đạt |
| INT-API-06 | Mọi endpoint camera của Tổ chức khác | Âm | Truy vấn trực tiếp CSDL không qua bộ lọc vẫn bị RLS chặn; API trả 404 | Cô lập Tổ chức | TenancyIT – tự động, đạt |
| INT-API-07 | `PUT /api/v1/users/{id}/assignments` | Âm | Site Manager gán phạm vi ngoài địa điểm → 403 ASSIGNMENT_OUT_OF_SCOPE; gán vai trò vượt quyền → 403 ROLE_EXCEEDS_OWN_PERMISSIONS | Không leo thang quyền | AccessIT – tự động, đạt |
| INT-API-08 | `POST /api/v1/cameras/{id}/live-session` | Dương/âm | Có VIEW_LIVE → URL WHEP/HLS + token 60 s; không có → 403; token sai path/hành động/hết hạn bị `/internal/media/authorize` từ chối | US-08 AC2, TS-03 AC2 | LiveIT, MediaTokenServiceTest – tự động, đạt |
| INT-API-09 | `POST /api/v1/cameras/test-connection` | Âm | Sai mật khẩu → 422 AUTH_FAILED; không tới được → 422 UNREACHABLE; Gateway ngừng → 503 GATEWAY_UNAVAILABLE | Mã lỗi đúng | DeviceIT – tự động, đạt |
| INT-API-10 | `POST /api/v1/cameras/import` | Dương/âm | CSV có dòng thiếu IP, sai khu vực, ô có `;` trong ngoặc kép → nhập 2/4, báo lỗi đúng dòng, không trả mật khẩu về | US-07 AC1 | CameraImportIT – tự động, đạt |
| INT-API-11 | `POST /api/v1/cameras/{id}/ptz` | Tranh chấp | Hai người khác hạng: hạng thấp → 409 PTZ_LOCKED kèm tên người giữ; hạng cao chiếm quyền, người kia nhận `/user/queue/ptz` | US-12 AC2 | LiveIT – tự động, đạt |
| INT-API-12 | `POST /api/v1/cameras/{id}/talk` | Dương/âm | Operator nói 4,2 s → ghi TALK_AUDIO có `durationSeconds`; Viewer → 403; camera không có loa → 409 TALK_NOT_SUPPORTED; người thứ hai → 409 TALK_BUSY | US-15 AC1 | TalkIT, `test_talk.py` – tự động, đạt (giả lập) |
| INT-API-13 | `PUT /api/v1/cameras/{id}/recording-policy` | Dương/âm | Lịch không hợp lệ → 400 INVALID_SCHEDULE; pre/post ngoài 0–60 / 0–600 s → 400 | US-16 | RecordingIT – tự động, đạt |
| INT-API-14 | `POST /api/v1/retention/purge` | Dương | Xóa đoạn quá hạn theo thời gian lưu riêng camera; **không** xóa đoạn trùng EvidenceHold chưa giải phóng | US-21 AC1–2, INV-06/07 | RecordingIT – tự động, đạt |
| INT-API-15 | `POST /api/v1/exports` | Âm, idempotent | > 60 phút → 400 EXPORT_RANGE_TOO_LONG (không tạo job); vượt hàng đợi → 429; worker nhận trùng `ExportRequested` → chỉ 1 tệp | US-20 AC2 | ExportIT, `test_consumer.py` – tự động, đạt |
| INT-API-16 | `GET /api/v1/exports/{id}/file` | Bảo mật | Chữ ký sai/hết 5 phút → 403 LINK_INVALID; quá 24 h → 410 EXPORT_EXPIRED | US-20 AC4 | ExportIT – tự động, đạt |
| INT-API-17 | `POST /api/v1/alerts/{id}/close` | Máy trạng thái | Thiếu kết quả → 400 OUTCOME_REQUIRED; người không phụ trách → 409 NOT_ASSIGNEE; đóng lần 2 → 409 ALERT_CLOSED | INV-14, INV-28 | MotionAlertIT – tự động, đạt |
| INT-API-18 | Luồng `MotionDetected` → Alert | Tích hợp sự kiện | Trong lịch → 1 cảnh báo + 1 thông báo; 3 sự kiện trong 2 phút → vẫn 1 thông báo, sự kiện đánh dấu trên dòng thời gian | US-36 AC1–3, INV-15 | MotionAlertIT – tự động, đạt |
| INT-API-19 | Outbox → RabbitMQ | Phục hồi | Broker ngừng, giao dịch vẫn commit; broker hồi phục → sự kiện được gửi bù | TS-04 AC2 | OutboxIT – tự động, đạt |
| INT-API-20 | `GET /api/v1/audit-logs/export` | Bảo mật | Ô bắt đầu bằng `=`, `+`, `-`, `@` được thêm tiền tố; bản thân việc xuất được ghi nhật ký | US-33, S-06 | AuditCsvTest, AuditIT – tự động, đạt |
| INT-API-21 | UPDATE/DELETE trên `audit_log` | Âm (CSDL) | Role ứng dụng không có quyền; trigger chặn | US-33 AC2, RULE-06 | AuditIT – tự động, đạt |
| INT-API-22 | `POST /api/v1/connector-agent/activate` | Âm | Mã sai → 404 INVALID_CODE; quá 24 h → 410 ACTIVATION_EXPIRED; dùng lại → 409 ALREADY_ACTIVATED | US-06 AC2 | ConnectorIT – tự động, đạt |
| INT-API-23 | `POST /api/v1/auth/login` (LDAP) | Dương/âm | Lần đầu tạo người dùng, vai trò theo nhóm; vô hiệu trong thư mục → DISABLED; LDAP ngừng → 503, Admin cục bộ vẫn vào | US-44 AC1–3 | DirectoryIT – tự động, đạt (OpenLDAP) |
| INT-API-24 | `DELETE /api/v1/roles/{id}` | Âm | Vai trò đang dùng → 409 ROLE_IN_USE kèm số người; vai trò hệ thống → 409 SYSTEM_ROLE | US-43 AC2–3 | RoleTemplateIT – tự động, đạt |
| INT-API-25 | `POST /api/v1/config-templates/{id}/apply` | Một phần | Áp cho camera ONVIF + RTSP → kết quả theo camera; áp lại chỉ phần lỗi | US-27 AC1 | RoleTemplateIT – tự động, đạt |
| INT-API-26 | STOMP SUBSCRIBE `/topic/org/{orgKhác}/…` | Âm | AccessDenied | Cô lập thời gian thực | **Kế hoạch** (T-05) |
| INT-API-27 | `GET /api/v1/camera-groups`, `/camera-groups/{id}/cameras`, `/retention-policy` | Âm | Kiểm tra lại: các endpoint này không kiểm quyền (mọi người dùng đã đăng nhập đều đọc được). Xác nhận chỉ lộ dữ liệu cùng Tổ chức; quyết định có cần giới hạn theo phạm vi không | Không lộ dữ liệu ngoài phạm vi | **Kế hoạch** (SEC-02) |

#### 6.1.3 Kiểm thử hệ thống (đầu-cuối)

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | QA |
| **Công cụ** | Playwright 1.50.1 (Chrome) trong container gắn mạng `camhub_backend` (`tests/e2e/run.sh`); bộ giả lập RTSP/ONVIF; Mailpit; chat-mock |
| **Phạm vi** | Hành trình nghiệp vụ đầu-cuối qua giao diện thật, Nginx, Core, Gateway, MediaMTX, Worker |
| **Tiêu chí vào** | Kiểm thử tích hợp đạt; smoke test 12/12; dữ liệu demo đã nạp; bộ giả lập chạy (`dev.sh sim`) |
| **Tiêu chí ra** | 100% ca e2e đạt (trừ ca bỏ qua có điều kiện, phải ghi lý do); 0 lỗi mức 1–2 mở |
| **Trọng tâm** | Hành trình người dùng, quyền hiển thị trên giao diện, thời gian thực (WebSocket), đo thời gian thực tế trên trình duyệt |

**Ca kiểm thử đầu-cuối:**

| Mã | Hành trình | Các bước | Kết quả mong đợi | Bằng chứng / trạng thái |
| --- | --- | --- | --- | --- |
| E2E-01 | HT-01 Lắp đặt địa điểm mới (SC-01) | 1. Admin tạo bộ kết nối, lấy mã. 2. Site Connector kích hoạt. 3. Technician dò tìm qua tunnel. 4. Thêm hàng loạt (1 camera sai mật khẩu). 5. Áp mẫu cấu hình | Bộ kết nối "Trực tuyến"; camera hiện ≤ 30 s; camera sai mật khẩu có nút thử lại; kết quả mẫu theo từng camera | s6 (US-06), s2 (US-01), s8 (US-27) – tự động, đạt từng đoạn. **Chuỗi liền mạch: Kế hoạch** |
| E2E-02 | HT-02 Xử lý cảnh báo đêm (SC-02) | 1. Bộ giả lập phát MotionAlarm trong lịch. 2. Telegram + chuông + toast. 3. Operator mở cảnh báo, xem lại từ thời điểm trừ 10 s. 4. Chuyển sang xem trực tiếp, PTZ tới preset. 5. Chuyển giao cho Minh. 6. Đóng với kết quả | Thông báo có tên, giờ, ảnh, liên kết; lịch sử trạng thái đầy đủ; không đóng được khi thiếu kết quả | s7 (US-36/37/38/42), s3 (US-12) – tự động, đạt |
| E2E-03 | HT-03 Trích xuất bằng chứng (SC-03) | 1. Site Manager chọn ngày trên lịch. 2. Phát dòng thời gian (≤ 3 s), 16x, tạm dừng. 3. Xem đồng bộ 4 camera. 4. Giữ bằng chứng. 5. Xuất clip > 60 phút (bị chặn), rồi 5 phút. 6. Tải MP4. 7. Admin kiểm nhật ký | MP4 phát được; nhật ký có VIEW_PLAYBACK, EXPORT_VIDEO, HOLD_EVIDENCE | s4, s5, s8 (US-17..21) – tự động, đạt |
| E2E-04 | HT-04 Camera mất kết nối (SC-04) | 1. Dừng luồng giả lập của 1 camera. 2. Tổng quan cập nhật ≤ 60 s. 3. Sau thời gian chờ → CAMERA_OFFLINE. 4. Khởi động lại camera từ xa. 5. Camera trực tuyến lại → cảnh báo RECOVERED. 6. Xem lại từ thẻ SD | Trạng thái và cảnh báo đúng thứ tự; tự đóng khi hồi phục | s2 (US-04), s6 (US-28), s7 (US-22) – đạt từng phần. **US-34 chưa có e2e: Kế hoạch** |
| E2E-05 | Đăng nhập, 2FA, hết phiên | 1. Mở `/` khi chưa đăng nhập → trang đăng nhập. 2. Sai mật khẩu. 3. Đăng nhập, quay lại trang cũ. 4. Viewer với 2FA bắt buộc: QR, mã dự phòng. 5. Tải lại trang giữ phiên. 6. Đăng xuất | Đúng trang đích theo vai trò; mã dự phòng dùng 1 lần | s1, s2 – tự động, đạt (bổ sung 27/10 sau lỗi màn hình đen ở `/`) |
| E2E-06 | Xem trực tiếp lưới 4/9/16 | 1. Chọn lưới 4, thêm 4 camera. 2. Lưới 9 → luồng phụ. 3. Nhấp đúp → luồng chính. 4. Lưu bố cục, đăng nhập lại | First frame ≤ 3 s; bố cục mở lại | s3 – tự động, đạt |
| E2E-07 | Thu quyền khi đang xem | 1. Lan đang xem camera. 2. Admin thu quyền. 3. ≤ 60 s ô hiện "Không có quyền" | Luồng dừng | s3 (US-32 AC2) – tự động, đạt |
| E2E-08 | Cấu hình camera | Luồng (chỉ tham số hỗ trợ), hình ảnh (khôi phục), mạng (cảnh báo đổi IP, lệch 7 s), vùng (vượt giới hạn bị chặn), đổi mật khẩu 2 camera, khởi động lại | Kết quả theo từng tham số / camera | s4, s5, s6, s7, s8 – tự động, đạt |
| E2E-09 | Quản trị người dùng, vai trò, LDAP | Nhân bản vai trò "Bảo vệ ca đêm"; Site Manager tạo người dùng trong phạm vi; người dùng AD đăng nhập thành Site Manager | Vai trò hệ thống khóa; phạm vi đúng | s1, s8 – tự động, đạt (LDAP bỏ qua khi thiếu bí mật) |
| E2E-10 | Nhập camera từ tệp | Tệp 3 dòng (1 lỗi) → xem trước → nhập 2, bỏ 1, báo cáo từng dòng | Hộp thoại giữ mở, hiện báo cáo | s9 – tự động, đạt |
| E2E-11 | Đàm thoại hai chiều | Chrome có micro giả, giữ nút 2,5 s → bộ giả lập nhận > 80 gói G.711; nhật ký TALK_AUDIO > 1,5 s | Âm thanh tới loa | s9 – tự động, đạt (giả lập) |
| E2E-12 | Nền tảng | Tổng quan khớp số liệu; tab sao lưu chỉ xem, không có nút khôi phục; cảnh báo dung lượng qua WS, chuông, email | US-35/39/40 | s5 – tự động, đạt |
| E2E-13 | Responsive và ngôn ngữ | 5 trang ở 360 × 780 không cuộn ngang; chuyển tiếng Anh toàn giao diện | US-41 | s1 – tự động, đạt |
| E2E-14 | HLS dự phòng | Chặn UDP 8189 (WebRTC) → ô tự chuyển HLS, vẫn có hình | US-08 AC3, PNFR-02 | **Kế hoạch** (T-06) |
| E2E-15 | Ô camera ngoại tuyến | Camera mất kết nối trong lưới → ô hiện trạng thái, không màn đen | US-09 AC3 | **Kế hoạch** |

#### 6.1.4 Kiểm thử chấp nhận (UAT)

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Product Owner (chủ trì), người dùng pilot theo 5 vai trò, QA hỗ trợ |
| **Công cụ** | Kiểm thử thủ công theo kịch bản Gherkin (`tests/e2e/features`); biểu mẫu kết quả UAT; khảo sát hài lòng 1–5 |
| **Phạm vi** | Toàn bộ US-01..44 trên staging với camera thật của địa điểm pilot (20–50 camera, ≥ 3 hãng) |
| **Tiêu chí vào** | Kiểm thử hệ thống đạt trên staging; 0 lỗi mức 1–2 mở; người dùng pilot đã được đào tạo (mục 13) |
| **Tiêu chí ra** | 100% AC Must đạt; AC Should đạt hoặc có ngoại lệ PO ký; điểm hài lòng ≥ 4/5 (AC-B5); biên bản UAT ký |
| **Trọng tâm** | Giá trị nghiệp vụ, tính dễ dùng với camera và mạng thật |

**Ca kiểm thử chấp nhận.** Mỗi US có một ca UAT. Kết quả mong đợi là toàn bộ AC của US đó (mục 0.6). Cột "Kiểm trước tự động" cho biết AC đã có bằng chứng tự động trên dev.

| Mã | US | Kịch bản | Người thực hiện | Kiểm trước tự động | Kết quả UAT |
| --- | --- | --- | --- | --- | --- |
| UAT-01 | US-01 | Dò tìm camera thật trong LAN pilot | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-02 | US-02 | Thêm thủ công camera ONVIF và RTSP | Technician | Đạt | Chưa chạy |
| UAT-03 | US-03 | Thêm NVR thật, chọn kênh | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-04 | US-04 | Rút mạng một camera, quan sát trạng thái | Operator | Đạt | Chưa chạy |
| UAT-05 | US-05 | Tổ chức cây, lọc, chuyển camera | Admin | Đạt | Chưa chạy |
| UAT-06 | US-06 | Kích hoạt bộ kết nối tại địa điểm thật sau NAT | Admin, IT | Đạt (stub) | Chưa chạy |
| UAT-07 | US-07 | Nhập danh sách camera của địa điểm từ Excel | Technician | Đạt | Chưa chạy |
| UAT-08 | US-08 | Xem trực tiếp trên 4 trình duyệt; mạng chặn UDP | Viewer, Operator | Đạt (AC3 chưa) | Chưa chạy |
| UAT-09 | US-09 | Lưới 16 trên màn hình phòng giám sát | Operator | Đạt | Chưa chạy |
| UAT-10 | US-10 | Lưu 3 bố cục ca trực, mở lại | Operator | Đạt | Chưa chạy |
| UAT-11 | US-11 | Xem từ chi nhánh băng thông thấp | Operator | Chỉ có unit test | Chưa chạy |
| UAT-12 | US-12 | PTZ camera thật; hai người tranh chấp | Operator | Đạt (giả lập) | Chưa chạy |
| UAT-13 | US-13 | Lưu và gọi preset | Operator | Đạt (IT) | Chưa chạy |
| UAT-14 | US-14 | Chụp ảnh, phóng to 8x, toàn màn hình | Operator | Đạt | Chưa chạy |
| UAT-15 | US-15 | Nói qua loa camera thật | Operator | Đạt (giả lập) | Chưa chạy |
| UAT-16 | US-16 | Ghi theo lịch và theo sự kiện | Admin | Đạt | Chưa chạy |
| UAT-17 | US-17 | Tìm đoạn video theo dòng thời gian | Operator, Site Manager | Đạt | Chưa chạy |
| UAT-18 | US-18 | Tua, tạm dừng, từng khung hình | Operator | Đạt | Chưa chạy |
| UAT-19 | US-19 | Xem lại đồng bộ 4 camera | Operator | Đạt | Chưa chạy |
| UAT-20 | US-20 | Xuất clip, mở bằng VLC / Windows Media Player | Site Manager | Đạt | Chưa chạy |
| UAT-21 | US-21 | Đặt thời gian lưu, giữ bằng chứng | Admin, Site Manager | Đạt | Chưa chạy |
| UAT-22 | US-22 | Xem lại từ thẻ SD / NVR thật | Operator | Đạt (giả lập) | Chưa chạy |
| UAT-23 | US-23 | Đổi độ phân giải/bitrate camera thật | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-24 | US-24 | Chỉnh hình ảnh, khôi phục | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-25 | US-25 | Đổi NTP, kiểm lệch giờ | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-26 | US-26 | Vẽ vùng che, kiểm trên video trực tiếp **và video ghi** | Technician, Viewer | Đạt (giả lập; AC1 phần video ghi chưa kiểm) | Chưa chạy |
| UAT-27 | US-27 | Áp mẫu cho 10 camera nhiều hãng | Technician | Đạt | Chưa chạy |
| UAT-28 | US-28 | Đổi mật khẩu hàng loạt, khởi động lại | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-29 | US-29 | Kiểm tùy chọn theo khả năng từng hãng | Technician | Đạt (giả lập) | Chưa chạy |
| UAT-30 | US-30 | Đăng nhập, khóa, hết phiên | Mọi vai trò | Đạt | Chưa chạy |
| UAT-31 | US-31 | Cài 2FA bằng app điện thoại | Admin | Đạt | Chưa chạy |
| UAT-32 | US-32 | Kiểm quyền theo phạm vi với 5 vai trò | Admin | Đạt | Chưa chạy |
| UAT-33 | US-33 | Tra cứu, xuất nhật ký | Admin | Đạt | Chưa chạy |
| UAT-34 | US-34 | Cảnh báo mất kết nối camera và địa điểm | Operator | Đạt (IT, chưa có e2e) | Chưa chạy |
| UAT-35 | US-35 | Cảnh báo dung lượng | Admin | Đạt | Chưa chạy |
| UAT-36 | US-36 | Nhận cảnh báo chuyển động qua Telegram/Zalo/email thật | Operator | Đạt (chat-mock; Zalo chưa thật) | Chưa chạy |
| UAT-37 | US-37 | Xác nhận, chuyển giao, đóng cảnh báo | Operator | Đạt | Chưa chạy |
| UAT-38 | US-38 | Mở xem lại từ cảnh báo | Operator | Đạt | Chưa chạy |
| UAT-39 | US-39 | Đọc bảng tổng quan đầu ca | Admin | Đạt | Chưa chạy |
| UAT-40 | US-40 | Xem tình trạng sao lưu; IT diễn tập khôi phục | Admin, IT | Đạt (dev) | Chưa chạy |
| UAT-41 | US-41 | Dùng trên điện thoại, tiếng Anh | Viewer | Đạt | Chưa chạy |
| UAT-42 | US-42 | Trung tâm thông báo | Operator | Đạt | Chưa chạy |
| UAT-43 | US-43 | Tạo vai trò tùy chỉnh cho bảo vệ | Admin | Đạt | Chưa chạy |
| UAT-44 | US-44 | Đăng nhập bằng AD của tổ chức | Mọi vai trò | Đạt (OpenLDAP) | Chưa chạy |

#### 6.1.5 Kiểm thử hồi quy

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Đội phát triển (đơn vị/tích hợp), QA (e2e) |
| **Công cụ** | GitHub Actions (`.github/workflows/ci.yml`), Gradle, pytest, Vitest, Playwright |
| **Phạm vi** | Toàn bộ chức năng; ưu tiên tính năng rủi ro Cao (mục 4.1) |
| **Kích hoạt** | Mỗi pull request (REG-01..06); hằng đêm và trước mỗi lần phát hành (REG-07..09); khi sửa lỗi thì thêm test tái hiện lỗi vào bộ phù hợp |
| **Cách làm** | Hồi quy tự động toàn bộ + smoke sau mỗi lần triển khai. Lỗi đã sửa phải có test tự động tương ứng trước khi đóng |
| **Tần suất** | REG-01..06: mỗi PR. REG-07..08: hằng đêm và trước phát hành. REG-09..10: trước mỗi cổng (G2, G3) |

**Bộ hồi quy:**

| Mã | Phạm vi | Số test | Thời gian | Tự động | Trong CI |
| --- | --- | --- | --- | --- | --- |
| REG-01 | Core đơn vị + kiến trúc (`./gradlew test`) | 24 | < 1 phút | ✓ | ✓ (chưa chạy lần nào: repo chưa đẩy) |
| REG-02 | Core tích hợp (`./gradlew integrationTest`, Testcontainers) | 83 | Vài phút (đo khi bật CI) | ✓ | ✓ |
| REG-03 | Device Gateway (`pytest`) | 204 | < 1 phút | ✓ | ✓ |
| REG-04 | Site Connector + Media Worker (`pytest`) | 21 + 43 | < 1 phút | ✓ | ✓ |
| REG-05 | Web (`vitest`, lint, typecheck, build) | 20 | ≈ 20 s cho test | ✓ | ✓ |
| REG-06 | Quét thư viện (OWASP Dependency-Check failOnCVSS 7, pip-audit, npm audit high) | — | — | ✓ | ✓ |
| REG-07 | Giao diện đầu-cuối (`tests/e2e/run.sh`, s1–s9) | 51 | Vài chục phút (đo khi bật CI) | ✓ | **Chưa**: cần job hằng đêm với Compose |
| REG-08 | Smoke sau triển khai (`tests/smoke/smoke.sh`) | 12 | < 1 phút | ✓ | **Chưa** |
| REG-09 | Tương thích ONVIF (`pytest -m compat`) trên camera thật | 9 khả năng × số camera | Theo số camera | ✓ | Không (cần LAN có camera) |
| REG-10 | Tải API rút gọn (10 người dùng, 5 phút) | 8 loại yêu cầu | 5 phút | ✓ | Không (chạy trước cổng) |

#### 6.1.6 Kiểm thử hiệu năng

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | QA (hiệu năng), Đội vận hành IT (giám sát hạ tầng) |
| **Công cụ** | Gatling 3.13.5 (API); Playwright + Chrome headless (`tests/load/first-frame.*`, `tools/poc-bench`) đo first frame; `tools/rtsp-sim` (luồng giả lập); `docker stats` (`tests/load/sample-stats.sh`), Prometheus/Grafana (tài nguyên); tc/netem (giả lập băng thông) |
| **Loại kiểm thử** | Tải (load), độ bền (soak), tăng đột biến (spike), chịu lỗi thành phần, đo first frame trên trình duyệt |
| **Tiêu chí vào** | Bản build ổn định đã qua hồi quy. Giám sát bật. Dữ liệu tải nạp sẵn (`LoadUsers`: 100 Operator). Không chạy song song việc khác trên cùng máy |
| **Tiêu chí ra** | Đạt mọi chỉ tiêu cột SLA trên **staging** (dev chỉ dùng làm cận trên) |
| **Chỉ số chính** | Thông lượng (req/s); P50/P95/P99; tỷ lệ lỗi; CPU/RAM từng container; first frame P95; lệch đồng bộ phát lại |

**Kịch bản hiệu năng:**

| Mã | Loại | Tải | Thời gian | Chỉ tiêu | SLA | Kết quả / trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| PERF-01 | Tải API | 100 Operator đồng thời, tăng dần trong 120 s; mỗi người: đăng nhập, danh sách camera, 4 phiên xem trực tiếp mỗi ≈ 50 s, đếm cảnh báo mở/thông báo chưa đọc, xem lại ngẫu nhiên, danh sách cảnh báo | 60 phút | Lỗi < 1%; P95 toàn cục < 500 ms; P95 phiên xem trực tiếp < 300 ms | NFR-03 (phần API) | **Đạt (dev, 27/10/2026)**: 46.701 yêu cầu, 0 lỗi; P95 toàn cục 28 ms (P99 67 ms, max 452 ms); phiên xem trực tiếp P95 23 ms; đăng nhập P95 232 ms; 12,4 req/s. Core CPU trung bình 10%, đỉnh 64%, RAM 536 → 594 MiB. Lần chạy đầu (26–27/10) dừng ở phút 13 do Docker Desktop sập, không do ứng dụng. Gọi thẳng `core:8080`, không qua Nginx. Staging: **Kế hoạch** |
| PERF-02 | First frame trên trình duyệt | 1/4/9/16/20 luồng cùng lúc trong 1 tab, 3 lượt | Mỗi lượt 10 s | First frame P95 ≤ 3 s (lưới ≥ 9 dùng luồng phụ) | NFR-01 | PoC 23/10 (dev): P95 0,5–3,0 s; 150/150 luồng phát; 1/15 lượt 3.031 ms ở 16 luồng → **đạt có điều kiện**. e2e s3: lưới 4 ≤ 3 s, đạt. Đo lại trên staging: **Kế hoạch** |
| PERF-03 | Thời gian bắt đầu xem lại | Phát từ một mốc trên dòng thời gian | — | Bắt đầu phát ≤ 3 s | NFR-02 | e2e s4 đạt (ngưỡng 3 s + 1,5 s dung sai trình duyệt). Dưới tải: nằm trong PERF-01 ("Phiên xem lại") |
| PERF-04 | Quy mô ghi hình | 500 camera ghi liên tục (50 camera thật + 450 luồng giả lập, 1080p H.264/H.265) + 100 người dùng PERF-01 | 60 phút | CPU media node < 70%; không mất đoạn (đủ 60 đoạn/camera/giờ); hook segment 100%; first frame vẫn đạt PERF-02 | NFR-03, AR-01 | **Chặn**: cần staging (D-01) và camera thật (D-04). Dev mới đo 20 luồng (260/260 hook đạt) |
| PERF-05 | Độ bền | Như PERF-04 | 24 giờ | RAM Core/Gateway không tăng > 10% sau giờ thứ 2; không rò rỉ phiên WHEP; nhật ký và outbox không ùn | NFR-04 | **Kế hoạch** (staging) |
| PERF-06 | Tăng đột biến | 0 → 100 lần đăng nhập trong 10 s (đầu ca), rồi 100 → 0 | 10 phút | Không lỗi 5xx; P95 đăng nhập < 1 s; hồi phục bình thường ≤ 1 phút. Theo dõi 429 do giới hạn 30 r/s mỗi IP của Nginx (G-03) | NFR-03 | **Kế hoạch** |
| PERF-07 | Chuyển mã H.265 → H.264 | Tăng dần số phiên chuyển mã đồng thời | — | Xác định giới hạn phiên của máy staging (dev: 0,6–1,1 lõi mỗi phiên 720p, giới hạn ≈ số lõi ÷ 2); vượt giới hạn phải từ chối có thông báo, không làm sập media node | PNFR-03, PR-01 | Dev đạt (PoC). Staging: **Kế hoạch** |
| PERF-08 | Băng thông thấp | tc/netem giới hạn 1 Mbps, trễ 150 ms, mất gói 2% | — | Sau 5 s tự chuyển luồng phụ và hiện nhãn "chất lượng thấp"; chế độ thủ công không tự chuyển | US-11, PR-03 | Mới có unit test (`AdaptiveStream.spec.ts`). **Kế hoạch** |
| PERF-09 | Lọc camera | 500 camera trong CSDL | — | Lọc theo tên/trạng thái < 1 s | US-05 AC1 | AccessIT – tự động, đạt |
| PERF-10 | Chịu lỗi media node | Tắt container MediaMTX trong 10 phút khi đang ghi | 10 phút | Báo động vận hành; camera có thẻ SD vẫn ghi; xem lại từ thiết bị được; MediaMTX tự khởi động lại và tiếp tục ghi | NFR-12, AR-03 | **Kế hoạch** |
| PERF-11 | Hiệu năng giao diện | Lighthouse trên `/login`, `/dashboard`, `/live`, `/playback` | — | LCP < 2,5 s; TBT < 200 ms (máy văn phòng, LAN) | NFR-01 (cảm nhận) | **Kế hoạch** |
| PERF-12 | Độ trễ trực tiếp | Đồng hồ hiển thị trong luồng giả lập so với đồng hồ máy xem | — | Độ trễ đầu-cuối ≤ 2 s trong LAN | NFR-01 | PoC dev: đạt. Staging với camera thật: **Kế hoạch** |

#### 6.1.7 Kiểm thử bảo mật

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Đội phát triển (tự rà soát, SAST/SCA), nhà cung cấp pentest độc lập (DAST/pentest), QA (kiểm lại) |
| **Công cụ** | OWASP Dependency-Check, pip-audit, npm audit, OSV (SCA); OWASP ZAP (DAST tự động); Burp Suite (pentest thủ công); testssl.sh; kiểm thử quyền bằng tích hợp (Testcontainers) |
| **Loại kiểm thử** | SCA, cấu hình, kiểm thử quyền tự động, DAST, pentest theo OWASP ASVS 4.0 L2 |
| **Tiêu chí vào** | Ứng dụng chạy trên staging với cấu hình giống production (không có profile `poc`, `demo`); tài khoản cho 5 vai trò ở 2 Tổ chức / 2 địa điểm |
| **Tiêu chí ra** | 0 lỗi Nghiêm trọng/Cao mở; lỗi Trung bình có kế hoạch sửa hoặc chấp nhận rủi ro có chữ ký (PNFR-08, AC-B4) |

Đã có trên dev: tự rà soát ngày 26/10 ([security-review.md](security-review.md)):

| Mức | Phát hiện | Trạng thái |
| --- | --- | --- |
| Cao | S-01 | Đã sửa |
| Trung bình | S-02 | Đã sửa |
| Thấp | S-03..06 | Đã sửa |
| — | S-07 | Chấp nhận rủi ro |
| — | G-01..04 | Mở |

**Ca kiểm thử bảo mật:**

| Mã | Hạng mục | Mô tả | Kết quả mong đợi | Trạng thái |
| --- | --- | --- | --- | --- |
| SEC-01 | Xác thực (ASVS V2, V3) | Brute force (khóa sau 5 lần, 423); chính sách mật khẩu ≥ 10 ký tự; 2FA bắt buộc theo vai trò; mã dự phòng 1 lần; cookie refresh HttpOnly/Secure/SameSite=Strict; xoay token; thu hồi phiên khi đổi mật khẩu/vô hiệu; hết phiên 12 h | Không vượt qua được | Tự động đạt (AuthIT, TwoFactorIT). Pentest: Kế hoạch |
| SEC-02 | Phân quyền (V4): IDOR, leo thang quyền | Ma trận 5 vai trò × 4 loại phạm vi × nhóm endpoint (mục 0.5); đổi ID camera/địa điểm/Tổ chức trong URL; Site Manager gán quyền vượt mình; đăng ký STOMP kênh Tổ chức khác; 3 endpoint đọc không kiểm quyền (INT-API-27) | 403/404, không lộ dữ liệu | Phần lớn tự động đạt (AccessIT, TenancyIT). INT-API-26/27: Kế hoạch |
| SEC-03 | Chèn mã (V5) | SQL injection trên tham số lọc/sắp xếp; XSS lưu trữ qua tên camera/ghi chú cảnh báo/tên vai trò; CSV injection khi xuất nhật ký; XXE trong SOAP ONVIF; tệp nhập (CSV/XLSX) độc hại | Mọi đầu vào được xử lý an toàn | CSV, XXE: tự động đạt. XSS/SQLi: DAST Kế hoạch |
| SEC-04 | Bảo vệ dữ liệu (V6, V8) | Mật khẩu camera/LDAP/TOTP mã hóa AES-256-GCM, không bao giờ trả về trình duyệt; mật khẩu người dùng Argon2id; sao lưu giải mã được chỉ với khóa chính; URL camera không lộ ra trình duyệt | Dữ liệu được mã hóa | Tự động đạt (CryptoServiceTest, CameraImportIT, diễn tập 4b) |
| SEC-05 | Bảo mật API và luồng media | Token media HMAC 60 s gắn path và hành động; MediaMTX hỏi Core mỗi phiên; liên kết tải clip ký 5 phút; `/internal`, `/actuator` trả 404 từ ngoài; Gateway yêu cầu `X-Internal-Token`; giới hạn 30 r/s | Không truy cập trái phép | Tự động đạt (MediaTokenServiceTest, smoke, `test_security.py`) |
| SEC-06 | Header và TLS (V9, V14) | TLS 1.2/1.3 (testssl.sh); HSTS; CSP `script-src 'self'`; X-Frame-Options DENY; Permissions-Policy; source map trả 404; header trên `/assets/` (thiếu X-Frame-Options/Referrer-Policy: đánh giá, rủi ro thấp vì là tệp tĩnh) | Header đúng cấu hình | Tự rà soát đạt (S-03..05). Chứng chỉ thật G-01: Mở |
| SEC-07 | Nhật ký và giám sát (V7) | Mọi hành động trong `AuditAction` được ghi; IP lấy từ `$remote_addr`, không giả mạo được qua X-Forwarded-For; nhật ký không sửa/xóa được; không ghi mật khẩu vào log | Đầy đủ, toàn vẹn | Tự động đạt (AuditIT, S-02) |
| SEC-08 | Thành phần phụ thuộc (V14.2) | SCA trên Java, Python, npm; image Docker (Trivy) | 0 lỗ hổng CVSS ≥ 7 chưa xử lý | Đạt 26/10 (sau S-01). Đưa vào CI: G-04 Mở. Trivy: Kế hoạch |
| SEC-09 | Bộ kết nối và tunnel | Mã kích hoạt 1 lần, hết hạn 24 h; bí mật connector; chỉ cổng 443 đi ra; không mở cổng vào tại địa điểm | Đúng thiết kế ADR-10 | Tự động đạt (ConnectorIT, `tunnel_test.md` 10/10) |
| SEC-10 | Cấu hình triển khai | Staging/production không bật profile `poc`, `demo`; bí mật nằm trong Docker secrets, không nằm trong image/git; Grafana, RabbitMQ, MinIO console chỉ nghe 127.0.0.1 | Đúng cấu hình | Kế hoạch (danh sách kiểm tra trước GA) |
| SEC-11 | WebSocket đàm thoại | Mã phiên 192 bit dùng 1 lần; hết hạn 30 s nếu chưa nối; giới hạn khung 64 KB; Permissions-Policy `microphone=(self)` | Không chiếm phiên của người khác | Tự động đạt (`test_talk.py`, TalkIT) |
| SEC-12 | Pentest độc lập ASVS L2 | Nhà cung cấp độc lập thử toàn bộ bề mặt (web, API, WS, media, tunnel) | Báo cáo không còn lỗi Nghiêm trọng/Cao | **Chặn**: chưa thuê nhà cung cấp; cần staging |

#### 6.1.8 Kiểm thử trợ năng

BRS/PRD không đặt yêu cầu trợ năng riêng. Kế hoạch này lấy **WCAG 2.1 AA** làm chuẩn tham chiếu nội bộ, vì Operator dùng hệ thống liên tục 8–12 giờ mỗi ca.

Lỗi trợ năng được xếp mức 3–4 (mục 6.5), **không chặn GA**, trừ lỗi làm không dùng được bàn phím ở màn hình xem trực tiếp hoặc cảnh báo.

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | QA |
| **Công cụ** | axe-core qua `@axe-core/playwright` (cần thêm vào `tests/e2e`); NVDA (Windows); VoiceOver (macOS/iOS); Chrome DevTools (độ tương phản) |
| **Chuẩn** | WCAG 2.1 AA |
| **Tiêu chí vào** | Giao diện ổn định, đủ 11 màn hình |
| **Tiêu chí ra** | 0 vi phạm axe `critical`/`serious` trên 11 màn hình ở cả giao diện tối và sáng; ACC-01, ACC-05 đạt trên `/live`, `/alerts` |

| Mã | Hạng mục | Mô tả | Kết quả mong đợi | Trạng thái |
| --- | --- | --- | --- | --- |
| ACC-01 | Bàn phím | Tab qua mọi phần tử tương tác ở 11 màn hình; nút nói giữ được bằng Space; PTZ dùng được bằng bàn phím; phím tắt phát lại (Space, ←/→) | Dùng được hoàn toàn bằng bàn phím | Một phần (e2e có Space ở s4). Kế hoạch |
| ACC-02 | Trình đọc màn hình | Điều hướng bằng NVDA/VoiceOver: cây camera, ô video (tên camera + trạng thái), dòng thời gian, cảnh báo | Mọi phần tử được đọc đúng | Kế hoạch |
| ACC-03 | Độ tương phản | Chữ và nền ở giao diện tối (mặc định) và sáng; nhãn trạng thái (trực tuyến/ngoại tuyến/REC) không chỉ dựa vào màu | Chữ thường ≥ 4,5:1; chữ lớn ≥ 3:1 | Kế hoạch |
| ACC-04 | ARIA | Nhãn, vai trò, `aria-live` cho toast và trạng thái ô video. Hiện thiếu ở Audit, System, Templates, Dashboard | Thuộc tính ARIA đúng | Kế hoạch (đã biết thiếu) |
| ACC-05 | Quản lý focus | Focus vào hộp thoại khi mở (AppModal), trả focus khi đóng, không thoát khỏi hộp thoại bằng Tab | Thứ tự focus hợp lý | Kế hoạch |
| ACC-06 | Thông báo lỗi | Lỗi đăng nhập, lỗi form (thêm camera, người dùng) được trình đọc màn hình đọc | Lỗi được thông báo | Kế hoạch |

#### 6.1.9 Kiểm thử giao diện (UI/UX)

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | QA, Thiết kế |
| **Công cụ** | So sánh thủ công với `design/*.html`; Playwright (ảnh chụp màn hình, viewport); BrowserStack hoặc máy thật cho Safari/iOS |
| **Phạm vi** | 15 route, 24 component, 2 ngôn ngữ, 2 giao diện màu |
| **Tiêu chí vào** | Giao diện đã triển khai trên staging |
| **Tiêu chí ra** | UI-01..10 đạt; sai khác so với thiết kế được Thiết kế chấp nhận |

| Mã | Hạng mục | Mô tả | Kết quả mong đợi | Trạng thái |
| --- | --- | --- | --- | --- |
| UI-01 | So với thiết kế | Đối chiếu 11 màn hình với `design/*.html`: bố cục, thành phần, nhãn, luồng hộp thoại | Đủ chức năng; sai khác về hình thức được ghi nhận và chấp nhận | Kế hoạch |
| UI-02 | Responsive | 360, 768, 1280, 1920 px; lưới trên điện thoại ≤ 4 ô | Không cuộn ngang | Tự động đạt ở 360 px (s1, 5 trang). Còn lại: Kế hoạch |
| UI-03 | Trạng thái thành phần | Mặc định, di chuột, đang chọn, vô hiệu, đang tải, lỗi cho nút/ô video/cây/nút nói | Đủ trạng thái | Kế hoạch |
| UI-04 | Tương tác vi mô | Kéo camera vào lưới, nhấp đúp phóng to, kéo dòng thời gian, vẽ đa giác vùng, nhấn giữ để nói | Phản hồi tức thì, đúng | Tự động đạt phần lớn (s3, s5, s9) |
| UI-05 | Trạng thái lỗi/trống | Danh sách trống (camera, cảnh báo, mẫu, clip); lỗi mạng; 403 → `/forbidden`; không có dữ liệu xem lại | Có thông báo rõ, không màn hình trắng/đen | Một phần. Lỗi màn hình đen ở `/` đã sửa 27/10 và có test hồi quy |
| UI-06 | Trạng thái đang tải | Ô video "đang kết nối"; xuất clip "đang xử lý"; áp mẫu "đang áp" | Có chỉ báo | Kế hoạch |
| UI-07 | Thông báo | Toast, chuông, hộp thoại xác nhận (khởi động lại, đổi IP, xóa) | Hiện đúng lúc, có liên kết | Tự động đạt (s5, s7) |
| UI-08 | Điều hướng | Menu theo quyền (ẩn mục không có quyền); quay lại trang cũ sau đăng nhập; route lạ → `/` | Đúng quyền | Tự động đạt (s1) |
| UI-09 | Tương thích trình duyệt (NFR-08) | 2 phiên bản mới nhất của Chrome, Edge, Firefox, Safari (macOS, iOS): đăng nhập, xem trực tiếp WebRTC/HLS, xem lại, xuất clip, đàm thoại | Chức năng chính chạy được | Mới có Chrome (e2e). Edge/Firefox/Safari: Kế hoạch |
| UI-10 | Đa ngôn ngữ | Mọi chuỗi có ở vi và en (665 khóa, khớp); định dạng ngày giờ theo ngôn ngữ ứng dụng (ExportsView, AlertDetail đang dùng `toLocaleString()` theo trình duyệt: kiểm tra) | Không còn chuỗi chưa dịch | Một phần (s1, `http.spec.ts`) |

#### 6.1.10 Kiểm thử API

| Thuộc tính | Mô tả |
| --- | --- |
| **Đội phụ trách** | Đội phát triển, QA |
| **Công cụ** | Spring MockMvc + Testcontainers (Core), pytest + httpx (Gateway), OpenAPI `/v3/api-docs` để đối chiếu hợp đồng; Postman/Newman tùy chọn cho kiểm thử khám phá |
| **Phạm vi** | 153 endpoint Core, 37 endpoint Gateway, STOMP, hook MediaMTX |
| **Tiêu chí vào** | API triển khai; OpenAPI sinh được |
| **Tiêu chí ra** | 100% nhóm endpoint có ca dương + âm; mọi mã lỗi đặc thù có ít nhất 1 ca; endpoint không có caller giao diện có kiểm thử API hoặc bị gỡ |

| Mã | Endpoint | Phương thức | Loại | Kiểm tra | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| API-01 | `/api/v1/auth/*` | POST | Chức năng, âm | Schema token, cookie, 401/423/403/503 | ✓ AuthIT, TwoFactorIT, DirectoryIT |
| API-02 | `/api/v1/me/*`, `/users/*`, `/roles/*` | GET, POST, PATCH, PUT, DELETE | Chức năng, âm | Phạm vi quản lý, vai trò hệ thống bất biến, mật khẩu yếu | ✓ AccessIT, RoleTemplateIT |
| API-03 | `/api/v1/directory/*` | GET, PUT, POST | Chức năng, âm | Kiểm tra kết nối, đồng bộ, ánh xạ nhóm, LDAP ngừng | ✓ DirectoryIT |
| API-04 | `/api/v1/sites`, `/zones`, `/camera-groups`, `/cameras/{id}/move` | Mọi phương thức | Chức năng, âm | Cây theo quyền, không xóa địa điểm/khu vực còn camera, lịch sử vị trí | ✓ AccessIT. PATCH/DELETE `camera-groups`, `location-history` chưa có caller giao diện: thêm ca API (Kế hoạch) |
| API-05 | `/api/v1/cameras*`, `/recorders*`, `/sites/{id}/discovery` | Mọi phương thức | Chức năng, âm, phân trang | `size` 1–1000, BulkResult, mã lỗi thiết bị 422/502/503/504 | ✓ DeviceIT, RecorderIT, CameraImportIT |
| API-06 | `/api/v1/connectors*`, `/connector-agent/*` | GET, POST, DELETE | Chức năng, bảo mật | Mã kích hoạt, Basic auth, nhịp tim | ✓ ConnectorIT |
| API-07 | `/api/v1/cameras/{id}/live-session`, `/ptz*`, `/presets*`, `/talk*`, `/layouts*` | GET, POST, PUT, DELETE | Chức năng, đồng thời | Token 60 s, khóa PTZ, `LAYOUT_LIMIT` (10), `TALK_BUSY` | ✓ LiveIT, TalkIT. DELETE layout/preset chưa có caller giao diện: thêm ca (Kế hoạch) |
| API-08 | `/api/v1/cameras/{id}/recording-policy`, `/recording-policies` | GET, PUT | Chức năng, âm | Lịch 30 phút, pre/post, áp hàng loạt | ✓ RecordingIT |
| API-09 | `/api/v1/cameras/{id}/timeline`, `/recording-days`, `/playback-session`, `/edge-*` | GET, POST | Chức năng, hiệu năng | `RANGE_TOO_LONG` > 31 ngày, `NO_RECORDING`, `EDGE_NOT_SUPPORTED`; P95 dưới tải | ✓ RecordingIT, EdgePlaybackIT, PERF-01 |
| API-10 | `/api/v1/retention-policy`, `/cameras/{id}/retention`, `/evidence-holds*`, `/retention/purge` | GET, PUT, POST | Chức năng, quy tắc | 1–3650 ngày, giữ bằng chứng, xóa quá hạn | ✓ RecordingIT |
| API-11 | `/api/v1/exports*` | GET, POST | Chức năng, bảo mật | 202, 60 phút, 429, liên kết ký, 409/410 | ✓ ExportIT |
| API-12 | `/api/v1/alerts*`, `/motion-rules*`, `/alert-rules/*` | GET, POST, PUT, DELETE | Chức năng, máy trạng thái | Chuyển trạng thái hợp lệ và không hợp lệ, `MOTION_NOT_SUPPORTED` | ✓ AlertingIT, MotionAlertIT. `open-count` dùng trong PERF-01 |
| API-13 | `/api/v1/me/notifications*`, `/me/channels/*`, `/notification-channels/*` | GET, POST, PUT, DELETE | Chức năng, âm | Địa chỉ sai, bí mật bắt buộc | ✓ MotionAlertIT (một phần). Kế hoạch: ca âm `INVALID_ADDRESS` |
| API-14 | `/api/v1/cameras/{id}/config/*` | GET, PUT | Chức năng, âm | Kết quả theo tham số, `OUT_OF_RANGE`, `NOT_SUPPORTED`, `INVALID_NETWORK` | ✓ RecordingIT, EdgePlaybackIT, ZoneConfigIT, NetworkConfigIT |
| API-15 | `/api/v1/config-templates*`, `/template-applications/*` | GET, POST, PUT, DELETE | Chức năng | Áp một phần, áp lại phần lỗi | ✓ RoleTemplateIT |
| API-16 | `/api/v1/audit-logs*`, `/system/*` | GET | Chức năng, bảo mật | Phân trang ≤ 500, CSV an toàn, chỉ MANAGE_SYSTEM | ✓ AuditIT, AuditCsvTest, AlertingIT |
| API-17 | `/internal/media/authorize`, `/internal/media/segments` | POST | Bảo mật, tích hợp | read/playback/publish; path `test-*`/`edge-*`; Nginx chặn từ ngoài | ✓ LiveIT, smoke |
| API-18 | Device Gateway `/v1/*`, WS `/talk/ws/{id}` | POST, GET, PUT, DELETE, WS | Chức năng, bảo mật | Token nội bộ, 424 + mã thiết bị, 422 không trả lại giá trị đầu vào, WS 4404 | ✓ 204 test pytest |

### 6.2 Chiến lược thực thi

Kiểm thử chạy theo 4 chu kỳ, gắn với cổng của kế hoạch phát triển. Mỗi chu kỳ bắt đầu bằng smoke test (REG-08). Smoke không đạt thì dừng chu kỳ (mục 8).

| Chu kỳ | Môi trường | Cổng | Nội dung | Trạng thái 27/10/2026 |
| --- | --- | --- | --- | --- |
| C0 – Phát triển | Dev (laptop, Docker Compose, bộ giả lập) | — | Đơn vị, tích hợp, e2e theo từng phase S0–S9; PoC hiệu năng; tự rà soát bảo mật; diễn tập khôi phục 1 và 2; tải API 100 người dùng | **Xong** (giới hạn: giả lập, máy dev) |
| C1 – Hệ thống | Staging (SADD 3.12: node ứng dụng + node media + NAS) với 50 camera thật ≥ 5 hãng + 450 luồng giả lập | G1/G2 | Toàn bộ hồi quy; e2e trên staging; tương thích `compat` với camera thật; PERF-02, 04, 05, 06, 07, 10, 12; SEC-06, 08, 10; UI-09 (4 trình duyệt); ACC-01..06 | **Chặn** (D-01, D-04) |
| C2 – Pilot (Beta) | Staging + 1 địa điểm pilot (20–50 camera, ≥ 3 hãng) qua Site Connector | G2 | Vận hành thật 2 tuần; PERF-08 tại chi nhánh; LDAP/AD thật; Telegram/Zalo/SMTP thật; thu thập lỗi pilot; đo NFR-04 | Chưa bắt đầu |
| C3 – Phát hành (GA) | Staging cấu hình như production | G3 | Pentest độc lập (SEC-12) và kiểm lại; UAT-01..44; hồi quy đầy đủ trên bản ứng viên; diễn tập khôi phục lần 3 trên staging; báo cáo tổng kết kiểm thử | Chưa bắt đầu |

**Thứ tự trong một chu kỳ.**

1. Smoke.
2. Hồi quy tự động (REG-01..07).
3. Kiểm thử mới / thủ công theo ưu tiên rủi ro (mục 4.1, Cao trước).
4. Hiệu năng, bảo mật.
5. Kiểm lại lỗi đã sửa.
6. Hồi quy cuối trên bản ứng viên.

**Quy tắc.**

- Kiểm thử tải không chạy song song với e2e hay đo first frame trên cùng máy, vì sẽ làm sai số đo.
- Mỗi bản build kiểm thử phải có mã commit và tag.
- Kết quả gắn với mã bản build.

### 6.3 Dữ liệu kiểm thử

| Loại | Nguồn | Ghi chú |
| --- | --- | --- |
| Tổ chức, địa điểm, khu vực, camera mẫu, 4 tài khoản demo (Site Manager, Operator, Technician, Viewer) | `DemoDataSeeder` (profile `demo`) | Chỉ dev/staging. Mật khẩu demo không dùng ở production |
| Tài khoản Admin | Bí mật `bootstrap_admin_password` sinh lúc triển khai | Không ghi vào tài liệu/commit |
| 100 tài khoản tải `loadtest-001..100` (Operator) | `tests/load` (`LoadUsers`) | Tạo/xóa theo lượt chạy |
| Luồng video | `tools/rtsp-sim` (720p/1080p, H.264/H.265, có AAC); `tools/onvif-sim` (PTZ, sự kiện chuyển động, thẻ SD Profile G, đầu ghi 16 kênh, loa backchannel) | Giả lập trước, camera thật ở C1/C2 |
| Thư mục người dùng | OpenLDAP (`deploy/ldap`); AD của tổ chức ở pilot | — |
| Kênh thông báo | Mailpit (email), `tools/chat-mock` (Telegram/Zalo); tài khoản thật ở C2 | — |
| Tệp nhập camera | Tệp mẫu CSV/XLSX có dòng lỗi | Trong e2e s9 và CameraImportIT |
| Dữ liệu lớn | Bảng đệm 4 triệu dòng (1,1 GB) cho diễn tập khôi phục | Xóa sau diễn tập |

**Dữ liệu cá nhân.**

- Không dùng hình ảnh hay dữ liệu thật của người dùng production cho kiểm thử ở dev.
- Ở pilot, video thật được xử lý theo chính sách lưu trữ và Nghị định 13/2023.

### 6.4 Quản lý lỗi

**Mức nghiêm trọng:**

| Mức | Định nghĩa | Ví dụ | Thời gian xử lý mục tiêu |
| --- | --- | --- | --- |
| 1 – Nghiêm trọng | Mất dữ liệu, lỗ hổng bảo mật Nghiêm trọng/Cao, sập hệ thống, không ghi hình | Lộ video sang Tổ chức khác; mất đoạn ghi | Sửa ngay, chặn phát hành |
| 2 – Cao | Chức năng Must không dùng được, không có cách vòng | Không xem trực tiếp được; màn hình đen khi mở trang gốc (đã gặp 27/10) | Trong sprint, chặn cổng |
| 3 – Trung bình | Chức năng lỗi nhưng có cách vòng; Should không đạt | Ngày giờ hiển thị theo ngôn ngữ trình duyệt | Theo ưu tiên của PO |
| 4 – Thấp | Giao diện, chính tả, trợ năng nhỏ | Thiếu nhãn ARIA | Backlog |

**Quy trình.**

1. Mở lỗi. Lỗi phải có các bước tái hiện, bản build, môi trường, nhật ký/ảnh chụp và liên kết tới ca kiểm thử / US.
2. Phân loại (triage) 2 lần/tuần với PO và trưởng nhóm phát triển.
3. Sửa lỗi kèm test tự động tái hiện lỗi.
4. QA kiểm lại trên bản build mới.
5. Đóng lỗi.

**Theo dõi.**

- Lỗi tái mở được đánh dấu riêng.
- Lỗi đã sửa trong mỗi phase được ghi vào mục "Lỗi phát hiện và đã sửa" của `implement/plan/status-S*.md`.

### 6.5 Tiêu chí vào/ra theo mức

| Mức | Tiêu chí vào | Tiêu chí ra |
| --- | --- | --- |
| Đơn vị | Mã đã review, build/lint đạt | 100% đạt, độ phủ đạt ngưỡng (mục 6.1.1) |
| Tích hợp | Đơn vị đạt, migration sạch | 100% đạt, không test chập chờn chưa xử lý |
| Hệ thống | Tích hợp đạt, smoke 12/12, môi trường và dữ liệu sẵn sàng | E2E đạt, 0 lỗi mức 1–2 mở, chỉ tiêu hiệu năng/bảo mật của chu kỳ đạt |
| Chấp nhận | Hệ thống đạt trên staging, người dùng đã đào tạo | Tiêu chí mục 6.1.4 |

### 6.6 Chỉ số theo dõi

| Chỉ số | Định nghĩa | Mục tiêu |
| --- | --- | --- |
| Độ phủ yêu cầu | Số BR/US có ít nhất 1 ca kiểm thử đạt ÷ tổng | 100% Must, ≥ 95% Should trước G3 |
| Tỷ lệ đạt | Số ca đạt ÷ số ca đã chạy, theo chu kỳ | ≥ 98% ở bản ứng viên |
| Lỗi mở theo mức | Đếm theo mức 1–4 | 0 mức 1–2 tại cổng |
| Độ phủ mã | Dòng lệnh (JaCoCo, pytest-cov, Vitest) | Mục 6.1.1 |
| Test chập chờn | Số test không ổn định trong 10 lần chạy hằng đêm | 0 |
| Tự động hóa | Số AC có test tự động ÷ tổng AC | Hiện ≈ 100% US có ít nhất 1 test tự động; AC phần cứng thật là ngoại lệ |

### 6.7 Mức độ phủ hiện tại

Tổng hợp 27/10/2026:

- Mọi US/TS đều có ít nhất 1 test tự động.
- Điểm phủ mỏng:
  - US-11: chỉ có unit test.
  - US-13, US-34: chưa có e2e.
  - US-14, US-18, US-38, US-42: chỉ có e2e.
  - TS-02, TS-07, TS-08: chỉ có script, chưa vào CI.
- **Mọi AC cần phần cứng thật đều chưa kiểm**: TS-03, TS-05, US-15, US-22, US-26 (video ghi), US-36 (Zalo thật), US-44 (AD thật).
- Các mục trên là trọng tâm của chu kỳ C1/C2.

### 6.8 Ma trận truy vết yêu cầu

#### 6.8.1 Yêu cầu chức năng → US → UC → kiểm thử

Viết tắt: **U** = đơn vị, **IT** = tích hợp, **E** = e2e (tệp `tests/e2e/specs/sN.spec.js`).

| BR | US | UC | INV / RULE | Kiểm thử tự động (U / IT / E) | Ca trong kế hoạch | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| BR-01 | US-01 | UC-10 | INV-02, INV-03 | U `test_discovery`, `test_api` / IT DeviceIT / E s2 ×2 | INT-API-09, E2E-01, UAT-01 | Đạt (giả lập) |
| BR-02 | US-02 | UC-11 | INV-03 | U `test_api` / IT DeviceIT / E s2 ×2 | INT-API-09, UAT-02 | Đạt |
| BR-03 | US-03 | UC-12 | INV-02 | U `test_recorder_channels` / IT RecorderIT / E s6 | UAT-03 | Đạt (giả lập) |
| BR-04 | US-04 | UC-16 | — | U `test_health_monitor` / IT DeviceIT / E s2 | E2E-04, UAT-04 | Đạt |
| BR-05 | US-05 | UC-14 | RULE-07, INV-18/19 | IT AccessIT / E s1 | INT-API-05, PERF-09, UAT-05 | Đạt |
| BR-06 | US-06, TS-06 | UC-15 | — | U site-connector 21, `test_tunnel` / IT ConnectorIT / E s6; `tunnel_test.md` | INT-API-22, SEC-09, E2E-01, UAT-06 | Đạt (stub) |
| BR-07 | US-07 | UC-13 | — | IT CameraImportIT / E s9 | INT-API-10, E2E-10, UAT-07 | Đạt |
| BR-08 | US-08, TS-03 | UC-20 | INV-10 | U MediaTokenServiceTest, `player.spec` / IT LiveIT / E s3 | INT-API-08, E2E-06, E2E-14, PERF-02, PERF-12, UAT-08 | Đạt (AC3 HLS: Kế hoạch) |
| BR-09 | US-09 | UC-20 | INV-13 | U `test_profiles` / E s3 | E2E-06, E2E-15, UAT-09 | Đạt (AC3: Kế hoạch) |
| BR-10 | US-11 | UC-20 | — | U `AdaptiveStream.spec` | PERF-08, UAT-11 | Một phần |
| BR-11 | US-12 | UC-22 | INV-05 | compat ptz / IT LiveIT / E s3 | INT-API-11, UAT-12 | Đạt (giả lập) |
| BR-12 | US-14 | UC-24 | — | E s3, s6 | UAT-14 | Đạt |
| BR-13 | US-15 | UC-25 | INV-05 | U `test_talk`, `PcmResampler.spec` / IT TalkIT / E s9 | INT-API-12, E2E-11, SEC-11, UAT-15 | Đạt (giả lập) |
| BR-14 | US-16 | UC-30 | — | IT RecordingIT / E s4 | INT-API-13, PERF-04, UAT-16 | Đạt |
| BR-15 | US-17 | UC-31 | RULE-07 | IT RecordingIT / E s4 | PERF-03, E2E-03, UAT-17 | Đạt |
| BR-16 | US-18 | UC-31 | — | E s4 | E2E-03, UAT-18 | Đạt |
| BR-17 | US-19 | UC-32 | — | U `SyncController.spec` / E s8 | E2E-03, UAT-19 | Đạt |
| BR-18 | US-20 | UC-33 | RULE-01, INV-08/09 | U media-worker `test_export_job`, `test_consumer` / IT ExportIT / E s5 ×2 | INT-API-15/16, E2E-03, UAT-20 | Đạt |
| BR-19 | US-21 | UC-34..36 | RULE-02, INV-06/07 | IT RecordingIT / E s4 | INT-API-14, UAT-21 | Đạt |
| BR-20 | US-22 | UC-31 | — | U `test_edge_playback` / IT EdgePlaybackIT / E s7 | E2E-04, UAT-22 | Đạt (giả lập) |
| BR-21 | US-23 | UC-40 | INV-04 | U `test_api` / IT RecordingIT / E s4 | E2E-08, UAT-23 | Đạt (giả lập) |
| BR-22 | US-24 | UC-40 | — | compat imaging / IT EdgePlaybackIT / E s7 | E2E-08, UAT-24 | Đạt (giả lập) |
| BR-23 | US-25 | UC-40 | — | U `test_network` / IT NetworkConfigIT / E s8 | E2E-08, UAT-25 | Đạt (giả lập) |
| BR-24 | US-26 | UC-40 | RULE-03 | U `test_zones` / IT ZoneConfigIT / E s5 | E2E-08, UAT-26 | Một phần (vùng che trên video ghi chưa kiểm) |
| BR-25 | US-27 | UC-41 | INV-01 | IT RoleTemplateIT / E s8 | INT-API-25, E2E-01, UAT-27 | Đạt |
| BR-26 | US-28 | UC-42 | — | U `test_api` / IT RecorderIT / E s6 | E2E-08, UAT-28 | Đạt (giả lập) |
| BR-27 | US-29 | UC-40 | — | U `test_profiles` / IT RecordingIT / E s4 | E2E-08, UAT-29 | Đạt (giả lập) |
| BR-28 | US-30 | UC-01, UC-03 | RULE-04, INV-12 | U `http.spec` / IT AuthIT / E s1 ×5 | INT-API-01/02/04, E2E-05, SEC-01, UAT-30 | Đạt |
| BR-29 | US-32 | UC-03, UC-05 | INV-10/27, SaaS-1..2 | U MediaTokenServiceTest / IT AccessIT, TenancyIT / E s1 ×2, s3 | INT-API-06/07/26/27, E2E-07, SEC-02, UAT-32 | Đạt (INT-API-26/27: Kế hoạch) |
| BR-30 | US-33 | UC-08 | RULE-06, INV-16 | U AuditCsvTest / IT AuditIT / E s2 | INT-API-20/21, SEC-07, UAT-33 | Đạt |
| BR-31 | US-34 | UC-50, UC-51 | INV-14 | IT AlertingIT | E2E-04, UAT-34 | Đạt (chưa có e2e) |
| BR-32 | US-36, US-42 | UC-50, 51, 53 | INV-15 | U `test_normalize`, snapshot / IT MotionAlertIT / E s7 | INT-API-18, E2E-02, UAT-36, UAT-42 | Đạt (Zalo thật: Chặn) |
| BR-33 | US-37 | UC-52 | INV-14/28 | IT MotionAlertIT / E s7 | INT-API-17, E2E-02, UAT-37 | Đạt |
| BR-34 | US-10 | UC-21 | INV-13 | IT LiveIT / E s3 | E2E-06, UAT-10 | Đạt |
| BR-35 | US-13 | UC-23 | INV-05 | compat presets / IT LiveIT | UAT-13 | Đạt (chưa có e2e) |
| BR-36 | US-31 | UC-02 | INV-11/25 | IT TwoFactorIT / E s2 | INT-API-03, E2E-05, SEC-01, UAT-31 | Đạt |
| BR-37 | US-35 | UC-50, UC-51 | — | IT AlertingIT / E s5 | E2E-12, UAT-35 | Đạt |
| BR-38 | US-38 | UC-52, UC-31 | — | E s7 | E2E-02, UAT-38 | Đạt |
| BR-39 | US-43 | UC-04 | INV-20/21/22 | IT RoleTemplateIT / E s8 | INT-API-24, E2E-09, UAT-43 | Đạt |
| BR-40 | US-44 | UC-01, 06, 07 | RULE-05, INV-23/24/26 | IT DirectoryIT / E s8 | INT-API-23, E2E-09, UAT-44 | Đạt (OpenLDAP; AD thật: Chặn) |

**Độ phủ:**

- **40/40 BR (100%) có ca kiểm thử và test tự động.**
- **44/44 US có ca UAT.**
- **39/39 UC** được phủ qua US tương ứng. UC-62 (khôi phục, ngoài ứng dụng) được phủ bằng diễn tập khôi phục.

#### 6.8.2 Yêu cầu phi chức năng → kiểm thử

| Yêu cầu | Thiết kế (SADD) | Ca kiểm thử | Bằng chứng hiện có | Trạng thái |
| --- | --- | --- | --- | --- |
| NFR-01 | WHEP, luồng phụ cho lưới lớn | PERF-02, PERF-12, PERF-11, E2E-06 | PoC 23/10; e2e s3 | Đạt có điều kiện (dev). Staging: Kế hoạch |
| NFR-02 | Chỉ mục `recording_segment`, playback MediaMTX | PERF-03, PERF-01 | e2e s4; PERF-01 | Đạt (dev) |
| NFR-03 | Core không trạng thái, media node riêng | PERF-01, PERF-04, PERF-06 | PERF-01 (mục 6.1.6) | API: đạt (dev). 500 camera: Chặn |
| NFR-04 | Healthcheck, tự khởi động lại, Prometheus | PERF-05, PERF-10; đo thời gian hoạt động hằng tháng ở pilot; TS-07 (báo động ServiceDown) | Báo động ServiceDown sau 85 s (S0) | Kế hoạch (đo ở C2) |
| NFR-05, PNFR-07 | TLS, AES-256-GCM, Argon2id | SEC-01, SEC-04, SEC-06 | CryptoServiceTest; tự rà soát | Đạt (dev). Chứng chỉ thật: G-01 |
| NFR-06 | Xóa quá hạn, vùng che, nhật ký, phân quyền | Pháp chế rà soát (I); INT-API-14, UAT-21, UAT-26 | — | Kế hoạch (Pháp chế) |
| NFR-07 | vue-i18n, responsive | UI-02, UI-10, E2E-13 | e2e s1 | Đạt |
| NFR-08, PNFR-01 | WebRTC + hls.js | UI-09 | Chỉ Chrome | Kế hoạch |
| NFR-09 | Phân vùng tháng, chặn sửa/xóa | INT-API-21; kiểm tra cấu hình phân vùng 12 tháng (I) | AuditIT | Đạt (dev) |
| NFR-10, NFR-11 | pgBackRest + WAL, runbook | Diễn tập khôi phục; UAT-40 | restore-drill-01, 02: đạt (khôi phục 11,8 s cho 1,1 GB; RPO ≈ 5 phút) | Đạt (dev). Staging ≥ 10 GB: Kế hoạch |
| NFR-12 | Ghi thẻ SD tại camera, Profile G | PERF-10, UAT-22 | EdgePlaybackIT (giả lập) | Kế hoạch |
| PNFR-02 | HLS dự phòng | E2E-14 | `player.spec` (unit) | Kế hoạch |
| PNFR-03 | Chuyển mã theo yêu cầu, giới hạn phiên | PERF-07 | PoC | Đạt (dev) |
| PNFR-04 | FFmpeg H.264/AAC | INT-API-15, UAT-20 | ExportIT, `test_export_job` | Đạt |
| PNFR-05 | ONVIF S/G/T, RTSP | REG-09 (`compat`) ≥ 5 hãng | [docs/compat/report.md](../compat/report.md): 2 bộ giả lập đạt; 3 camera thật bị bỏ qua vì thiếu thông tin đăng nhập | **Chặn** (D-04) |
| PNFR-06 | ≤ 16 luồng mỗi người dùng | PERF-02 (16 luồng) | PoC | Đạt có điều kiện |
| PNFR-08 | — | SEC-12 | — | **Chặn** |

## 7. Tiêu chí đạt/không đạt

**Mức ca kiểm thử.** Một ca **đạt** khi kết quả thực tế khớp kết quả mong đợi và oracle định lượng (mục 0.2.4). Một ca **không đạt** khi có bất kỳ sai khác nào. Lỗi được ghi theo mục 6.4.

**Mức đối tượng (SI-01..09).** Một đối tượng đạt khi:

- Bộ hồi quy tương ứng (REG-01..07) đạt 100%.
- Không có lỗi mức 1–2 mở gắn với đối tượng đó.

**Mức phát hành.** Áp dụng theo cổng, khớp tiêu chí nghiệm thu BRS §7 (AC-B1..B5):

| Cổng | Điều kiện đạt |
| --- | --- |
| G2 – Beta | 1. Mọi US Must và Should đạt trên staging (C1). 2. Pilot 20–50 camera, ≥ 3 hãng, ổn định 2 tuần. 3. 0 lỗi mức 1 mở. 4. PERF-02/03 đạt trên staging. |
| G3 – GA | 1. **AC-B1:** BR-01..40 đạt UAT theo phương pháp KC. 2. **AC-B2:** camera ≥ 5 hãng kết nối thành công (REG-09). 3. **AC-B3:** NFR-01/02 đạt với 100 camera thử nghiệm. 4. **AC-B4:** pentest không còn lỗi Nghiêm trọng/Cao. 5. **AC-B5:** người dùng pilot hài lòng ≥ 4/5. 6. NFR-03 đạt (PERF-01 + PERF-04). 7. Diễn tập khôi phục đạt trên staging. 8. 0 lỗi mức 1–2 mở. 9. Báo cáo tổng kết kiểm thử được ký. |

Ngoại lệ chỉ áp dụng cho US Should/Could. Ngoại lệ phải có văn bản do PO và Sponsor ký, ghi rủi ro chấp nhận.

## 8. Tiêu chí tạm dừng và tiếp tục

| Tạm dừng khi | Tiếp tục khi |
| --- | --- |
| Smoke test (REG-08) không đạt trên bản build mới | Bản build sửa lỗi qua smoke 12/12 |
| Lỗi mức 1 chặn > 30% ca của chu kỳ (ví dụ không đăng nhập được, không phát được video) | Lỗi được sửa và kiểm lại; chạy lại các ca bị chặn |
| Môi trường không ổn định: container khởi động lại liên tục, Docker/host sập. Đã gặp: Docker Desktop sập ở phút 13 của lần tải đầu, ngày 27/10 | Môi trường ổn định ≥ 1 giờ; chạy lại từ đầu lượt đo bị ảnh hưởng |
| Dữ liệu kiểm thử hỏng hoặc bị test trước làm bẩn | Khôi phục dữ liệu sạch (`dev.sh reset` + nạp dữ liệu demo) |
| Kiểm thử hiệu năng: có tác vụ nặng khác chạy cùng máy | Máy rảnh; chạy lại lượt đo |
| Pentest phát hiện lỗi Nghiêm trọng đang bị khai thác được | Bản vá đã triển khai; nhà cung cấp xác nhận |
| Camera/thiết bị thật bị lỗi phần cứng | Thay thiết bị; ghi nhận vào danh sách tương thích |

## 9. Sản phẩm bàn giao

| Sản phẩm | Vị trí | Thời điểm | Trạng thái |
| --- | --- | --- | --- |
| Kế hoạch kiểm thử tổng thể (tài liệu này) | `docs/test/master-test-plan.md` | Trước C1 | Bản nháp 1.0 |
| Đặc tả ca kiểm thử (Gherkin) | `tests/e2e/features/*.feature` | Liên tục | Có (105 kịch bản) |
| Script kiểm thử tự động | `core/src/test`, `*/tests`, `web/src/**/*.spec.ts`, `tests/e2e/specs`, `tests/load`, `tests/smoke` | Liên tục | Có |
| Nhật ký kiểm thử / kết quả chạy | Báo cáo Gradle, pytest, Playwright `tests/e2e/results`, Gatling `tests/load/build/reports` | Mỗi lần chạy | Có |
| Báo cáo kiểm thử tải | `docs/test/load-report.md` | Sau PERF-01 (dev) và C1 (staging) | Đang lập |
| Báo cáo PoC hiệu năng | `docs/poc/mediamtx-report.md` | S0 | Có |
| Báo cáo tương thích thiết bị | `docs/compat/report.md` | Mỗi lần chạy `compat` | Có (chưa có camera thật) |
| Tự rà soát bảo mật | `docs/test/security-review.md` | Trước pentest | Có |
| Báo cáo pentest độc lập và kiểm lại | `docs/test/pentest-report.md` (bản tóm tắt; bản đầy đủ lưu hạn chế) | C3 | Chưa có |
| Biên bản diễn tập khôi phục | `docs/runbook/restore-drill-0N.md` | Hằng quý và trước GA | Lần 1, 2 có |
| Báo cáo sự cố kiểm thử (lỗi) | Công cụ quản lý lỗi + mục lỗi trong `status-S*.md` | Liên tục | Có |
| Biên bản UAT, khảo sát hài lòng | `docs/test/uat-report.md` | C3 | Chưa có |
| Báo cáo tổng kết kiểm thử (Test Summary Report, IEEE 829) | `docs/test/test-summary-report.md` | Trước G3 | Chưa có |

## 10. Công việc kiểm thử

| Mã | Công việc | Phụ thuộc | Phụ trách | Công (giờ) | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| T-01 | Đẩy repo lên GitHub, chạy CI lần đầu, sửa lỗi CI | — | Dev | 4 | Mở |
| T-02 | Thêm job CI hằng đêm: Compose + smoke + e2e | T-01 | Dev / QA | 8 | Mở |
| T-03 | Bật đo độ phủ (JaCoCo, pytest-cov, Vitest coverage) và đặt ngưỡng | T-01 | Dev | 4 | Mở |
| T-04 | Bổ sung e2e còn thiếu: US-34, US-13, E2E-15 (ô ngoại tuyến), chuỗi E2E-01 liền mạch | — | QA | 8 | Mở |
| T-05 | Ca API còn thiếu: INT-API-26/27, API-04/07/13 | — | Dev | 6 | Mở |
| T-06 | E2E-14 HLS dự phòng (chặn UDP 8189) | — | QA | 4 | Mở |
| T-07 | Thêm `@axe-core/playwright`, ACC-01..06 | — | QA | 8 | Mở |
| T-08 | Hoàn thiện đo first frame trên trang Xem trực tiếp thật (`tests/load/first-frame.*`: ô đang dừng ở "đang kết nối") | — | QA | 4 | Mở |
| T-09 | Viết `docs/test/load-report.md` từ PERF-01 | PERF-01 | QA | 3 | Đang làm |
| T-10 | Chuẩn bị staging theo SADD 3.12 | D-01 | IT | — | Chặn |
| T-11 | Thu thập ≥ 5 hãng camera + thông tin đăng nhập; chạy `compat` | D-04 | IT, QA | 8 | Chặn |
| T-12 | C1: hồi quy + PERF-02/04/05/06/07/10/12 trên staging | T-10, T-11 | QA, IT | 40 | Chặn |
| T-13 | UI-09: kiểm thử 4 trình duyệt (Edge, Firefox, Safari macOS/iOS) | T-10 | QA | 12 | Kế hoạch |
| T-14 | Thuê pentest ASVS L2; chuẩn bị tài khoản 5 vai trò × 2 Tổ chức | T-10 | PM, Dev | 8 + nhà cung cấp | Chặn |
| T-15 | Sửa và kiểm lại lỗi pentest | T-14 | Dev, QA | 16 | Kế hoạch |
| T-16 | C2: pilot 2 tuần; LDAP/AD, Telegram/Zalo/SMTP thật; PERF-08 tại chi nhánh | T-12 | PO, QA, IT | 40 | Kế hoạch |
| T-17 | Đào tạo người dùng pilot; chạy UAT-01..44; khảo sát | T-16 | PO, QA | 24 | Kế hoạch |
| T-18 | Diễn tập khôi phục lần 3 trên staging (≥ 10 GB) | T-10 | IT | 6 | Kế hoạch |
| T-19 | Báo cáo tổng kết kiểm thử; họp G3 | T-12..T-18 | QA Lead | 6 | Kế hoạch |
| T-20 | Xử lý G-01..G-04 (chứng chỉ TLS, kho sao lưu off-site, giới hạn 30 r/s sau NAT, quét thư viện trong CI) | — | IT, Dev | 12 | Mở |

## 11. Nhu cầu môi trường

| Môi trường | Cấu hình | Mục đích | Trạng thái |
| --- | --- | --- | --- |
| Dev | Laptop Intel i7-1185G7 (4 lõi / 8 luồng), 16 GB RAM, Windows 11 + Docker Desktop (WSL2); toàn bộ 20+ container trên một máy | C0: đơn vị, tích hợp, e2e, PoC, tải API rút gọn | Có. Số đo hiệu năng chỉ là cận trên |
| CI | GitHub Actions (`ubuntu-latest`), Docker cho Testcontainers | REG-01..06; job hằng đêm REG-07..08 | Đã cấu hình, chưa chạy |
| Staging | Theo SADD 3.12. **Node ứng dụng:** 16 vCPU, 64 GB RAM, 1 TB SSD. **Node media:** 32 vCPU, 64 GB RAM, 2 × 10 GbE. **NAS** cho `/recordings`. Mạng LAN riêng cho camera | C1, C3: hệ thống, hiệu năng, bảo mật, UAT | **Chưa có** (D-01) |
| Pilot | 1 địa điểm, 20–50 camera, ≥ 3 hãng, Site Connector (Docker trên máy nhỏ), kết nối Internet thật | C2 | Chưa có |
| Thiết bị | Camera ONVIF ≥ 5 hãng, gồm PTZ, có thẻ SD, có loa (backchannel), H.265; 1 NVR. Đã có trong danh sách: Hikvision DS-2CD2143G2-I, Dahua IPC-HFW2431S, Hikvision PTZ DS-2DE4425IW (thiếu thông tin đăng nhập) | REG-09, UAT | Thiếu (D-04) |
| Trình duyệt / máy khách | Chrome, Edge, Firefox (2 phiên bản mới nhất) trên Windows; Safari macOS; Safari iOS; Chrome Android; màn hình lớn cho phòng giám sát | UI-09, UAT | Chỉ có Chrome |
| Dịch vụ ngoài | SMTP thật, Telegram bot, Zalo OA đã xác thực (OQ-P02), AD của tổ chức (tài khoản dịch vụ chỉ đọc, D-06) | C2 | Chưa có |
| Công cụ | Gatling 3.13.5; Playwright 1.50.1; @axe-core/playwright; OWASP ZAP; Burp Suite (nhà cung cấp pentest); testssl.sh; Trivy; tc/netem; Lighthouse; NVDA | Mục 6.1 | Một phần |

## 12. Trách nhiệm

| Hoạt động | QA Lead | QA | Đội phát triển | Đội vận hành IT | Product Owner | Sponsor | Nhà cung cấp pentest |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lập và duy trì MTP | A/R | C | C | C | C | I | — |
| Kiểm thử đơn vị, tích hợp | I | C | A/R | — | — | — | — |
| Kiểm thử hệ thống, e2e, hồi quy | A | R | C | C | I | — | — |
| Kiểm thử hiệu năng | A | R | C | R (hạ tầng, giám sát) | I | — | — |
| Kiểm thử bảo mật (tự rà soát, SCA) | A | C | R | C | I | — | — |
| Pentest độc lập | A | C | C (sửa lỗi) | C | I | I | R |
| Tương thích thiết bị | A | R | C | R (cung cấp camera) | I | — | — |
| Diễn tập khôi phục | I | C | C | A/R | I | — | — |
| UAT | C | R (hỗ trợ) | C | C | A | I | — |
| Phân loại lỗi | R | C | R | C | A | — | — |
| Quyết định qua cổng G2/G3 | R | I | C | C | A | A (G3) | — |

R = thực hiện, A = chịu trách nhiệm cuối, C = tham vấn, I = được thông báo.

## 13. Nhân sự và đào tạo

| Vai trò | Số người | Kỹ năng cần | Đào tạo |
| --- | --- | --- | --- |
| QA Lead | 1 | IEEE 829, kiểm thử dựa trên rủi ro, quản lý cổng phát hành | — |
| QA (chức năng, tự động) | 1–2 | Playwright, API REST, WebRTC cơ bản, ONVIF/RTSP | 1 ngày về kiến trúc CamHub (SADD 3.1–3.9) và bộ giả lập; nửa ngày về chạy `dev.sh`, `tests/e2e/run.sh` |
| QA hiệu năng | 1 (có thể kiêm) | Gatling, Prometheus/Grafana, phân tích tài nguyên container | Nửa ngày về `tests/load` và giới hạn máy dev |
| Đội vận hành IT | 1–2 | Docker, mạng camera, pgBackRest | Diễn tập khôi phục theo `restore.md` |
| Người dùng pilot | 5–10 (đủ 5 vai trò) | Nghiệp vụ giám sát | 2 giờ theo vai trò (Admin, Technician, Operator): đăng nhập 2FA, xem trực tiếp, xem lại, xuất clip, xử lý cảnh báo; hướng dẫn ghi lỗi UAT |
| Nhà cung cấp pentest | Theo hợp đồng | OWASP ASVS L2, WebRTC/WebSocket | Tài liệu: SADD §4, danh sách endpoint mục 0.5, tài khoản thử |

## 14. Lịch trình

Mốc theo kế hoạch phát triển ([implement/plan/README.md](../../implement/plan/README.md)) và thực tế:

| Mốc | Kế hoạch gốc | Thực tế / dự kiến | Hoạt động kiểm thử |
| --- | --- | --- | --- |
| S0–S9 trên dev (C0) | 26/10/2026 – 26/03/2027 | Đã hoàn thành chức năng trên dev ngày 27/10/2026 | Đơn vị, tích hợp, e2e theo phase; PoC; tự rà soát bảo mật; diễn tập khôi phục 1–2; tải API |
| Hoàn thiện kiểm thử trên dev (T-01..T-09, T-20) | — | T0 + 1 tuần (T0 = ngày duyệt MTP) | CI, độ phủ, e2e/API còn thiếu, trợ năng, báo cáo tải |
| Bàn giao staging + camera (D-01, D-04) | Trước M2 (PRD §12) | Do IT xác nhận: **mốc ràng buộc** | — |
| C1 – Hệ thống trên staging | S6–S8 | Bàn giao staging + 2 tuần | T-11, T-12, T-13; hồi quy đầy đủ |
| G2 – Beta | Sau S8 (12/03/2027) | Sau C1 | Đánh giá tiêu chí G2 |
| C2 – Pilot | S8 | G2 + 2 tuần | T-16 |
| C3 – Pentest, UAT, GA | S9 (15/03 – 26/03/2027) | Pentest bắt đầu khi có staging (song song C1/C2); UAT sau pilot 2 tuần | T-14, T-15, T-17, T-18 |
| G3 – GA | 26/03/2027 | Sau C3 | T-19; ký phê duyệt |

BQ-02 (thêm S10 cho UAT) do PO quyết định. Nếu pilot hoặc pentest trễ, G3 dời theo, không rút ngắn C2/C3.

## 15. Rủi ro và dự phòng (kiểm thử)

| Mã | Rủi ro | K | A | Điểm | Giảm thiểu | Dự phòng |
| --- | --- | --- | --- | --- | --- | --- |
| TR-01 | Staging chưa có (D-01): NFR-01..03 chỉ có số đo trên laptop | 5 | 4 | 20 | Báo cáo rõ đây là cận trên. Theo dõi D-01 trong họp tuần | Thuê máy chủ đám mây cấu hình tương đương SADD 3.12 trong thời gian đo |
| TR-02 | Không có camera thật ≥ 5 hãng (D-04) | 4 | 5 | 20 | Bộ giả lập ONVIF có PTZ, sự kiện, Profile G, backchannel; xin mượn camera từ nhà tích hợp | Công bố danh sách tương thích giới hạn; dùng RTSP cho hãng chưa đạt |
| TR-03 | CI chưa chạy: hồi quy phụ thuộc người chạy tay | 4 | 3 | 12 | T-01, T-02 trước C1 | Chạy toàn bộ hồi quy thủ công trước mỗi cổng, lưu kết quả |
| TR-04 | Đo hiệu năng bị nhiễu do chạy chung máy (Docker Desktop đã sập 1 lần khi tải 100 người dùng) | 3 | 3 | 9 | Không chạy song song; ghi tài nguyên bằng `sample-stats.sh` | Chạy lại lượt đo; tách máy sinh tải trên staging |
| TR-05 | Pentest trễ do thủ tục thuê | 3 | 4 | 12 | Bắt đầu thủ tục ngay khi MTP được duyệt | Tự chạy OWASP ZAP và ASVS checklist; dời G3 |
| TR-06 | Người dùng pilot không đủ thời gian UAT | 3 | 3 | 9 | Lịch UAT theo ca; kịch bản ngắn theo vai trò | Gộp UAT vào 2 tuần pilot, PO thu thập phản hồi trực tiếp |
| TR-07 | Test e2e chập chờn do dữ liệu dùng chung (đã gặp ở S9) | 3 | 2 | 6 | Mỗi spec tự dọn dữ liệu; chạy trên DB sạch | Đánh dấu test chập chờn, sửa trong 1 sprint |
| TR-08 | Dịch vụ ngoài (Zalo OA, AD) chưa sẵn sàng | 4 | 3 | 12 | Theo dõi OQ-P02, D-06 | Phát hành với kênh đã sẵn sàng (web, email, Telegram); Zalo ở bản vá sau |
| TR-09 | Yêu cầu chưa chốt (OQ-02 build/buy, OQ-03 dự phòng ghi hình, OQ-04 thời gian lưu) thay đổi phạm vi kiểm thử | 2 | 3 | 6 | Ghi giả định trong MTP (OQ-04: 30 ngày) | Cập nhật MTP; hồi quy phần bị ảnh hưởng |

## 16. Phê duyệt

| Vai trò | Họ tên | Chữ ký | Ngày | Ý kiến |
| --- | --- | --- | --- | --- |
| QA Lead | | | | |
| Trưởng nhóm phát triển | | | | |
| Solution Architect | | | | |
| Đội vận hành IT | | | | |
| Product Owner | | | | |
| Sponsor | | | | |

---

## Phụ lục A – Bất nhất phát hiện khi phân tích tài liệu

QA ghi nhận các điểm sau. Cần BA/PO chốt trước khi chạy UAT.

| # | Nội dung | Tài liệu | Đề xuất |
| --- | --- | --- | --- |
| A-1 | US-38: PRD ghi "thời điểm cảnh báo **trừ 10 giây**", User Stories AC1 ghi "**−5 s**". Mã và e2e s7 đang dùng −10 s | PRD US-38 AC1; User Stories US-38 | Chốt −10 s theo PRD, sửa User Stories |
| A-2 | UC-52 (xử lý cảnh báo) yêu cầu VIEW_LIVE, nhưng bảng tác nhân giao việc này cho Operator mà không nêu quyền riêng | Use Case §4 | Giữ VIEW_LIVE; ghi rõ trong bảng tác nhân |
| A-3 | UC-14 có tác nhân Site Manager nhưng cần MANAGE_SYSTEM, quyền mà Site Manager không có. Mã hiện tại: tạo/sửa địa điểm cần MANAGE_SYSTEM, khu vực cần CONFIGURE_CAMERA theo địa điểm | Use Case UC-14 | Chốt quyền của Site Manager với khu vực/nhóm |
| A-4 | UC-30 (cấu hình chế độ ghi) có tác nhân Admin nhưng quyền là CONFIGURE_CAMERA | Use Case UC-30 | Ghi tác nhân là Technician/Admin |
| A-5 | Site Manager có TALK_AUDIO / CONFIGURE_CAMERA hay không vẫn chờ PO xác nhận | status-S1-S4 | PO chốt trước UAT-15, UAT-23 |
| A-6 | Tiêu chí G0 yêu cầu PoC 50 camera thật + 500 luồng giả lập trước M1 (AR-01). Thực tế G0 "đạt có điều kiện" với ≤ 20 luồng trên dev | SADD 7, status-S0 | Đo bù ở PERF-04 (C1), là điều kiện của G2 |
| A-7 | Tiêu chí ra S9 nhắc `docs/test/load-report.md`, nhưng tệp chưa tồn tại | status-S9 | T-09 |
| A-8 | `tests/e2e/specs/zz-probe3.spec.js` là script dò lỗi, không có kiểm tra, nhưng chạy cùng bộ e2e | tests/e2e | Xóa hoặc loại khỏi bộ hồi quy |

## Phụ lục B – Lệnh chạy bộ kiểm thử

```bash
# Môi trường dev
./deploy/scripts/dev.sh up && ./deploy/scripts/dev.sh sim 24

# REG-01, REG-02 – Core
cd core && ./gradlew test integrationTest

# REG-03, REG-04 – Python
cd device-gateway && pytest            # thêm: pytest -m compat (camera thật)
cd site-connector && pytest; cd media-worker && pytest

# REG-05 – Web
cd web && npm run lint && npm run typecheck && npx vitest run

# REG-07 – e2e (container Playwright trong mạng backend)
./tests/e2e/run.sh                      # hoặc: ./tests/e2e/run.sh specs/s3.spec.js

# REG-08 – smoke
./deploy/scripts/dev.sh smoke

# PERF-01 – tải API
./tests/load/run.sh -Dusers=100 -Dminutes=60 -DrampSeconds=120

# Diễn tập khôi phục
cd deploy && bash backup/restore-drill.sh
```
