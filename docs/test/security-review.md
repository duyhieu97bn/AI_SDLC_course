# Rà soát bảo mật trước GA (TS-08.3, TS-08.4)

> Ngày: 26/10/2026 · Phạm vi: stack dev `deploy/docker-compose.yml` (profile sim), mã nguồn tại thời điểm rà soát
> · Người thực hiện: đội phát triển (tự rà soát, **không thay thế** kiểm thử xâm nhập độc lập theo OWASP ASVS L2 mà
> TS-08 yêu cầu).

## 1. Kết luận

| Mức | Phát hiện | Đã sửa | Còn mở |
| --- | --- | --- | --- |
| Cao | 1 (thư viện Java có lỗ hổng đã công bố) | 1 | 0 |
| Trung bình | 1 (giả mạo IP trong nhật ký kiểm toán) | 1 | 0 |
| Thấp | 4 (thiếu CSP/Permissions-Policy; header không áp cho `/assets/`; lộ source map; lọt CSV injection) | 4 | 0 |
| Chấp nhận rủi ro | 1 (spring-webmvc 6.2.19: 2 cảnh báo không có bản vá nguồn mở, tính năng không dùng) | – | 1 |

Không còn lỗi mức nghiêm trọng/cao đã biết trong phạm vi tự rà soát. Điều kiện ra của S9 vẫn cần báo cáo kiểm thử
xâm nhập độc lập (mục 5).

## 2. Phát hiện và cách sửa

| # | Mức | Phát hiện | Bằng chứng | Sửa |
| --- | --- | --- | --- | --- |
| S-01 | Cao | **Thư viện Java lỗi thời:** Spring Boot 3.4.4 (03/2025) kéo theo 28/144 thư viện có cảnh báo trên OSV, gồm Tomcat 10.1.39, Spring Security 6.4.4, Netty 4.1.119, Jackson 2.18.3 | Quét `runtimeClasspath` qua API OSV (`api.osv.dev/v1/querybatch`) | Nâng Spring Boot lên 3.5.16 (cùng dòng 3.x, Java 21, Jackson 2) và ghim bản vá cùng nhánh trong `core/build.gradle.kts`: Tomcat 10.1.61, Netty 4.1.139, Jackson 2.21.8, amqp-client 5.37.0 (bỏ phụ thuộc Netty 4.2 tùy chọn), commons-lang3 3.21.0, log4j-api 2.26.1. Còn 1 thư viện có cảnh báo (S-07) |
| S-02 | Trung bình | **Giả mạo IP trong nhật ký kiểm toán:** Nginx *nối thêm* `X-Forwarded-For`, Core lấy giá trị đầu tiên, nên client tự đặt header là ghi được IP tùy ý vào `audit_log` (ASVS V7.1) | `curl -H 'X-Forwarded-For: 6.6.6.6'` đăng nhập sai → `LOGIN_FAILED ip=6.6.6.6` | `deploy/nginx/nginx.conf`: *ghi đè* `X-Forwarded-For $remote_addr` ở `/api/`, `/ws`, `/tunnel/`, `/talk/ws/`. Kiểm lại: nhật ký ghi IP thật. Có cân bằng tải phía trước thì dùng `real_ip_header` + `set_real_ip_from` |
| S-03 | Thấp | **Thiếu Content-Security-Policy và Permissions-Policy** (ASVS V14.4.3) | `curl -I` không có hai header | Thêm CSP `script-src 'self'` (bản build Vite không có script inline), `worker-src`/`media-src` `blob:` cho hls.js, `frame-ancestors 'none'`, `object-src 'none'`; Permissions-Policy chỉ cho `microphone=(self)` (đàm thoại US-15). Toàn bộ e2e chạy lại: không có vi phạm CSP, WebRTC/HLS/QR 2FA hoạt động |
| S-04 | Thấp | **Header bảo mật không áp cho `/assets/`**: location có `add_header` riêng thì Nginx bỏ các `add_header` cấp server, nên JS/CSS không có HSTS, `nosniff` | `curl -I /assets/index-*.js` | Lặp lại HSTS, `nosniff`, CSP trong `location /assets/` (có ghi chú cảnh báo trong cấu hình) |
| S-05 | Thấp | **Lộ source map** `*.js.map` (mã nguồn giao diện) | `GET /assets/*.js.map` → 200 | `location ~ \.map$ { return 404; }` |
| S-06 | Thấp | **Lọt chặn CSV injection** khi xuất nhật ký: kiểm tra `matches("^[=+\-@].*")` không khớp giá trị nhiều dòng (`.` không qua xuống dòng), bỏ sót tab/CR | `"=HYPERLINK(...)\nX"` không được thêm `'` | Xét ký tự đầu trong tập `= + - @ \t \r`; unit test `AuditCsvTest` |
| S-07 | Chấp nhận | spring-webmvc 6.2.19: GHSA-j9f9-w8pj-32f8 (Server-Sent Events), GHSA-pc63-qcmh-9cmg (`XsltView`) – chỉ sửa ở 7.0.9, nhánh 6.2 không có bản nguồn mở | Quét OSV | CamHub không dùng SSE (realtime qua STOMP/WebSocket) và không dùng `XsltView`. Theo dõi khi lên Spring Boot 4 |

Thư viện của các thành phần khác (quét ngày 26/10/2026):

| Thành phần | Công cụ | Kết quả |
| --- | --- | --- |
| Web (Vue) | `npm audit --omit=dev` | 0 lỗ hổng |
| Device Gateway, Media Worker, Site Connector | `pip-audit` trên `pip freeze` của image (33, 38, 14 gói) | 0 lỗ hổng |
| Core (Java) | OSV, 144 thư viện runtime | 28 → 1 (S-07) sau khi sửa S-01 |

## 3. Đã kiểm, không phát hiện vấn đề

- **Phân đoạn mạng:** Nginx chỉ mở 8443 (HTTPS) và 8189 (WebRTC). `/internal/` và `/actuator/` trả 404 ở Nginx. REST `/v1` của Device Gateway chỉ có trong mạng backend, có `X-Internal-Token`. Cổng quản trị (Grafana, RabbitMQ, MinIO console, Mailpit) chỉ nghe `127.0.0.1`.
- **Phiên và xác thực:**
  - Cookie làm mới: `HttpOnly; Secure; SameSite=Strict`, giới hạn đường dẫn.
  - Access token 15 phút.
  - Khóa tài khoản sau nhiều lần sai; 2FA TOTP bắt buộc được theo vai trò.
  - Nginx giới hạn 30 yêu cầu/giây mỗi IP cho `/api/`.
- **Phân quyền:**
  - Mọi API kiểm tra quyền theo phạm vi (US-32).
  - Dữ liệu tách theo Tổ chức bằng PostgreSQL RLS.
  - ArchitectureTest chặn module gọi chéo ngoài gói `api`.
- **Bí mật lưu trữ:**
  - Mật khẩu camera/đầu ghi, LDAP, TOTP và kênh thông báo dùng AES-256-GCM theo khóa từng Tổ chức, bọc bằng `camhub_master_key`. AAD là mã Tổ chức (INV-17, NFR-05).
  - Diễn tập khôi phục lần 2 xác nhận mọi bí mật giải mã được sau khôi phục.
- **Luồng video:** token HMAC 60 giây gắn với path và hành động. MediaMTX hỏi Core cho mọi phiên đọc/phát lại/publish.
- **Đàm thoại (mới, US-15):**
  - WebSocket `/talk/ws/{mã}` là điểm duy nhất của Gateway ra ngoài Nginx.
  - Mã phiên ngẫu nhiên 192 bit, do Core cấp sau khi kiểm `TALK_AUDIO`. Mã dùng một lần, hết hạn sau 30 giây nếu chưa nối.
  - Phiên dài tối đa 5 phút, khung tối đa 64 KB, mỗi camera một người nói.
  - Mã phiên chỉ ghi 6 ký tự đầu vào log.
- **Nhập tệp (US-07):** XML trong XLSX đọc bằng DOM với `disallow-doctype-decl` và `FEATURE_SECURE_PROCESSING` (chặn XXE). Tệp tối đa 5 MB, giải nén tối đa 40 MB, tối đa 2.000 dòng (chặn zip bomb). Mật khẩu không gửi lại trình duyệt khi xem trước.
- **SQL:** truy vấn dùng tham số (`JdbcClient`). Không có ghép chuỗi từ dữ liệu người dùng.

## 4. Việc trước Go-live (không sửa được ở dev)

| # | Việc | Phụ trách |
| --- | --- | --- |
| G-01 | Chứng chỉ TLS thật (dev dùng tự ký) | Đội vận hành IT |
| G-02 | Kho sao lưu off-site `repo2` có mã hóa (restore.md mục 3) | Đội vận hành IT |
| G-03 | Giới hạn 30 yêu cầu/giây theo IP: nhiều người sau cùng một NAT văn phòng dùng chung hạn mức. Đo tải 100 Operator trong 1 giờ (27/10/2026): trung bình 12,4 yêu cầu/giây cho cả 100 người, còn dư nếu chỉ một NAT; theo dõi 429/503 khi pilot | Đội vận hành IT |
| G-04 | Đưa quét thư viện (OSV/`npm audit`/`pip-audit`) vào CI để không tụt bản vá như S-01 | DevOps |

## 5. Hỗ trợ kiểm thử xâm nhập độc lập (TS-08.3)

Chuẩn bị cho đơn vị kiểm thử:

- **Môi trường:** staging dựng từ `deploy/docker-compose.yml` cùng phiên bản đã rà soát; dữ liệu mẫu (profile `demo`).
- **Tài khoản:** một tài khoản cho mỗi vai trò hệ thống (Admin, Site Manager, Technician, Operator, Viewer) và một vai trò tùy chỉnh. Thêm hai Tổ chức để thử vượt quyền giữa Tổ chức (RLS).
- **Phạm vi đề nghị:**
  - OWASP ASVS L2 cho web/API.
  - Bắt tay WebRTC/WHEP và token luồng.
  - WebSocket STOMP và WebSocket đàm thoại.
  - Đường hầm Site Connector (chisel qua `/tunnel/`).
  - Nhập tệp CSV/XLSX.
- **Ngoài phạm vi:** firmware camera; hạ tầng mạng của khách hàng.
- **Đầu mối:** đội phát triển nhận báo cáo, sửa theo mức độ (nghiêm trọng/cao trước Go-live).
