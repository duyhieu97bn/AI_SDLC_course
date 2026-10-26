# User Story & Product Backlog – CamHub: Nền tảng Web quản lý Camera IP

> Ngày: 23/10/2026 · Người soạn: Product Owner / Business Analyst · Phiên bản: 0.1 (Bản nháp)
> Đầu vào: [PRD](../requirements/PRD_IP_Camera_Web.md) v0.4 · [Use case](../requirements/Use_Case_IP_Camera_Web.md) v0.2 · [SADD](../requirements/SADD_IP_Camera_Web.md) v0.1 · [HTML design](../design/index.html) v0.1
> Bảng theo dõi: [Product_Backlog_IP_Camera_Web.xlsx](Product_Backlog_IP_Camera_Web.xlsx) (cùng dữ liệu, có cột trạng thái và công thức tổng hợp sprint)

## 1. Tóm tắt

Backlog phiên bản 1.0 gồm **44 user story** (từ PRD) và **8 enabler kỹ thuật** (từ SADD), tổng **274 điểm**. Với sprint 2 tuần và năng lực khoảng 30 điểm, backlog cần **10 sprint (20 tuần)**, gồm 1 sprint PoC và nền tảng (S0) và 9 sprint tính năng.

**Điểm khác với kế hoạch trong PRD (mục 10):** tổng thời gian vẫn là 20 tuần (PoC 2 tuần + 18 tuần), nhưng ranh giới các mốc dịch chuyển:

| Mốc | PRD | Theo ước lượng backlog | Lý do |
| --- | --- | --- | --- |
| M1 – Alpha (toàn bộ Must) | 8 tuần (+2 tuần PoC) | 12 tuần: S0–S5 | Story Must cùng enabler cần khoảng 175 điểm, vượt 120 điểm của 4 sprint |
| M2 – Beta pilot (Should) | 6 tuần | 6 tuần: S6–S8 | Không đổi |
| M3 – GA (Could, kiểm thử, UAT) | 4 tuần | 2 tuần: S9 | Chỉ còn khoảng 14 điểm dự phòng cho sửa lỗi pilot và UAT |

Rủi ro: M3 còn ít dự phòng. Nên cân nhắc thêm 1 sprint (S10) cho UAT và sửa lỗi, hoặc chuyển US-15 (Could) sang phiên bản sau (xem mục 8).

## 2. Quy ước

- **Mẫu story:** "Là [vai trò], tôi muốn [hành động], để [giá trị]"; đáp ứng INVEST (độc lập, thương lượng được, có giá trị, ước lượng được, nhỏ, kiểm thử được).
- **Tiêu chí chấp nhận:** viết theo Gherkin tiếng Việt (*Kịch bản / Giả sử / Khi / Thì / Và*), mỗi kịch bản là một test tự động.
- **Story point:** thang Fibonacci 1, 2, 3, 5, 8. Story lớn hơn 8 phải tách.
- **Ưu tiên:** MoSCoW lấy từ BRS/PRD. Phiên bản 1.0 làm toàn bộ; ưu tiên dùng để xếp thứ tự sprint.
- **Enabler (TS-xx):** công việc kỹ thuật không trực tiếp tạo tính năng, lấy từ SADD (PoC, khung dự án, hạ tầng).
- **Truy vết:** mỗi item ghi BR (BRS), UC (Use case), màn hình (design/) và thành phần (SADD).

### Definition of Ready

- [ ] Story viết theo mẫu "Là … tôi muốn … để …" và đáp ứng INVEST
- [ ] Có tiêu chí chấp nhận Given/When/Then, PO đã đồng ý
- [ ] Đã ước lượng story point (thang Fibonacci 1, 2, 3, 5, 8; story > 8 phải tách)
- [ ] Phụ thuộc đã xong hoặc nằm cùng sprint, đứng trước
- [ ] Có màn hình tham chiếu trong design/ (nếu có giao diện)
- [ ] Câu hỏi mở liên quan đã được trả lời hoặc ghi giả định

### Definition of Done

- [ ] Code đã review, merge vào main, pipeline CI xanh (gồm kiểm tra kiến trúc)
- [ ] Unit test cho logic nghiệp vụ; test tự động cho từng kịch bản Given/When/Then
- [ ] Kiểm tra quyền ở API (không chỉ ẩn nút trên giao diện); thao tác quan trọng ghi nhật ký
- [ ] Giao diện có tiếng Việt và tiếng Anh, dùng được ở 360px, đúng màn hình trong design/
- [ ] Không còn lỗi mức nghiêm trọng/cao; quét phụ thuộc không có lỗ hổng mức cao
- [ ] Tài liệu API (OpenAPI) cập nhật; PO chấp nhận trên môi trường staging

## 3. Kế hoạch sprint

| Sprint | Mốc | Mục tiêu sprint | Item | Điểm |
| --- | --- | --- | --- | --- |
| S0 | M0 – PoC & nền tảng | Dựng khung dự án, môi trường Docker Compose, PoC MediaMTX, nền tảng Core | TS-01, TS-02, TS-03, TS-04, TS-07 | 29 / 30 |
| S1 | M1 – Alpha | Đăng nhập, cây địa điểm và phân quyền theo phạm vi | TS-05, US-05, US-30, US-32, US-41 | 29 / 30 |
| S2 | M1 – Alpha | 2FA, nhật ký; thêm camera (thủ công, dò tìm) và theo dõi trạng thái | US-01, US-02, US-04, US-31, US-33 | 28 / 30 |
| S3 | M1 – Alpha | Xem trực tiếp: lưới, bố cục, PTZ, preset | US-08, US-09, US-10, US-12, US-13, US-29 | 29 / 30 |
| S4 | M1 – Alpha | Ghi hình, timeline, phát lại, thời gian lưu trữ; cấu hình luồng | US-16, US-17, US-18, US-21, US-23 | 29 / 30 |
| S5 | M1 – Alpha | Xuất clip, vùng chuyển động, cảnh báo hệ thống, tổng quan, sao lưu | US-20, US-26, US-34, US-35, US-39, US-40 | 31 / 30 |
| S6 | M2 – Beta pilot | Bộ kết nối địa điểm, đầu ghi NVR, chất lượng luồng, tiện ích xem | TS-06, US-03, US-06, US-11, US-14, US-28 | 28 / 30 |
| S7 | M2 – Beta pilot | Cảnh báo đa kênh và xử lý; xem lại từ thiết bị | US-22, US-24, US-36, US-37, US-38, US-42 | 29 / 30 |
| S8 | M2 – Beta pilot | Đồng bộ nhiều camera, cấu hình mẫu, vai trò tùy chỉnh, LDAP/AD | US-19, US-25, US-27, US-43, US-44 | 26 / 30 |
| S9 | M3 – GA | Story Could, kiểm thử tải và bảo mật, sửa lỗi pilot, UAT | TS-08, US-07, US-15 | 16 / 30 |
| **Tổng** | | | 52 item | **274 / 300** |

## 4. Epic

| Epic | Tên | Số item | Điểm | Must | Should | Could |
| --- | --- | --- | --- | --- | --- | --- |
| EP-00 | Nền tảng kỹ thuật (enabler) | 8 | 50 | 42 | 8 | 0 |
| EP-01 | Kết nối và quản lý camera | 7 | 39 | 26 | 10 | 3 |
| EP-02 | Xem trực tiếp | 8 | 36 | 24 | 7 | 5 |
| EP-03 | Ghi hình và xem lại | 7 | 45 | 32 | 13 | 0 |
| EP-04 | Cấu hình camera | 7 | 32 | 18 | 14 | 0 |
| EP-05 | Người dùng, phân quyền, nhật ký | 6 | 36 | 23 | 13 | 0 |
| EP-06 | Cảnh báo | 5 | 22 | 7 | 15 | 0 |
| EP-07 | Nền tảng và sẵn sàng SaaS | 4 | 14 | 11 | 3 | 0 |

## 5. Chi tiết backlog

### EP-00 Nền tảng kỹ thuật (enabler)

#### TS-01 Khung dự án và CI/CD

**Là Đội phát triển, tôi muốn có repo, build, lint, test và kiểm tra kiến trúc tự động cho Core, Gateway, Worker, Web, để mọi story sau dùng chung một quy trình chất lượng.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S0 | — | — | — | — | 3.8 Structure; 3.4 Dependency |

```gherkin
Tính năng: Khung dự án và CI/CD

  Kịch bản: Pipeline chạy khi có pull request
    Giả sử một pull request vào nhánh main
    Khi pipeline CI chạy
    Thì build, unit test, lint và kiểm tra kiến trúc của cả 4 thành phần đều chạy
    Và pull request bị chặn nếu một bước thất bại

  Kịch bản: Phát hiện phụ thuộc vòng giữa module
    Giả sử module recording gọi trực tiếp repository của module device
    Khi kiểm tra kiến trúc chạy
    Thì build thất bại và chỉ ra vi phạm
```

*Ghi chú kỹ thuật:* Monorepo theo SADD 3.8; Gradle + ArchUnit/Spring Modulith (cấm phụ thuộc vòng), pytest, Vitest; quét phụ thuộc OWASP/pip-audit/npm audit.

#### TS-02 Môi trường Docker Compose dev/staging

**Là Đội phát triển, tôi muốn dựng toàn bộ hệ thống bằng một lệnh, để mọi người phát triển và kiểm thử trên cùng cấu hình.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S0 | TS-01 | — | — | — | 3.2 Composition; 3.12.2 Triển khai |

```gherkin
Tính năng: Môi trường Docker Compose dev/staging

  Kịch bản: Khởi động môi trường
    Giả sử máy có Docker
    Khi chạy docker compose up
    Thì mọi container ở trạng thái healthy trong 3 phút
    Và trang đăng nhập mở được qua https://localhost
```

*Ghi chú kỹ thuật:* nginx, core, device-gateway, media-worker, mediamtx, postgres, redis, rabbitmq, minio, tunnel-server; secret qua Docker secret; healthcheck cho mọi container.

#### TS-03 PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã

**Là Solution Architect, tôi muốn kiểm chứng MediaMTX với camera thật và luồng giả lập, để xác nhận ADR-03 và giảm rủi ro AR-01 trước khi làm tính năng video.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S0 | TS-02 | BR-08, BR-14 | UC-20, UC-31 | — | ADR-03, AR-01, 3.9.2 |

```gherkin
Tính năng: PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã

  Kịch bản: Đạt ngưỡng hiệu năng
    Giả sử 50 camera thật và 450 luồng RTSP giả lập
    Khi 20 phiên WHEP mở đồng thời
    Thì first frame trung vị ≤ 3 giây và độ trễ ≤ 2 giây trong LAN
    Và CPU media node ≤ 70%

  Kịch bản: Xác thực từng phiên xem
    Giả sử MediaMTX cấu hình xác thực HTTP tới Core
    Khi trình duyệt gửi WHEP offer với token sai
    Thì MediaMTX từ chối phiên xem
```

*Ghi chú kỹ thuật:* Đo first frame, độ trễ, CPU/RAM với 50 camera thật + 500 luồng giả lập; thử authHTTP, hook hoàn tất đoạn ghi, chuyển mã H.265→H.264. Kết quả ghi vào báo cáo PoC.

#### TS-04 Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký

**Là Đội phát triển, tôi muốn có module platform dùng chung, để mọi module nghiệp vụ tự động đáp ứng INV-01, INV-17 và ghi nhật ký.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S0 | TS-01 | BR-30; ràng buộc SaaS | — | — | 3.5 Information; 3.6 Patterns |

```gherkin
Tính năng: Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký

  Kịch bản: Cách ly dữ liệu theo Tổ chức
    Giả sử 2 Tổ chức A và B có dữ liệu camera
    Khi một truy vấn chạy với ngữ cảnh Tổ chức A
    Thì chỉ trả về camera của A, kể cả khi truy vấn quên lọc organization_id

  Kịch bản: Không mất sự kiện khi broker lỗi
    Giả sử RabbitMQ đang dừng
    Khi một giao dịch nghiệp vụ ghi sự kiện vào outbox
    Thì sự kiện được gửi đi khi RabbitMQ hoạt động lại
```

*Ghi chú kỹ thuật:* organization_id + PostgreSQL RLS; transactional outbox → RabbitMQ; AES-256-GCM envelope encryption; AuditableEvent; Flyway baseline.

#### TS-05 Khung Device Gateway và bộ kiểm thử tương thích ONVIF

**Là Đội phát triển, tôi muốn có dịch vụ Python giao tiếp ONVIF và bộ test chạy trên danh sách camera mẫu, để phát hiện sớm khác biệt giữa các hãng (R-01, AR-02).**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S1 | TS-02 | BR-01, BR-27 | UC-10, UC-40 | — | 3.3 Gateway modules; 3.7.3 |

```gherkin
Tính năng: Khung Device Gateway và bộ kiểm thử tương thích ONVIF

  Kịch bản: Báo cáo tương thích
    Giả sử danh sách camera kiểm thử của ít nhất 5 hãng
    Khi chạy bộ kiểm thử tương thích
    Thì có báo cáo theo model: luồng, PTZ, cấu hình, sự kiện, Profile G hỗ trợ hay không
```

*Ghi chú kỹ thuật:* FastAPI + onvif-zeep async; endpoint /discover, /capabilities; mTLS với Core; báo cáo tương thích theo model (D-04).

#### TS-06 Tunnel Server và Site Connector

**Là Đội phát triển, tôi muốn có đường hầm ngược qua WSS 443 từ địa điểm về máy chủ, để Device Gateway và MediaMTX truy cập được camera sau NAT (ADR-10).**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 8 | S6 | TS-02, TS-05 | BR-06 | UC-15, UC-10 | — | ADR-10; 3.9.5 |

```gherkin
Tính năng: Tunnel Server và Site Connector

  Kịch bản: Kết nối chỉ qua cổng 443
    Giả sử địa điểm chỉ cho phép kết nối ra ngoài cổng 443
    Khi Site Connector khởi động
    Thì đường hầm được thiết lập và máy chủ đọc được luồng RTSP của camera trong LAN địa điểm
```

*Ghi chú kỹ thuật:* chisel hoặc tương đương; Connector chạy dò tìm WS-Discovery tại chỗ; theo dõi băng thông từng Connector (AR-06).

#### TS-07 Giám sát và log tập trung

**Là Đội vận hành IT, tôi muốn có Prometheus, Grafana, Loki với dashboard cơ bản, để phát hiện sự cố và đo NFR từ sớm.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S0 | TS-02 | NFR-04 | UC-60 | — | 3.2; 6.1 |

```gherkin
Tính năng: Giám sát và log tập trung

  Kịch bản: Cảnh báo vận hành
    Giả sử container core dừng quá 1 phút
    Khi Prometheus đánh giá rule
    Thì đội vận hành nhận cảnh báo
```

*Ghi chú kỹ thuật:* Metrics JVM, MediaMTX, RabbitMQ queue depth, PostgreSQL; log JSON có correlation id.

#### TS-08 Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA

**Là Đội kiểm thử, tôi muốn chạy kiểm thử tải 500 camera/100 người dùng và kiểm thử xâm nhập theo OWASP ASVS L2, để đủ điều kiện nghiệm thu GA (PNFR-08, NFR-03).**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S9 | Mọi story M1, M2 | NFR-01 đến NFR-05 | — | — | 4 Bảo mật; 6.1 |

```gherkin
Tính năng: Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA

  Kịch bản: Đạt NFR dưới tải
    Giả sử 500 camera và 100 người dùng đồng thời
    Khi chạy kịch bản tải 1 giờ
    Thì NFR-01, NFR-02 đạt ở phân vị 95

  Kịch bản: Không còn lỗi bảo mật nghiêm trọng
    Giả sử báo cáo kiểm thử xâm nhập
    Khi review trước GA
    Thì không còn lỗi mức nghiêm trọng/cao chưa xử lý
```

*Ghi chú kỹ thuật:* Gatling cho API, giả lập RTSP; diễn tập khôi phục (NFR-10).

### EP-01 Kết nối và quản lý camera

#### US-01 Dò tìm camera ONVIF

**Là Technician, tôi muốn hệ thống tự tìm camera trong mạng của địa điểm, để không phải nhập IP từng camera.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S2 | TS-05, US-02, US-05 | BR-01 | UC-10 | cameras.html (Dò tìm ONVIF) | Core device; Device Gateway discovery |

```gherkin
Tính năng: Dò tìm camera ONVIF

  Kịch bản: Tìm thấy camera
    Giả sử địa điểm Kho Bắc Ninh có 3 camera ONVIF chưa thêm
    Khi Technician bấm "Dò tìm"
    Thì trong ≤ 30 giây danh sách hiển thị IP, hãng, model, MAC của 3 camera

  Kịch bản: Camera đã thêm
    Giả sử camera 10.20.1.11 đã có trong hệ thống
    Khi kết quả dò tìm hiển thị
    Thì camera đó được đánh dấu "Đã thêm" và không chọn được

  Kịch bản: Một camera sai mật khẩu
    Giả sử Technician chọn 3 camera và nhập tài khoản chung
    Khi một camera từ chối đăng nhập
    Thì 2 camera được thêm thành công
    Và camera lỗi hiển thị "Sai mật khẩu" kèm nút nhập lại
```

*Ghi chú kỹ thuật:* WS-Discovery; với địa điểm sau NAT dùng Site Connector (TS-06, S6). Thiết bị đã thêm nhận diện theo MAC.

#### US-02 Thêm camera thủ công

**Là Technician, tôi muốn thêm camera bằng IP/cổng/tài khoản hoặc URL RTSP, để kết nối được camera không bật ONVIF.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S2 | TS-04, TS-05, US-05 | BR-02 | UC-11 | cameras.html (Thêm thủ công) | Core device; Device Gateway capabilities; MediaMTX path |

```gherkin
Tính năng: Thêm camera thủ công

  Kịch bản: Kiểm tra kết nối thành công
    Giả sử Technician nhập IP, cổng, tài khoản đúng
    Khi bấm "Kiểm tra kết nối"
    Thì trong ≤ 10 giây hiển thị khung hình xem trước
    Và nút "Lưu camera" được bật

  Kịch bản: Kiểm tra kết nối thất bại
    Giả sử địa chỉ IP không tới được
    Khi bấm "Kiểm tra kết nối"
    Thì hiển thị lỗi "Không tới được địa chỉ"
    Và chỉ lưu được khi tick "Lưu dù chưa kết nối"
```

*Ghi chú kỹ thuật:* Mật khẩu mã hóa khi lưu (INV-17); camera RTSP chỉ xem/ghi (INV-03).

#### US-03 Thêm đầu ghi NVR/DVR

**Là Technician, tôi muốn thêm một đầu ghi và nhận mọi kênh, để không phải thêm từng camera.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S6 | US-02 | BR-03 | UC-12 | cameras.html (Thêm đầu ghi) | Core device (Recorder); Device Gateway |

```gherkin
Tính năng: Thêm đầu ghi NVR/DVR

  Kịch bản: Chọn kênh
    Giả sử đầu ghi 16 kênh, 3 kênh có tín hiệu
    Khi Technician thêm đầu ghi và chọn 3 kênh
    Thì 3 camera được tạo với tên kênh lấy từ đầu ghi

  Kịch bản: Đầu ghi mất kết nối
    Giả sử đầu ghi đang có 3 kênh
    Khi đầu ghi mất kết nối
    Thì cả 3 camera chuyển trạng thái "Mất kết nối"
```

*Ghi chú kỹ thuật:* Mỗi kênh là một Camera liên kết Recorder; trạng thái kênh theo đầu ghi.

#### US-04 Trạng thái camera

**Là Operator, tôi muốn thấy camera nào đang mất kết nối, để báo kỹ thuật kịp thời.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S2 | US-02 | BR-04 | UC-16 | dashboard.html; cameras.html | Core device; Device Gateway events; WebSocket /topic camera-status |

```gherkin
Tính năng: Trạng thái camera

  Kịch bản: Cập nhật trạng thái
    Giả sử camera Kho số 7 đang trực tuyến
    Khi camera mất kết nối lúc 14:32
    Thì trong ≤ 60 giây trạng thái chuyển "Mất kết nối" kèm "từ 14:32"

  Kịch bản: Tổng quan theo địa điểm
    Giả sử Kho Bắc Ninh có 7 camera, 2 mất kết nối
    Khi mở trang Tổng quan
    Thì địa điểm hiển thị "5 / 7" trực tuyến
```

*Ghi chú kỹ thuật:* Kiểm tra định kỳ + sự kiện ONVIF; đẩy WebSocket.

#### US-05 Tổ chức, lọc và chuyển camera

**Là Site Manager, tôi muốn sắp camera theo địa điểm, khu vực, nhóm và chuyển camera khi cần, để tìm nhanh trong hàng trăm camera mà không mất video cũ.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S1 | TS-04 | BR-05, RULE-07 | UC-14 | cameras.html (cây, Chuyển khu vực) | Core organization; CameraLocationHistory |

```gherkin
Tính năng: Tổ chức, lọc và chuyển camera

  Kịch bản: Lọc nhanh
    Giả sử tổ chức có 500 camera
    Khi lọc theo trạng thái "Mất kết nối" và hãng "Dahua"
    Thì kết quả hiển thị trong ≤ 1 giây

  Kịch bản: Một camera một khu vực
    Giả sử camera thuộc khu vực Kho A
    Khi kéo camera sang Kho B
    Thì camera chỉ còn thuộc Kho B và vẫn giữ các nhóm đã gán

  Kịch bản: Chuyển camera giữ video cũ
    Giả sử camera có video 30 ngày ở Tầng 1
    Khi chuyển camera sang Tầng 2
    Thì toàn bộ video, cảnh báo, đánh dấu giữ lại vẫn còn
    Và người có quyền trên Tầng 2 xem được video cũ
    Và nhật ký ghi MOVE_CAMERA
```

*Ghi chú kỹ thuật:* INV-02, INV-18, INV-19; chỉ mục (organization_id, zone_id).

#### US-06 Kích hoạt bộ kết nối địa điểm

**Là Admin, tôi muốn kết nối camera ở chi nhánh không có IP tĩnh, để không phải cấu hình router.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S6 | TS-06 | BR-06 | UC-15 | system.html (Bộ kết nối) | Tunnel Server; Site Connector; Core device (Connector) |

```gherkin
Tính năng: Kích hoạt bộ kết nối địa điểm

  Kịch bản: Kích hoạt thành công
    Giả sử Admin tạo bộ kết nối cho Chi nhánh Đà Nẵng
    Khi Connector chạy với mã kích hoạt trong 24 giờ
    Thì trạng thái chuyển "Trực tuyến" và hiển thị thời điểm liên lạc gần nhất

  Kịch bản: Mã hết hạn
    Giả sử mã kích hoạt tạo đã 25 giờ
    Khi Connector dùng mã đó
    Thì kích hoạt bị từ chối và Admin phải tạo mã mới
```

*Ghi chú kỹ thuật:* Mã kích hoạt dùng 1 lần, hết hạn 24 giờ; trạng thái PENDING_ACTIVATION → ONLINE.

#### US-07 Nhập camera từ file

**Là Technician, tôi muốn nhập danh sách camera từ file, để triển khai địa điểm lớn nhanh hơn.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Could | 3 | S9 | US-02 | BR-07 | UC-13 | cameras.html (Nhập từ file) | Core device |

```gherkin
Tính năng: Nhập camera từ file

  Kịch bản: Bỏ qua dòng lỗi
    Giả sử file có 5 dòng, 2 dòng sai IP hoặc trùng IP
    Khi Technician xác nhận nhập
    Thì 3 camera hợp lệ được thêm
    Và có báo cáo 2 dòng lỗi kèm lý do
```

*Ghi chú kỹ thuật:* Mẫu .xlsx/.csv; xem trước có đánh dấu lỗi; báo cáo kết quả.

### EP-02 Xem trực tiếp

#### US-08 Xem trực tiếp trên trình duyệt

**Là Operator, tôi muốn xem camera trên trình duyệt mà không cài gì, để làm việc từ bất kỳ máy nào.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S3 | TS-03, US-02, US-32 | BR-08 | UC-20 | live.html | Core liveview; MediaMTX WHEP/HLS; /internal/media/authorize |

```gherkin
Tính năng: Xem trực tiếp trên trình duyệt

  Kịch bản: Phát nhanh
    Giả sử Operator có VIEW_LIVE trên camera Cổng chính
    Khi mở camera trên Chrome, Edge, Firefox hoặc Safari
    Thì hình hiển thị trong ≤ 3 giây không cần plugin
    Và độ trễ ≤ 2 giây trong LAN

  Kịch bản: Không có quyền
    Giả sử Viewer không có quyền trên camera Kho A – Lối 1
    Khi gọi API tạo phiên xem cho camera đó
    Thì API trả 403 và MediaMTX không cấp luồng

  Kịch bản: Mạng chặn WebRTC
    Giả sử mạng của người dùng chặn UDP
    Khi mở xem trực tiếp
    Thì trình duyệt tự chuyển sang HLS
```

*Ghi chú kỹ thuật:* Token ký 60 giây; HLS dự phòng; chuyển mã H.265 theo yêu cầu (PNFR-03).

#### US-09 Lưới nhiều camera

**Là Operator, tôi muốn xem nhiều camera cùng lúc, để giám sát toàn khu vực.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S3 | US-08 | BR-09 | UC-20 | live.html | Web player |

```gherkin
Tính năng: Lưới nhiều camera

  Kịch bản: Đổi lưới
    Giả sử Operator đang xem lưới 4
    Khi chọn lưới 9 và kéo 9 camera vào
    Thì 9 ô phát luồng phụ

  Kịch bản: Phóng to một ô
    Giả sử lưới 9 đang phát
    Khi nhấp đúp ô Bãi xe
    Thì ô đó phóng to và chuyển sang luồng chính

  Kịch bản: Camera mất kết nối trong lưới
    Giả sử một camera trong lưới mất kết nối
    Khi lưới hiển thị
    Thì ô hiển thị "Mất kết nối" kèm thời điểm thay vì màn hình đen
```

*Ghi chú kỹ thuật:* Lưới 9/16 dùng luồng phụ; nhấp đúp chuyển luồng chính.

#### US-10 Lưu bố cục xem

**Là Operator, tôi muốn lưu bố cục và danh sách camera đang xem, để mở lại ngay ở ca sau.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S3 | US-09 | BR-34 | UC-21 | live.html (Lưu bố cục) | Core liveview (ViewLayout) |

```gherkin
Tính năng: Lưu bố cục xem

  Kịch bản: Mở lại bố cục
    Giả sử Operator đã lưu bố cục "Ca đêm – Kho Bắc Ninh"
    Khi đăng nhập lại
    Thì bố cục dùng gần nhất tự mở

  Kịch bản: Quyền bị thu hồi
    Giả sử bố cục có camera Kho số 7
    Khi Operator bị thu hồi quyền trên camera đó
    Thì camera tự biến khỏi bố cục đã lưu
```

*Ghi chú kỹ thuật:* Tối đa 10 bố cục/người; INV-13.

#### US-11 Tự chuyển luồng theo băng thông

**Là Operator, tôi muốn hình ảnh không bị đứng khi mạng yếu, để vẫn theo dõi được.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S6 | US-09 | BR-10 | UC-20 | live.html (Mô phỏng mạng yếu) | SADD 3.11.5 (getStats) |

```gherkin
Tính năng: Tự chuyển luồng theo băng thông

  Kịch bản: Tự hạ chất lượng
    Giả sử ô đang phát luồng chính 4 Mbps ở chế độ Tự động
    Khi băng thông đo được dưới 4 Mbps trong 5 giây liên tục
    Thì ô chuyển sang luồng phụ và hiển thị "Chất lượng thấp"

  Kịch bản: Chế độ thủ công
    Giả sử người dùng chọn "Cao" cho một ô
    Khi băng thông giảm
    Thì ô đó không tự chuyển luồng
```

*Ghi chú kỹ thuật:* Quay lại luồng chính khi băng thông ≥ 1,5 lần bitrate trong 30 giây.

#### US-12 Điều khiển PTZ

**Là Operator, tôi muốn xoay, nghiêng, zoom camera, để theo dõi đối tượng di chuyển.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S3 | US-08 | BR-11 | UC-22 | live.html (PTZ) | Device Gateway control; Redis khóa PTZ (3.11.4) |

```gherkin
Tính năng: Điều khiển PTZ

  Kịch bản: Chỉ hiện với camera PTZ
    Giả sử camera Cổng chính không hỗ trợ PTZ
    Khi Operator chọn camera đó
    Thì không hiển thị bảng điều khiển PTZ

  Kịch bản: Tranh chấp điều khiển
    Giả sử Hương (Site Manager) đang điều khiển camera Bãi xe
    Khi Lan (Operator, thứ hạng thấp hơn) gửi lệnh PTZ
    Thì lệnh của Lan bị từ chối
    Và Lan thấy "Đang được điều khiển bởi Hương"
```

*Ghi chú kỹ thuật:* INV-05; khóa 30 giây, ưu tiên theo Role.rank.

#### US-13 Preset PTZ

**Là Operator, tôi muốn gọi nhanh vị trí đặt sẵn, để chuyển đến điểm cần xem trong 1 thao tác.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S3 | US-12 | BR-35 | UC-23 | live.html (Preset) | Core device (PtzPreset); Device Gateway |

```gherkin
Tính năng: Preset PTZ

  Kịch bản: Lưu và gọi preset
    Giả sử camera đang ở vị trí bãi xe
    Khi Operator lưu preset "Bãi xe" rồi gọi lại sau khi xoay camera
    Thì camera trở về đúng vị trí đã lưu
```

*Ghi chú kỹ thuật:* Hiển thị cả preset có sẵn trên camera (source DEVICE).

#### US-14 Chụp ảnh, phóng to số, toàn màn hình

**Là Operator, tôi muốn chụp nhanh khung hình, để lưu bằng chứng tức thời.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 2 | S6 | US-08 | BR-12 | UC-24 | live.html | Web player |

```gherkin
Tính năng: Chụp ảnh, phóng to số, toàn màn hình

  Kịch bản: Chụp ảnh
    Giả sử Operator đang xem camera Bãi xe
    Khi bấm "Chụp ảnh"
    Thì tải về file PNG tên chứa tên camera và thời điểm
```

*Ghi chú kỹ thuật:* Ảnh PNG có tên camera và thời điểm; zoom số tối đa 8x.

#### US-15 Âm thanh hai chiều

**Là Operator, tôi muốn nghe và nói qua camera, để cảnh báo người tại hiện trường.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Could | 5 | S9 | US-08 | BR-13 | UC-25 | live.html (Đàm thoại) | MediaMTX (âm thanh); Web WebRTC |

```gherkin
Tính năng: Âm thanh hai chiều

  Kịch bản: Nhấn giữ để nói
    Giả sử camera có loa và micro, Operator có TALK_AUDIO
    Khi giữ nút "Nhấn giữ để nói"
    Thì âm thanh phát ra loa camera
    Và nhật ký ghi TALK_AUDIO kèm thời lượng
```

*Ghi chú kỹ thuật:* Nhấn giữ để nói; ghi nhật ký TALK_AUDIO.

### EP-03 Ghi hình và xem lại

#### US-16 Chế độ ghi hình

**Là Admin, tôi muốn đặt chế độ ghi cho từng camera, để cân bằng lưu trữ và nhu cầu bằng chứng.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S4 | TS-03, US-02 | BR-14 | UC-30 | camera-detail.html (Ghi hình) | Core recording; MediaMTX record API |

```gherkin
Tính năng: Chế độ ghi hình

  Kịch bản: Ghi theo sự kiện
    Giả sử camera đặt chế độ "Theo sự kiện", trước 10 giây, sau 30 giây
    Khi camera phát hiện chuyển động lúc 23:40:05
    Thì có đoạn ghi từ 23:39:55 đến ít nhất 23:40:35

  Kịch bản: Áp cho nhiều camera
    Giả sử Admin chọn 10 camera
    Khi áp chế độ "Liên tục"
    Thì cả 10 camera hiển thị "REC" trên ô xem trực tiếp
```

*Ghi chú kỹ thuật:* Liên tục / lịch tuần / sự kiện (trước 10 giây, sau 30 giây).

#### US-17 Xem lại theo dòng thời gian

**Là Operator, tôi muốn chọn ngày giờ trên timeline, để tìm đúng đoạn cần xem.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S4 | US-16 | BR-15 | UC-31 | playback.html | Core recording (recording_segment); MediaMTX playback |

```gherkin
Tính năng: Xem lại theo dòng thời gian

  Kịch bản: Phát lại nhanh
    Giả sử camera có video ngày 21/10
    Khi Operator chọn 13:58 trên timeline
    Thì video bắt đầu phát trong ≤ 3 giây

  Kịch bản: Không có dữ liệu
    Giả sử khoảng 05:12–05:18 không có ghi hình
    Khi chọn 05:15
    Thì nhảy tới đoạn có dữ liệu gần nhất và thông báo

  Kịch bản: Hiển thị timeline
    Giả sử ngày có đoạn ghi, sự kiện và khoảng trống
    Khi mở timeline 24 giờ
    Thì ba loại hiển thị bằng màu khác nhau
    Và lịch đánh dấu ngày có dữ liệu
```

*Ghi chú kỹ thuật:* Chỉ mục (camera_id, start_at); ghi nhật ký VIEW_PLAYBACK.

#### US-18 Điều khiển phát lại

**Là Operator, tôi muốn tua nhanh và xem từng khung hình, để rà video dài nhanh hơn.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S4 | US-17 | BR-16 | UC-31 | playback.html | Web player |

```gherkin
Tính năng: Điều khiển phát lại

  Kịch bản: Tua và phím tắt
    Giả sử video đang phát
    Khi chọn 16x rồi nhấn Space và →
    Thì video phát 16x, sau đó dừng và tiến 10 giây
```

*Ghi chú kỹ thuật:* 0,5x–16x; phím tắt Space, ←/→.

#### US-19 Xem lại đồng bộ nhiều camera

**Là Operator, tôi muốn phát lại nhiều camera cùng mốc thời gian, để theo dấu một người qua nhiều góc quay.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S8 | US-17 | BR-17 | UC-32 | playback.html (Xem đồng bộ) | Web player |

```gherkin
Tính năng: Xem lại đồng bộ nhiều camera

  Kịch bản: Đồng bộ 4 camera
    Giả sử Operator chọn 4 camera vào chế độ đồng bộ
    Khi chọn 14:00 trên timeline chung
    Thì cả 4 camera phát từ 14:00, lệch nhau ≤ 1 giây

  Kịch bản: Giới hạn 4 camera
    Giả sử đã có 4 camera đồng bộ
    Khi chọn thêm camera thứ 5
    Thì hệ thống không cho thêm và thông báo giới hạn
```

*Ghi chú kỹ thuật:* Tối đa 4 camera; lệch ≤ 1 giây.

#### US-20 Xuất clip

**Là Site Manager, tôi muốn xuất đoạn video ra file, để gửi cho bên liên quan.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S5 | US-17 | BR-18, RULE-01 | UC-33 | playback.html (Xuất clip) | Core export; RabbitMQ; Media Worker FFmpeg; MinIO |

```gherkin
Tính năng: Xuất clip

  Kịch bản: Xuất thành công
    Giả sử Site Manager có EXPORT_VIDEO
    Khi chọn 13:58–14:03 và bấm "Xuất"
    Thì nhận thông báo khi clip sẵn sàng
    Và file MP4 phát được bằng trình phát phổ biến
    Và nhật ký ghi EXPORT_VIDEO

  Kịch bản: Quá 60 phút
    Giả sử khoảng chọn dài 75 phút
    Khi bấm "Xuất"
    Thì hệ thống yêu cầu rút ngắn và không tạo công việc xuất

  Kịch bản: Không có quyền
    Giả sử Operator không có EXPORT_VIDEO
    Khi mở màn hình Xem lại
    Thì không thấy nút "Xuất clip"

  Kịch bản: Liên kết hết hạn
    Giả sử clip READY từ 25 giờ trước
    Khi người dùng mở liên kết tải
    Thì trạng thái "Hết hạn" và phải xuất lại
```

*Ghi chú kỹ thuật:* MP4 H.264; ExportJob QUEUED→PROCESSING→READY→EXPIRED; URL ký 5 phút.

#### US-21 Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa

**Là Admin, tôi muốn đặt thời gian lưu và giữ lại đoạn cần điều tra, để kiểm soát dung lượng và tuân thủ quy định.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S4 | US-16 | BR-19, RULE-02 | UC-34, UC-35, UC-36 | system.html (Lưu trữ); camera-detail.html; playback.html (Giữ lại) | Core recording (RetentionPolicy, EvidenceHold); SADD 3.11.2 |

```gherkin
Tính năng: Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa

  Kịch bản: Ghi đè theo camera
    Giả sử mặc định tổ chức 30 ngày, camera Quầy 1 ghi đè 90 ngày
    Khi job xóa chạy
    Thì video Quầy 1 dưới 90 ngày không bị xóa

  Kịch bản: Giữ lại phục vụ điều tra
    Giả sử đoạn 13:45–14:15 ngày 15/10 được giữ lại
    Khi đoạn đó quá hạn lưu trữ
    Thì đoạn đó không bị xóa cho đến khi bỏ đánh dấu

  Kịch bản: Quyền giữ lại
    Giả sử Operator không có HOLD_EVIDENCE
    Khi mở màn hình Xem lại
    Thì không thấy nút "Giữ lại phục vụ điều tra"
```

*Ghi chú kỹ thuật:* Job 02:00 hằng ngày, xóa lô 500; giá trị mặc định chờ OQ-04.

#### US-22 Xem lại từ thiết bị

**Là Operator, tôi muốn xem video trên thẻ nhớ hoặc đầu ghi, để có dữ liệu cả khi máy chủ không ghi được.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 8 | S7 | US-17, TS-05 | BR-20 | UC-31 | playback.html (Nguồn: Thiết bị) | Device Gateway edge_playback (Profile G); MediaMTX |

```gherkin
Tính năng: Xem lại từ thiết bị

  Kịch bản: Nguồn thiết bị
    Giả sử camera hỗ trợ Profile G và có video trên thẻ nhớ
    Khi chọn nguồn "Thiết bị"
    Thì timeline hiển thị đoạn video trên thẻ nhớ và phát được

  Kịch bản: Không hỗ trợ
    Giả sử camera không hỗ trợ Profile G
    Khi mở Xem lại
    Thì tùy chọn nguồn "Thiết bị" bị vô hiệu kèm lý do
```

*Ghi chú kỹ thuật:* Chỉ với camera Profile G hoặc kênh đầu ghi; xuất clip theo cùng quy tắc US-20.

### EP-04 Cấu hình camera

#### US-23 Cấu hình luồng video

**Là Technician, tôi muốn chỉnh độ phân giải, fps, bitrate, chuẩn nén từ CamHub, để không phải vào giao diện của từng camera.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S4 | US-29 | BR-21 | UC-40 | camera-detail.html (Luồng video) | Core configuration; Device Gateway control |

```gherkin
Tính năng: Cấu hình luồng video

  Kịch bản: Áp dụng thành công
    Giả sử camera trực tuyến
    Khi Technician đổi bitrate luồng chính và bấm "Áp dụng"
    Thì trong ≤ 15 giây hiển thị "Đã áp dụng" cho tham số đó

  Kịch bản: Áp dụng một phần
    Giả sử Technician đổi 3 tham số
    Khi camera từ chối 1 tham số
    Thì 2 tham số được áp dụng
    Và tham số bị từ chối hiển thị thông báo lỗi do camera trả về
```

*Ghi chú kỹ thuật:* Kết quả theo từng tham số; nhật ký CHANGE_CONFIG.

#### US-24 Cấu hình hình ảnh

**Là Technician, tôi muốn chỉnh sáng, tương phản, hồng ngoại, WDR, để hình ảnh rõ hơn mà không cần đến hiện trường.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 3 | S7 | US-23 | BR-22 | UC-40 | camera-detail.html (Hình ảnh) | Core configuration; Device Gateway control |

```gherkin
Tính năng: Cấu hình hình ảnh

  Kịch bản: Khôi phục
    Giả sử Technician đã kéo thanh độ sáng
    Khi bấm "Khôi phục giá trị trước"
    Thì các giá trị quay về như trước khi chỉnh
```

*Ghi chú kỹ thuật:* Xem trước trực tiếp; khôi phục giá trị trước.

#### US-25 Cấu hình mạng và thời gian

**Là Technician, tôi muốn đặt IP, NTP, múi giờ cho camera, để thời gian video chính xác.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 3 | S8 | US-23 | BR-23 | UC-40 | camera-detail.html (Mạng & thời gian) | Core configuration; Device Gateway control |

```gherkin
Tính năng: Cấu hình mạng và thời gian

  Kịch bản: Đổi IP
    Giả sử Technician sửa địa chỉ IP
    Khi bấm "Áp dụng"
    Thì hệ thống cảnh báo có thể mất kết nối tạm thời
    Và sau khi đổi thành công, camera được cập nhật địa chỉ mới

  Kịch bản: Lệch giờ
    Giả sử camera lệch 7 giây so với máy chủ
    Khi mở danh sách camera
    Thì camera được đánh dấu lệch giờ
```

*Ghi chú kỹ thuật:* Cảnh báo đổi IP; đánh dấu camera lệch giờ > 5 giây.

#### US-26 Vùng chuyển động và vùng che riêng tư

**Là Technician, tôi muốn vẽ vùng phát hiện chuyển động và vùng che, để giảm cảnh báo giả và bảo vệ quyền riêng tư.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S5 | US-23 | BR-24, RULE-03 | UC-40 | camera-detail.html (Vùng) | Core configuration (MotionZone, PrivacyMask); Device Gateway |

```gherkin
Tính năng: Vùng chuyển động và vùng che riêng tư

  Kịch bản: Vùng che trên video ghi
    Giả sử Technician vẽ vùng che cửa sổ nhà dân
    Khi áp dụng
    Thì vùng đó bị che cả khi xem trực tiếp lẫn trong video ghi

  Kịch bản: Giới hạn camera
    Giả sử camera chỉ hỗ trợ 4 vùng che
    Khi vẽ vùng thứ 5
    Thì hệ thống không cho thêm và nêu giới hạn
```

*Ghi chú kỹ thuật:* Lưới ô hoặc đa giác theo khả năng camera; vùng che áp dụng trên camera.

#### US-27 Cấu hình mẫu

**Là Technician, tôi muốn tạo mẫu cấu hình và áp cho nhiều camera, để cấu hình hàng loạt trong một lần.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S8 | US-23, US-24, US-16 | BR-25 | UC-41 | templates.html | Core configuration (ConfigTemplate, TemplateApplication) |

```gherkin
Tính năng: Cấu hình mẫu

  Kịch bản: Áp mẫu cho 4 camera
    Giả sử mẫu "Kho – ghi theo sự kiện"
    Khi áp cho 4 camera, 1 camera không hỗ trợ WDR, 1 camera mất kết nối
    Thì kết quả: 2 thành công, 1 một phần (bỏ qua WDR), 1 thất bại
    Và có nút "Áp lại" cho camera thất bại
```

*Ghi chú kỹ thuật:* Kết quả SUCCESS/PARTIAL/FAILED từng camera; áp lại riêng camera lỗi.

#### US-28 Đổi mật khẩu, khởi động lại camera

**Là Technician, tôi muốn đổi mật khẩu và khởi động lại camera từ xa, để xử lý sự cố nhanh.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 3 | S6 | US-02 | BR-26 | UC-42 | cameras.html (Đổi mật khẩu); camera-detail.html (Khởi động lại) | Device Gateway control; Core device |

```gherkin
Tính năng: Đổi mật khẩu, khởi động lại camera

  Kịch bản: Đổi mật khẩu hàng loạt
    Giả sử 4 camera được chọn
    Khi đổi mật khẩu
    Thì mật khẩu mới được lưu cho các camera đổi thành công
    Và camera đổi thất bại giữ mật khẩu cũ và được báo lỗi

  Kịch bản: Khởi động lại
    Giả sử camera Kho số 7 treo
    Khi Technician xác nhận khởi động lại
    Thì hệ thống theo dõi đến khi camera trực tuyến lại
```

*Ghi chú kỹ thuật:* Cập nhật mật khẩu đã lưu khi đổi thành công; nhật ký REBOOT_CAMERA, CHANGE_CAMERA_PASSWORD.

#### US-29 Chỉ hiển thị tham số được hỗ trợ

**Là Technician, tôi muốn chỉ thấy tùy chọn camera thực sự hỗ trợ, để không cấu hình sai.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S3 | TS-05, US-02 | BR-27 | UC-40 | camera-detail.html | Device Gateway capabilities; DeviceCapabilities |

```gherkin
Tính năng: Chỉ hiển thị tham số được hỗ trợ

  Kịch bản: Ẩn tùy chọn không hỗ trợ
    Giả sử luồng phụ camera chỉ hỗ trợ H.264
    Khi mở cấu hình luồng phụ
    Thì danh sách chuẩn nén chỉ có H.264
```

*Ghi chú kỹ thuật:* Đọc GetVideoEncoderConfigurationOptions… và lưu vào DeviceCapabilities.

### EP-05 Người dùng, phân quyền, nhật ký

#### US-30 Đăng nhập tài khoản cá nhân

**Là Người dùng, tôi muốn đăng nhập bằng tài khoản riêng, để mọi thao tác gắn với tôi.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S1 | TS-04 | BR-28, RULE-04 | UC-01, UC-03 | login.html | Core identity; Spring Security; JWT 15 phút + refresh cookie |

```gherkin
Tính năng: Đăng nhập tài khoản cá nhân

  Kịch bản: Đăng nhập thành công
    Giả sử tài khoản cục bộ hợp lệ
    Khi nhập đúng email và mật khẩu
    Thì vào trang mặc định theo vai trò
    Và nhật ký ghi LOGIN

  Kịch bản: Khóa tài khoản
    Giả sử người dùng đã sai mật khẩu 4 lần liên tiếp
    Khi sai lần thứ 5
    Thì tài khoản bị khóa và nhật ký ghi LOGIN_FAILED

  Kịch bản: Hết phiên
    Giả sử người dùng không thao tác 12 giờ
    Khi gọi một API
    Thì phải đăng nhập lại
```

*Ghi chú kỹ thuật:* Argon2id; khóa sau 5 lần sai; phiên hết hạn 12 giờ không hoạt động.

#### US-31 Xác thực hai lớp

**Là Admin, tôi muốn bắt buộc 2FA cho các vai trò quan trọng, để giảm rủi ro lộ mật khẩu.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S2 | US-30 | BR-36 | UC-02 | login.html (2FA); system.html (Bảo mật) | Core identity (TOTP, RecoveryCode) |

```gherkin
Tính năng: Xác thực hai lớp

  Kịch bản: Bắt buộc 2FA
    Giả sử Admin bật bắt buộc 2FA cho vai trò Technician
    Khi Minh (Technician) đăng nhập lần kế tiếp
    Thì Minh phải thiết lập 2FA trước khi vào hệ thống

  Kịch bản: Mã dự phòng
    Giả sử người dùng mất điện thoại
    Khi nhập một mã dự phòng hợp lệ
    Thì đăng nhập thành công và mã đó không dùng lại được
```

*Ghi chú kỹ thuật:* TOTP; 10 mã dự phòng; INV-11, INV-25.

#### US-32 Phân quyền theo vai trò và phạm vi

**Là Admin, tôi muốn gán vai trò và phạm vi camera cho từng người, để mỗi người chỉ thấy và làm đúng việc của mình.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 8 | S1 | US-05, US-30 | BR-29 | UC-03, UC-05 | users.html | Core access (PermissionEvaluator, cache Redis 3.11.1) |

```gherkin
Tính năng: Phân quyền theo vai trò và phạm vi

  Kịch bản: Phạm vi theo địa điểm
    Giả sử Lan là Operator phạm vi Kho Bắc Ninh
    Khi Lan mở danh sách camera
    Thì chỉ thấy camera của Kho Bắc Ninh

  Kịch bản: Thu hồi quyền có hiệu lực nhanh
    Giả sử Lan đang xem camera Cổng sau
    Khi Admin gỡ quyền của Lan trên Kho Bắc Ninh
    Thì trong ≤ 60 giây ô xem dừng và hiển thị "Không có quyền"

  Kịch bản: Site Manager tạo người dùng
    Giả sử Hương là Site Manager của Chi nhánh Hà Nội
    Khi Hương tạo người dùng mới và chọn phạm vi
    Thì chỉ chọn được phạm vi trong Chi nhánh Hà Nội
    Và không chọn được vai trò Admin
```

*Ghi chú kỹ thuật:* INV-10, INV-22, INV-27; hiệu lực ≤ 60 giây; Site Manager tạo người dùng trong địa điểm của mình.

#### US-33 Nhật ký thao tác

**Là Admin, tôi muốn xem ai đã làm gì, để truy vết khi có sự cố.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S2 | TS-04 | BR-30, RULE-06 | UC-08 | audit.html | Core audit; bảng audit_log phân vùng, trigger chặn sửa/xóa |

```gherkin
Tính năng: Nhật ký thao tác

  Kịch bản: Tra cứu
    Giả sử có thao tác xuất clip lúc 18:05
    Khi Admin lọc loại EXPORT_VIDEO ngày 21/10
    Thì thấy bản ghi gồm người, thời điểm, IP, đối tượng, kết quả

  Kịch bản: Không sửa được
    Giả sử một bản ghi nhật ký
    Khi bất kỳ vai trò nào cố sửa hoặc xóa (kể cả qua DB bằng tài khoản ứng dụng)
    Thì thao tác bị từ chối
```

*Ghi chú kỹ thuật:* Lọc theo người, thời gian, loại, camera; xuất CSV (cũng được ghi nhật ký).

#### US-43 Vai trò tùy chỉnh

**Là Admin, tôi muốn tạo vai trò riêng từ tập quyền, để phù hợp với vị trí công việc của tổ chức.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S8 | US-32 | BR-39 | UC-04 | users.html (Vai trò) | Core access (Role, MANAGE_ROLES) |

```gherkin
Tính năng: Vai trò tùy chỉnh

  Kịch bản: Tạo từ vai trò có sẵn
    Giả sử Admin sao chép vai trò Operator
    Khi đặt tên "Bảo vệ ca đêm" và bỏ quyền VIEW_PLAYBACK
    Thì vai trò mới được tạo với các quyền còn lại

  Kịch bản: Không xóa vai trò đang dùng
    Giả sử vai trò "Bảo vệ ca đêm" đang gán cho 4 người
    Khi Admin bấm xóa
    Thì bị từ chối và hiển thị số người đang dùng

  Kịch bản: Vai trò mặc định
    Giả sử vai trò Operator là vai trò hệ thống
    Khi Admin mở vai trò đó
    Thì không sửa, đổi tên hoặc xóa được
```

*Ghi chú kỹ thuật:* INV-20, INV-21, INV-22.

#### US-44 Đăng nhập bằng LDAP/Active Directory

**Là Admin, tôi muốn người dùng đăng nhập bằng tài khoản của tổ chức, để quản lý tài khoản tập trung và vô hiệu hóa ngay khi nghỉ việc.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 8 | S8 | US-30, US-32 | BR-40, RULE-05 | UC-01, UC-06, UC-07 | users.html (LDAP); login.html | Core identity (DirectoryConnection, DirectoryGroupMapping); LDAPS |

```gherkin
Tính năng: Đăng nhập bằng LDAP/Active Directory

  Kịch bản: Đăng nhập lần đầu
    Giả sử Hương thuộc nhóm AD CamHub-SiteMgr-HN đã ánh xạ
    Khi Hương đăng nhập bằng tài khoản AD lần đầu
    Thì tài khoản được tạo với vai trò Site Manager phạm vi Chi nhánh Hà Nội

  Kịch bản: Nghỉ việc
    Giả sử tài khoản AD của Quang bị vô hiệu hóa
    Khi đến lần đồng bộ kế tiếp
    Thì tài khoản CamHub của Quang chuyển "Vô hiệu hóa"

  Kịch bản: Máy chủ thư mục lỗi
    Giả sử không kết nối được LDAP/AD
    Khi người dùng thư mục đăng nhập
    Thì thấy thông báo không kết nối được máy chủ thư mục
    Và Admin cục bộ vẫn đăng nhập được
```

*Ghi chú kỹ thuật:* Đồng bộ mỗi giờ; INV-23 đến INV-26; cần D-06 (tài khoản dịch vụ).

### EP-06 Cảnh báo

#### US-34 Cảnh báo mất kết nối

**Là Technician, tôi muốn được báo khi camera mất kết nối, để xử lý trước khi mất dữ liệu.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S5 | US-04 | BR-31 | UC-50, UC-51 | alerts.html | Core alerting (Bộ hẹn giờ); notification (Web, Email) |

```gherkin
Tính năng: Cảnh báo mất kết nối

  Kịch bản: Sau thời gian chờ
    Giả sử thời gian chờ 5 phút
    Khi camera mất kết nối 6 phút
    Thì tạo cảnh báo CAMERA_OFFLINE và gửi cho người nhận

  Kịch bản: Gộp theo địa điểm
    Giả sử bộ kết nối Kho Bắc Ninh mất kết nối
    Khi 7 camera cùng mất kết nối
    Thì chỉ tạo 1 cảnh báo SITE_OFFLINE

  Kịch bản: Tự phục hồi
    Giả sử cảnh báo mất kết nối đang mở
    Khi camera kết nối lại
    Thì cảnh báo chuyển "Đã phục hồi"
```

*Ghi chú kỹ thuật:* offlineDelayMinutes mặc định 5; gộp cấp địa điểm (SITE_OFFLINE); tự RECOVERED.

#### US-35 Cảnh báo dung lượng lưu trữ

**Là Admin, tôi muốn được báo khi lưu trữ sắp đầy, để không bị dừng ghi.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 2 | S5 | US-16 | BR-37 | UC-50, UC-51 | alerts.html; system.html (Lưu trữ) | Core recording (StorageVolume); alerting |

```gherkin
Tính năng: Cảnh báo dung lượng lưu trữ

  Kịch bản: Vượt ngưỡng
    Giả sử ngưỡng cảnh báo 85%
    Khi NAS-01 đạt 86%
    Thì tạo cảnh báo kèm dung lượng còn lại và số ngày ước tính
```

*Ghi chú kỹ thuật:* Ngưỡng 85% và 95%; ước tính số ngày trước khi đầy.

#### US-36 Cảnh báo chuyển động đa kênh

**Là Operator, tôi muốn nhận cảnh báo chuyển động qua web, email, Telegram và Zalo, để phản ứng kịp thời ngoài giờ.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 8 | S7 | US-34, US-26 | BR-32 | UC-50, UC-51, UC-53 | alerts.html (Quy tắc, Kênh nhận); system.html (Kênh thông báo) | Device Gateway events (PullPoint); RabbitMQ; Core alerting, notification; Redis throttle; Media Worker snapshot |

```gherkin
Tính năng: Cảnh báo chuyển động đa kênh

  Kịch bản: Trong khung giờ
    Giả sử quy tắc 22:00–06:00 cho Kho Bắc Ninh, kênh Telegram
    Khi camera Cổng sau báo chuyển động lúc 23:40
    Thì Lan nhận Telegram gồm tên camera, thời điểm, ảnh và liên kết

  Kịch bản: Ngoài khung giờ
    Giả sử cùng quy tắc
    Khi có chuyển động lúc 10:00
    Thì không gửi thông báo

  Kịch bản: Chống spam
    Giả sử chống spam 120 giây
    Khi camera báo chuyển động 3 lần trong 1 phút
    Thì chỉ gửi 1 thông báo; các sự kiện vẫn được đánh dấu trên timeline

  Kịch bản: Camera không hỗ trợ
    Giả sử camera không có chức năng phát hiện chuyển động
    Khi tạo quy tắc chuyển động cho camera đó
    Thì tùy chọn bị ẩn kèm lý do
```

*Ghi chú kỹ thuật:* motionSource = DEVICE; chống spam bằng SET NX EX; Zalo phụ thuộc OQ-P02.

#### US-37 Xử lý và chuyển giao cảnh báo

**Là Operator, tôi muốn nhận, ghi chú, chuyển giao và đóng cảnh báo, để cả ca biết cảnh báo nào đã có người xử lý.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 5 | S7 | US-34 | BR-33 | UC-52 | alerts.html | Core alerting (Alert, AlertStatusChange, AlertHandover) |

```gherkin
Tính năng: Xử lý và chuyển giao cảnh báo

  Kịch bản: Nhận xử lý
    Giả sử cảnh báo ở trạng thái Mới
    Khi Lan bấm "Nhận xử lý"
    Thì trạng thái Đang xử lý, phụ trách là Lan

  Kịch bản: Chuyển giao
    Giả sử Lan đang xử lý cảnh báo
    Khi Lan chuyển giao cho Minh kèm lý do
    Thì Minh thành người phụ trách và nhận thông báo
    Và lịch sử ghi lần chuyển giao

  Kịch bản: Đóng bắt buộc kết quả
    Giả sử cảnh báo đang xử lý
    Khi đóng mà không chọn kết quả
    Thì hệ thống không cho đóng
```

*Ghi chú kỹ thuật:* INV-14, INV-28; lịch sử trạng thái và chuyển giao.

#### US-38 Mở xem lại từ cảnh báo

**Là Operator, tôi muốn mở ngay video tại thời điểm cảnh báo, để xác minh trong vài giây.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 2 | S7 | US-17, US-37 | BR-38 | UC-52, UC-31 | alerts.html → playback.html | Web; Core recording |

```gherkin
Tính năng: Mở xem lại từ cảnh báo

  Kịch bản: Xem lại từ cảnh báo
    Giả sử cảnh báo lúc 23:40:05
    Khi Operator bấm "Xem lại"
    Thì video phát từ 23:39:55
    Và có nút chuyển sang xem trực tiếp
```

*Ghi chú kỹ thuật:* Bắt đầu tại thời điểm cảnh báo trừ 10 giây.

### EP-07 Nền tảng và sẵn sàng SaaS

#### US-39 Bảng tổng quan hệ thống

**Là Admin, tôi muốn xem sức khỏe hệ thống, để phát hiện sớm vấn đề.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S5 | US-04, US-35 | NFR-04 | UC-60 | dashboard.html | Core platform; /system/overview |

```gherkin
Tính năng: Bảng tổng quan hệ thống

  Kịch bản: Hiển thị chỉ số
    Giả sử 14/16 camera trực tuyến và 3 cảnh báo mở
    Khi Admin mở Tổng quan
    Thì thấy "14 / 16" và "3" cùng danh sách camera mất kết nối
```

*Ghi chú kỹ thuật:* Camera trực tuyến, lưu trữ, cảnh báo mở, bộ kết nối, sao lưu gần nhất.

#### US-40 Sao lưu và khôi phục

**Là Đội vận hành IT, tôi muốn hệ thống sao lưu tự động và có runbook khôi phục, để khôi phục được khi có sự cố.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 5 | S5 | TS-02 | NFR-10, NFR-11 | UC-61, UC-62 | system.html (Sao lưu, chỉ xem) | pgBackRest; SADD 3.12.3 |

```gherkin
Tính năng: Sao lưu và khôi phục

  Kịch bản: Sao lưu định kỳ
    Giả sử hệ thống đang chạy
    Khi mỗi giờ
    Thì có bản sao lưu mới và giữ ≥ 30 bản

  Kịch bản: Diễn tập khôi phục
    Giả sử máy chủ ứng dụng bị hỏng
    Khi đội vận hành IT làm theo runbook
    Thì hệ thống hoạt động lại trong ≤ 4 giờ, mất tối đa 1 giờ dữ liệu

  Kịch bản: Admin không khôi phục được
    Giả sử Admin của tổ chức
    Khi mở tab Sao lưu
    Thì chỉ thấy trạng thái, không có nút khôi phục
```

*Ghi chú kỹ thuật:* Khôi phục chỉ do đội vận hành IT (UCQ-03); giao diện Admin chỉ xem trạng thái.

#### US-41 Đa ngôn ngữ và responsive

**Là Người dùng, tôi muốn dùng giao diện tiếng Việt hoặc tiếng Anh trên mọi màn hình, để làm việc thuận tiện.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Must | 3 | S1 | TS-01 | NFR-07 | UC-63 | Mọi màn hình | Web vue-i18n |

```gherkin
Tính năng: Đa ngôn ngữ và responsive

  Kịch bản: Đổi ngôn ngữ
    Giả sử người dùng đang dùng tiếng Việt
    Khi chọn English trong hồ sơ
    Thì toàn bộ giao diện chuyển sang tiếng Anh

  Kịch bản: Màn hình điện thoại
    Giả sử màn hình rộng 360px
    Khi mở Xem trực tiếp
    Thì không có cuộn ngang và lưới tối đa 4 ô
```

*Ghi chú kỹ thuật:* Thiết lập khung i18n và responsive từ S1; mỗi story sau tự đáp ứng (DoD).

#### US-42 Trung tâm thông báo trên web

**Là Operator, tôi muốn thấy cảnh báo mới ngay trên web, để không bỏ sót khi đang xem camera.**

| Ưu tiên | Điểm | Sprint | Phụ thuộc | BR | Use case | Màn hình | Thành phần (SADD) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Should | 3 | S7 | US-34 | BR-32 | UC-51, UC-52 | Thanh trên cùng (chuông); alerts.html | WebSocket /user/queue/alerts |

```gherkin
Tính năng: Trung tâm thông báo trên web

  Kịch bản: Cảnh báo mới
    Giả sử Operator đang ở màn hình Xem trực tiếp
    Khi có cảnh báo mới trong phạm vi của Operator
    Thì chuông tăng số đếm và hiện toast kèm liên kết
```

*Ghi chú kỹ thuật:* Toast + âm báo bật/tắt được.

## 6. Ma trận truy vết

| Item | BR | Use case | Màn hình | Sprint |
| --- | --- | --- | --- | --- |
| TS-01 | — | — | — | S0 |
| TS-02 | — | — | — | S0 |
| TS-03 | BR-08, BR-14 | UC-20, UC-31 | — | S0 |
| TS-04 | BR-30; ràng buộc SaaS | — | — | S0 |
| TS-05 | BR-01, BR-27 | UC-10, UC-40 | — | S1 |
| TS-06 | BR-06 | UC-15, UC-10 | — | S6 |
| TS-07 | NFR-04 | UC-60 | — | S0 |
| TS-08 | NFR-01 đến NFR-05 | — | — | S9 |
| US-01 | BR-01 | UC-10 | cameras.html (Dò tìm ONVIF) | S2 |
| US-02 | BR-02 | UC-11 | cameras.html (Thêm thủ công) | S2 |
| US-03 | BR-03 | UC-12 | cameras.html (Thêm đầu ghi) | S6 |
| US-04 | BR-04 | UC-16 | dashboard.html; cameras.html | S2 |
| US-05 | BR-05, RULE-07 | UC-14 | cameras.html (cây, Chuyển khu vực) | S1 |
| US-06 | BR-06 | UC-15 | system.html (Bộ kết nối) | S6 |
| US-07 | BR-07 | UC-13 | cameras.html (Nhập từ file) | S9 |
| US-08 | BR-08 | UC-20 | live.html | S3 |
| US-09 | BR-09 | UC-20 | live.html | S3 |
| US-10 | BR-34 | UC-21 | live.html (Lưu bố cục) | S3 |
| US-11 | BR-10 | UC-20 | live.html (Mô phỏng mạng yếu) | S6 |
| US-12 | BR-11 | UC-22 | live.html (PTZ) | S3 |
| US-13 | BR-35 | UC-23 | live.html (Preset) | S3 |
| US-14 | BR-12 | UC-24 | live.html | S6 |
| US-15 | BR-13 | UC-25 | live.html (Đàm thoại) | S9 |
| US-16 | BR-14 | UC-30 | camera-detail.html (Ghi hình) | S4 |
| US-17 | BR-15 | UC-31 | playback.html | S4 |
| US-18 | BR-16 | UC-31 | playback.html | S4 |
| US-19 | BR-17 | UC-32 | playback.html (Xem đồng bộ) | S8 |
| US-20 | BR-18, RULE-01 | UC-33 | playback.html (Xuất clip) | S5 |
| US-21 | BR-19, RULE-02 | UC-34, UC-35, UC-36 | system.html (Lưu trữ); camera-detail.html; playback.html (Giữ lại) | S4 |
| US-22 | BR-20 | UC-31 | playback.html (Nguồn: Thiết bị) | S7 |
| US-23 | BR-21 | UC-40 | camera-detail.html (Luồng video) | S4 |
| US-24 | BR-22 | UC-40 | camera-detail.html (Hình ảnh) | S7 |
| US-25 | BR-23 | UC-40 | camera-detail.html (Mạng & thời gian) | S8 |
| US-26 | BR-24, RULE-03 | UC-40 | camera-detail.html (Vùng) | S5 |
| US-27 | BR-25 | UC-41 | templates.html | S8 |
| US-28 | BR-26 | UC-42 | cameras.html (Đổi mật khẩu); camera-detail.html (Khởi động lại) | S6 |
| US-29 | BR-27 | UC-40 | camera-detail.html | S3 |
| US-30 | BR-28, RULE-04 | UC-01, UC-03 | login.html | S1 |
| US-31 | BR-36 | UC-02 | login.html (2FA); system.html (Bảo mật) | S2 |
| US-32 | BR-29 | UC-03, UC-05 | users.html | S1 |
| US-33 | BR-30, RULE-06 | UC-08 | audit.html | S2 |
| US-43 | BR-39 | UC-04 | users.html (Vai trò) | S8 |
| US-44 | BR-40, RULE-05 | UC-01, UC-06, UC-07 | users.html (LDAP); login.html | S8 |
| US-34 | BR-31 | UC-50, UC-51 | alerts.html | S5 |
| US-35 | BR-37 | UC-50, UC-51 | alerts.html; system.html (Lưu trữ) | S5 |
| US-36 | BR-32 | UC-50, UC-51, UC-53 | alerts.html (Quy tắc, Kênh nhận); system.html (Kênh thông báo) | S7 |
| US-37 | BR-33 | UC-52 | alerts.html | S7 |
| US-38 | BR-38 | UC-52, UC-31 | alerts.html → playback.html | S7 |
| US-39 | NFR-04 | UC-60 | dashboard.html | S5 |
| US-40 | NFR-10, NFR-11 | UC-61, UC-62 | system.html (Sao lưu, chỉ xem) | S5 |
| US-41 | NFR-07 | UC-63 | Mọi màn hình | S1 |
| US-42 | BR-32 | UC-51, UC-52 | Thanh trên cùng (chuông); alerts.html | S7 |

## 7. Giả định

- Đội khoảng 5–6 người (2 backend Java, 1 Python, 2 frontend Vue, 1 QA) với vận tốc khoảng 30 điểm/sprint; cần hiệu chỉnh sau 2 sprint đầu.
- Story point do BA ước lượng ban đầu; đội phát triển cần ước lượng lại trong buổi refinement (ví dụ planning poker).
- Sản phẩm tự phát triển (OQ-02 chưa chốt chính thức).

## 8. Câu hỏi mở

| Mã | Câu hỏi | Ảnh hưởng |
| --- | --- | --- |
| BQ-01 | Chấp nhận M1 kéo dài thành 12 tuần (S0–S5) không? | Lịch demo Alpha |
| BQ-02 | Thêm S10 cho UAT/sửa lỗi, hay chuyển US-15 (Could) sang phiên bản sau? | Ngày GA |
| BQ-03 | Zalo OA đã sẵn sàng chưa (OQ-P02)? | US-36 ở S7 |
| BQ-04 | Thời gian lưu mặc định (OQ-04) | Giá trị mặc định của US-21 |
