# Trạng thái Phase S0 – PoC và nền tảng

> Cập nhật: 23/10/2026 · Kế hoạch: [phase-00-S0-poc-nen-tang.md](phase-00-S0-poc-nen-tang.md)

## Item

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| TS-01 Khung dự án và CI/CD | Xong (CI chưa chạy trên GitHub) | `core/`, `device-gateway/`, `media-worker/`, `site-connector/`, `web/`, `.github/workflows/ci.yml`; `ArchitectureTest` có test chứng minh rule bắt được phụ thuộc vòng |
| TS-02 Docker Compose dev/staging | Xong | `deploy/`; `./scripts/dev.sh up` dựng 14 container healthy; `tests/smoke/smoke.sh` đạt 12/12 |
| TS-03 PoC MediaMTX | Xong phần đo trên dev; **chưa đo staging + camera thật** | [docs/poc/mediamtx-report.md](../../docs/poc/mediamtx-report.md) |
| TS-04 Nền tảng Core | Xong | `TenancyIT` (5 test, PostgreSQL thật, RLS) và `OutboxIT` (3 test, RabbitMQ thật, broker lỗi) đạt; `CryptoServiceTest` 8 test |
| TS-07 Giám sát và log tập trung | Xong | 7 target Prometheus `up`; dừng `device-gateway` → `ServiceDown` firing sau 85 s; Loki nhận log 15 dịch vụ; dashboard “CamHub – Tổng quan vận hành” |

## Điều kiện ra

- [ ] CI xanh trên main cho cả 4 thành phần: các bước CI đã chạy được trên máy dev, nhưng repo chưa được push lên GitHub nên workflow chưa chạy thật.
- [x] `docker compose up` chạy được toàn bộ dịch vụ
- [ ] Báo cáo PoC kết luận NFR-01: **đạt có điều kiện** trên dev (≤ 20 luồng/trình duyệt). Cổng G0 đề xuất giữ ADR-03, đo lại trên staging trước S3.
- [x] RLS và outbox có test tích hợp (task `integrationTest`, CI job `core`)

## Thay đổi so với kế hoạch

- **MinIO:** Docker Hub không còn cung cấp image MinIO cộng đồng. Môi trường dev dùng `bitnamilegacy/minio:2025.7.23`, bản đóng băng. Cần một ADR chọn kho S3 trước GA.
- **B-frame:** WebRTC của MediaMTX không phát được H.264 có B-frame. Bổ sung tiêu chí cho US-04/US-05 (phát hiện và cảnh báo khi thêm camera) và US-27 (mẫu cấu hình đặt B-frame = 0).
- **Thêm** `tools/poc-bench/`: đo first frame tự động bằng Chrome headless trong Docker. Việc này không có trong kế hoạch, nhưng cần để đo lặp lại được.
- **Tài khoản nội bộ MediaMTX** (`camhub-internal`): FFmpeg chuyển mã dùng tài khoản này để đọc luồng gốc, bộ giả lập dùng để publish. Trình duyệt không bao giờ nhận được tài khoản này.
