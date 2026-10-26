# Phase S0 – PoC và nền tảng kỹ thuật

> Sprint **S0** · Mốc **M0 – PoC & nền tảng** · 26/10/2026 – 06/11/2026 (2 tuần) · Kế hoạch **29 / 30 điểm**
> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · [Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)

## 1. Mục tiêu

Dựng khung dự án, môi trường Docker Compose, PoC MediaMTX, nền tảng Core.

## 2. Phạm vi

| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |
| --- | --- | --- | --- | --- | --- | --- |
| TS-01 | Khung dự án và CI/CD | Enabler | EP-00 | Must | 5 | — |
| TS-02 | Môi trường Docker Compose dev/staging | Enabler | EP-00 | Must | 5 | TS-01 |
| TS-03 | PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã | Enabler | EP-00 | Must | 8 | TS-02 |
| TS-04 | Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký | Enabler | EP-00 | Must | 8 | TS-01 |
| TS-07 | Giám sát và log tập trung | Enabler | EP-00 | Must | 3 | TS-02 |
| | **Tổng** | | | | **29** | |

## 3. Lịch thực hiện

| Tuần | Nội dung |
| --- | --- |
| Tuần 1 | TS-01, TS-02 (ngày 1–4); bắt đầu TS-04 |
| Tuần 2 | TS-03 (PoC đo trên môi trường TS-02), TS-04, TS-07; viết báo cáo PoC |

## 4. Task chi tiết

Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.

### TS-01 Khung dự án và CI/CD (5 điểm)

*Là Đội phát triển, tôi muốn có repo, build, lint, test và kiểm tra kiến trúc tự động cho Core, Gateway, Worker, Web, để mọi story sau dùng chung một quy trình chất lượng.*

Truy vết: yêu cầu — · use case — · màn hình — · SADD: 3.8 Structure; 3.4 Dependency

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-01.1 | OPS | Tạo monorepo theo SADD 3.8, README, .editorconfig, .gitignore | `/ (gốc repo)` | 3 |
| TS-01.2 | BE | Gradle multi-module, Spring Boot 3 + Java 21 skeleton, 11 package module rỗng | `core/build.gradle.kts, core/src/main/java/vn/camhub/*` | 8 |
| TS-01.3 | BE | Rule kiến trúc: cấm phụ thuộc vòng, chỉ gọi qua package api | `core/src/test/java/vn/camhub/ArchitectureTest.java` | 4 |
| TS-01.4 | PY | pyproject, ruff, mypy, pytest cho device-gateway, media-worker, site-connector | `device-gateway/pyproject.toml, media-worker/pyproject.toml, site-connector/pyproject.toml` | 4 |
| TS-01.5 | FE | Vite + Vue 3 + TypeScript + PrimeVue + Pinia + Vue Router + vue-i18n + Vitest + ESLint | `web/package.json, web/vite.config.ts, web/src/main.ts` | 6 |
| TS-01.6 | FE | Port design token và layout từ design/assets/styles.css sang theme PrimeVue | `web/src/styles/tokens.css, web/src/layouts/AppLayout.vue` | 6 |
| TS-01.7 | OPS | Pipeline CI: build, test, lint, kiểm tra kiến trúc, OWASP Dependency-Check, pip-audit, npm audit | `.github/workflows/ci.yml` | 8 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Pipeline chạy khi có pull request
- [ ] Phát hiện phụ thuộc vòng giữa module

### TS-02 Môi trường Docker Compose dev/staging (5 điểm)

*Là Đội phát triển, tôi muốn dựng toàn bộ hệ thống bằng một lệnh, để mọi người phát triển và kiểm thử trên cùng cấu hình.*

Truy vết: yêu cầu — · use case — · màn hình — · SADD: 3.2 Composition; 3.12.2 Triển khai

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-02.1 | OPS | docker-compose cho 10 dịch vụ, network, volume, Docker secret | `deploy/docker-compose.yml, deploy/.env.example` | 8 |
| TS-02.2 | OPS | Nginx: TLS tự ký cho dev, định tuyến /api, /ws, /media, SPA fallback | `deploy/nginx/nginx.conf` | 4 |
| TS-02.3 | OPS | Cấu hình MediaMTX cơ bản, PostgreSQL, Redis, RabbitMQ, MinIO | `deploy/mediamtx/mediamtx.yml, deploy/rabbitmq/definitions.json` | 4 |
| TS-02.4 | OPS | Healthcheck mọi container, script up/down/reset | `deploy/scripts/dev.sh` | 3 |
| TS-02.5 | QA | Smoke test sau khi dựng môi trường | `tests/smoke/smoke.sh` | 2 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Khởi động môi trường

### TS-03 PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã (8 điểm)

*Là Solution Architect, tôi muốn kiểm chứng MediaMTX với camera thật và luồng giả lập, để xác nhận ADR-03 và giảm rủi ro AR-01 trước khi làm tính năng video.*

Truy vết: yêu cầu BR-08, BR-14 · use case UC-20, UC-31 · màn hình — · SADD: ADR-03, AR-01, 3.9.2

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-03.1 | PY | Bộ giả lập RTSP (FFmpeg phát file mẫu thành N luồng) | `tools/rtsp-sim/` | 6 |
| TS-03.2 | BE | Endpoint /internal/media/authorize (bản PoC: kiểm tra token HMAC) | `core/src/main/java/vn/camhub/liveview/web/MediaAuthController.java` | 4 |
| TS-03.3 | FE | Player WHEP + hls.js dự phòng, đo first frame | `web/src/player/WhepPlayer.ts, web/src/player/HlsFallback.ts` | 8 |
| TS-03.4 | OPS | Bật ghi fMP4 60 giây, hook runOnRecordSegmentComplete gọi Core | `deploy/mediamtx/mediamtx.yml` | 4 |
| TS-03.5 | PY | Thử chuyển mã H.265 → H.264 theo yêu cầu, đo CPU mỗi phiên | `tools/transcode-poc/` | 4 |
| TS-03.6 | QA | Đo first frame, độ trễ, CPU/RAM với 50 camera thật + 450 luồng giả lập; viết báo cáo | `docs/poc/mediamtx-report.md` | 10 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Đạt ngưỡng hiệu năng
- [ ] Xác thực từng phiên xem

### TS-04 Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký (8 điểm)

*Là Đội phát triển, tôi muốn có module platform dùng chung, để mọi module nghiệp vụ tự động đáp ứng INV-01, INV-17 và ghi nhật ký.*

Truy vết: yêu cầu BR-30; ràng buộc SaaS · use case — · màn hình — · SADD: 3.5 Information; 3.6 Patterns

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-04.1 | BE | OrganizationContext, filter đặt app.org_id cho mỗi giao dịch | `core/src/main/java/vn/camhub/platform/tenancy/OrganizationContext.java, TenantFilter.java` | 6 |
| TS-04.2 | BE | Migration nền: bảng organization, outbox_event, chính sách RLS mẫu | `core/src/main/resources/db/migration/V0_1__baseline.sql` | 6 |
| TS-04.3 | BE | Transactional outbox + publisher sang RabbitMQ (retry, dead-letter) | `core/src/main/java/vn/camhub/platform/outbox/OutboxEvent.java, OutboxPublisher.java` | 8 |
| TS-04.4 | BE | CryptoService AES-256-GCM, khóa chính từ Docker secret, khóa theo Tổ chức | `core/src/main/java/vn/camhub/platform/crypto/CryptoService.java` | 6 |
| TS-04.5 | BE | AuditableEvent và listener chung (module audit dùng ở S2) | `core/src/main/java/vn/camhub/platform/audit/AuditableEvent.java` | 2 |
| TS-04.6 | QA | Test cách ly Tổ chức (truy vấn quên lọc vẫn bị RLS chặn), test outbox khi broker dừng | `core/src/test/java/vn/camhub/platform/TenancyIT.java, OutboxIT.java` | 5 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Cách ly dữ liệu theo Tổ chức
- [ ] Không mất sự kiện khi broker lỗi

### TS-07 Giám sát và log tập trung (3 điểm)

*Là Đội vận hành IT, tôi muốn có Prometheus, Grafana, Loki với dashboard cơ bản, để phát hiện sự cố và đo NFR từ sớm.*

Truy vết: yêu cầu NFR-04 · use case UC-60 · màn hình — · SADD: 3.2; 6.1

| Task | Vai trò | Công việc | File / thư mục | Giờ |
| --- | --- | --- | --- | --- |
| TS-07.1 | OPS | Prometheus scrape Core, MediaMTX, RabbitMQ, PostgreSQL; Grafana dashboard | `deploy/prometheus/prometheus.yml, deploy/grafana/dashboards/` | 6 |
| TS-07.2 | BE | Micrometer, log JSON có correlation id | `core/src/main/resources/logback-spring.xml` | 3 |
| TS-07.3 | OPS | Loki thu log container; rule cảnh báo container dừng | `deploy/loki/, deploy/prometheus/alerts.yml` | 3 |

Tiêu chí chấp nhận (kịch bản Gherkin):
- [ ] Cảnh báo vận hành

## 5. File sẽ tạo hoặc sửa

**Core Backend (Java, Spring Boot)**

- `core/build.gradle.kts`
- `core/src/main/java/vn/camhub/*`
- `core/src/main/java/vn/camhub/liveview/web/MediaAuthController.java`
- `core/src/main/java/vn/camhub/platform/audit/AuditableEvent.java`
- `core/src/main/java/vn/camhub/platform/crypto/CryptoService.java`
- `core/src/main/java/vn/camhub/platform/outbox/OutboxEvent.java`
- `core/src/main/java/vn/camhub/platform/tenancy/OrganizationContext.java`
- `core/src/main/resources/db/migration/V0_1__baseline.sql`
- `core/src/main/resources/logback-spring.xml`
- `core/src/test/java/vn/camhub/ArchitectureTest.java`
- `core/src/test/java/vn/camhub/platform/TenancyIT.java`

**Device Gateway (Python)**

- `device-gateway/pyproject.toml`

**Media Worker (Python + FFmpeg)**

- `media-worker/pyproject.toml`

**Site Connector (Python)**

- `site-connector/pyproject.toml`

**Web App (Vue 3)**

- `web/package.json`
- `web/src/layouts/AppLayout.vue`
- `web/src/main.ts`
- `web/src/player/HlsFallback.ts`
- `web/src/player/WhepPlayer.ts`
- `web/src/styles/tokens.css`
- `web/vite.config.ts`

**Triển khai (Docker Compose, cấu hình)**

- `deploy/.env.example`
- `deploy/docker-compose.yml`
- `deploy/grafana/dashboards/`
- `deploy/loki/`
- `deploy/mediamtx/mediamtx.yml`
- `deploy/nginx/nginx.conf`
- `deploy/prometheus/alerts.yml`
- `deploy/prometheus/prometheus.yml`
- `deploy/rabbitmq/definitions.json`
- `deploy/scripts/dev.sh`

**CI/CD**

- `.github/workflows/ci.yml`

**Kiểm thử**

- `tests/smoke/smoke.sh`

**Công cụ**

- `tools/rtsp-sim/`
- `tools/transcode-poc/`

**Tài liệu**

- `docs/poc/mediamtx-report.md`

**Khác**

- `/ (gốc repo)`
- `OutboxIT.java`
- `OutboxPublisher.java`
- `TenantFilter.java`

## 6. Giờ công theo vai trò

| Vai trò | Giờ |
| --- | --- |
| BE – Backend Java (2 người) | 47 |
| PY – Python – Device Gateway, Media Worker, Site Connector (1 người) | 14 |
| FE – Frontend Vue (2 người) | 20 |
| QA – Kiểm thử (1 người) | 17 |
| OPS – DevOps (bán thời gian, kiêm PY hoặc BE) | 43 |
| **Tổng** | **141** |

## 7. Điều kiện vào

- [ ] Đã chốt công nghệ (SADD frontmatter)
- [ ] Có 50 camera thật của ít nhất 3 hãng để PoC (D-04)
- [ ] Có máy chủ dev/staging (D-01)

## 8. Điều kiện ra

- [ ] CI xanh trên main cho cả 4 thành phần
- [ ] docker compose up chạy được toàn bộ dịch vụ
- [ ] Báo cáo PoC MediaMTX kết luận đạt/không đạt NFR-01; nếu không đạt, họp quyết định lại ADR-03 trước S1
- [ ] RLS và outbox có test tích hợp chạy trong CI
- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)

## 9. Demo cuối phase

1. Chạy docker compose up trên máy mới
2. Mở player PoC, phát 20 luồng WHEP và hiển thị số đo first frame
3. Trình bày báo cáo PoC

## 10. Rủi ro

- PoC MediaMTX không đạt hiệu năng (AR-01): phương án dự phòng là thử go2rtc hoặc tách nhiều media node
- Chưa có đủ camera thật: dùng giả lập RTSP, nhưng chưa kiểm chứng được ONVIF

## 11. Phase tiếp theo

[Phase S1 – Đăng nhập, cây địa điểm, phân quyền](phase-01-S1-dang-nhap-phan-quyen.md)
