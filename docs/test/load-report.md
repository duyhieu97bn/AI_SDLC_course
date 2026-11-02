# Báo cáo kiểm thử tải (TS-08.2, PERF-01)

| Mục | Nội dung |
| --- | --- |
| Ngày chạy | 27/10/2026, 14:28–15:31 UTC (21:28–22:31 giờ Việt Nam) |
| Kế hoạch | [master-test-plan.md](master-test-plan.md) mục 6.1.6, PERF-01 |
| Môi trường | Laptop dev Intel i7-1185G7 (4 lõi / 8 luồng), 16 GB RAM, Windows 11 + Docker Desktop (WSL2). Toàn bộ stack profile `sim` và máy sinh tải chạy trên **cùng một máy** |
| Bản build | Core trên Spring Boot 3.5.16, mã nguồn ngày 27/10/2026 |
| Công cụ | Gatling 3.13.5 (Java DSL, [tests/load](../../tests/load/README.md)) trong container `eclipse-temurin:21-jdk` gắn mạng `camhub_backend`; `sample-stats.sh` lấy `docker stats` mỗi 64 giây |
| Kết quả | **ĐẠT** cả 3 ngưỡng; 0 lỗi trong 46.701 yêu cầu |

## 1. Kịch bản

- **Người dùng:** 100 Operator (`loadtest-001..100@camhub.local`), tăng dần trong 120 giây, giữ 60 phút.
- **Mỗi người dùng lặp lại:**
  - đăng nhập;
  - lấy danh sách camera;
  - mở 4 phiên xem trực tiếp mỗi ≈ 50 giây (như lưới 4 ô, token sống 60 giây);
  - đếm cảnh báo mở và thông báo chưa đọc (như thanh trên của giao diện);
  - thỉnh thoảng xem lại (dòng thời gian + phiên phát lại) và danh sách cảnh báo.
- **Đích gọi:** `http://core:8080`, gọi thẳng Core, **không qua Nginx**. Nginx giới hạn 30 yêu cầu/giây mỗi IP, mà mọi người dùng giả lập lại đi từ cùng một container. Giới hạn này cho người dùng thật sau NAT văn phòng được ghi riêng ở G-03 trong [security-review.md](security-review.md).

## 2. Kết quả

| Ngưỡng (assertion) | Mục tiêu | Thực tế | Kết quả |
| --- | --- | --- | --- |
| Tỷ lệ lỗi toàn cục | < 1% | 0,0% | Đạt |
| P95 toàn cục | < 500 ms | 28 ms | Đạt |
| P95 phiên xem trực tiếp | < 300 ms | 23 ms | Đạt |

Đơn vị trong bảng dưới là ms.

| Yêu cầu | Số lượng | Lỗi | TB | P50 | P95 | P99 | Max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Toàn bộ** | **46.701** | **0** | 9 | 6 | 28 | 67 | 452 |
| Đăng nhập (Argon2id) | 500 | 0 | 83 | 63 | 232 | 378 | 452 |
| Danh sách camera | 436 | 0 | 11 | 8 | 28 | 54 | 60 |
| Phiên xem trực tiếp | 28.980 | 0 | 8 | 5 | 23 | 61 | 446 |
| Số cảnh báo mở | 7.245 | 0 | 12 | 9 | 35 | 77 | 318 |
| Thông báo chưa đọc | 7.245 | 0 | 4 | 3 | 13 | 36 | 91 |
| Dòng thời gian xem lại | 1.476 | 0 | 11 | 8 | 34 | 72 | 154 |
| Danh sách cảnh báo | 738 | 0 | 18 | 14 | 55 | 130 | 164 |
| Phiên xem lại | 81 | 0 | 20 | 15 | 57 | 114 | 114 |

- Thông lượng trung bình: 12,4 yêu cầu/giây.
- 100% yêu cầu dưới 800 ms.

## 3. Tài nguyên

Số liệu lấy theo container trong suốt lượt chạy. CPU tính theo % một lõi; 100% bằng một lõi.

| Container | CPU trung bình | CPU đỉnh | RAM đầu → cuối (MiB) | RAM đỉnh (MiB) |
| --- | --- | --- | --- | --- |
| core | 10,0% | 64,2% | 536 → 594 | 594 |
| rabbitmq | 15,2% | 239,2% | 169 → 181 | 240 |
| media-worker | 5,4% | 88,6% | 93 → 99 | 112 |
| mediamtx | 4,6% | 22,9% | 23 → 59 | 59 |
| device-gateway | 4,1% | 55,8% | 53 → 56 | 67 |
| postgres | 2,8% | 13,1% | 91 → 103 | 123 |
| redis | 1,3% | 4,8% | 12 → 14 | 14 |
| nginx | 0,5% | 8,5% | 24 → 20 | 25 |

**Nhận xét:**

- Core dùng trung bình 10% một lõi cho 100 người dùng. RAM tăng 58 MiB trong 1 giờ rồi ổn định ở mức heap đã cấp phát; chưa thấy dấu hiệu rò rỉ. Cần lượt chạy 24 giờ (PERF-05) để kết luận.
- Đỉnh CPU của RabbitMQ (239%) và media-worker trùng với các lượt quét định kỳ (outbox, tạo ảnh chụp cho cảnh báo giả lập). Chúng không ảnh hưởng thời gian phản hồi API.
- Đăng nhập chậm nhất (P95 232 ms) vì băm Argon2id. Đây là thiết kế cố ý.

## 4. Lần chạy đầu bị gián đoạn

- **Diễn biến:** lần chạy đầu cùng ngày dừng ở phút 13 vì **Docker Desktop sập** trên máy dev. Gatling báo `UnknownHostException: core` và `Connection refused` cho 239 yêu cầu sau thời điểm sập. Trước đó có 9.673 yêu cầu, tất cả đạt.
- **Nguyên nhân:** do máy chủ (Docker Desktop), không phải lỗi ứng dụng.
- **Xử lý:** khởi động lại Docker và chạy lại toàn bộ (mục 2). Log lần đầu: `tests/load/build/gatling-100u-run1-docker-crash.log`.

## 5. Đánh giá theo NFR

| NFR | Kết luận từ lượt chạy này | Còn thiếu |
| --- | --- | --- |
| NFR-03 (100 người dùng đồng thời) | **Đạt phần API** với 100 người dùng trong 1 giờ, dư nhiều tài nguyên | 500 camera **ghi đồng thời** (PERF-04) cần staging và camera thật (D-01, D-04) |
| NFR-01 (hình trực tiếp ≤ 3 giây) | Phần API (cấp phiên/token) chỉ tốn P95 23 ms trong ngân sách 3 giây | First frame trên trình duyệt (PERF-02) đo ở PoC 23/10: P95 0,5–3,0 s. Đo lại trên staging |
| NFR-02 (xem lại ≤ 3 giây) | Phần API (dòng thời gian + phiên phát lại) P95 ≤ 57 ms | e2e s4 đo trên trình duyệt: đạt |
| NFR-04 (sẵn sàng) | 0 lỗi trong 1 giờ | Đo hằng tháng ở pilot |

**Giới hạn:**

- Máy sinh tải và hệ thống chạy chung một laptop. Kết quả là mức thận trọng cho Core và API.
- Lượt chạy này không nói gì về năng lực media: số luồng ghi và chuyển mã chỉ đo được khi có node media riêng.

## 6. Chạy lại

```bash
./tests/load/run.sh -Dusers=100 -Dminutes=60 -DrampSeconds=120 > tests/load/build/gatling.log
bash tests/load/sample-stats.sh 64 > tests/load/build/stats.csv &   # lấy tài nguyên song song
```

Báo cáo HTML của Gatling: `tests/load/build/reports/gatling/apiloadsimulation-20261027142839597/index.html`.
