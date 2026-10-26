# Báo cáo PoC MediaMTX (TS-03)

> Phase S0 · Ngày đo: 23/10/2026 · Phiên bản: 0.1 (lần đo trên máy dev)
> Liên quan: ADR-03 (chọn MediaMTX), AR-01 (rủi ro hiệu năng), NFR-01 (live ≤ 3 giây), PNFR-03 (chuyển mã H.265)

## 1. Kết luận ngắn

| Hạng mục | Kết quả | Đánh giá |
| --- | --- | --- |
| WHEP + xác thực ngoài qua Core | Mỗi phiên đọc, phát lại và publish đều gọi `/internal/media/authorize`. Thiếu token hoặc token sai đều bị từ chối. | **Đạt** |
| First frame WebRTC, ≤ 20 luồng cùng lúc trên 1 trình duyệt | P95 theo từng lượt từ 0,5 đến 3,0 s; 150/150 luồng phát được | **Đạt có điều kiện** (1/15 lượt chạm 3.031 ms, xem mục 4) |
| Ghi fMP4 đoạn 60 giây + hook về Core | 20 luồng ghi liên tục; ffprobe đọc được (H.264 + AAC, 60,3 s); 260/260 lần gọi hook thành công | **Đạt** |
| Chuyển mã H.265 → H.264 theo yêu cầu | Chạy khi có người xem, tự dừng sau 15 s; khoảng 0,6–1,1 lõi CPU mỗi phiên 720p | **Đạt**, cần giới hạn số phiên (mục 5) |
| 500 luồng ghi đồng thời, 50 camera thật của ≥ 3 hãng | **Chưa đo** – chưa có máy chủ staging (D-01) và camera thật (D-04) | **Chưa kết luận** |

**Đề xuất cho cổng G0:** giữ ADR-03 (MediaMTX) để bắt đầu S1. Kết luận cuối về NFR-01 và AR-01 cần chờ lần đo trên staging, với 50 camera thật và 450 luồng giả lập. Lần đo này phải xong trước khi bắt đầu S3 (xem trực tiếp).

## 2. Môi trường đo

| Thành phần | Giá trị |
| --- | --- |
| Máy | Laptop dev: Intel Core i7-1185G7 (4 lõi, 8 luồng), RAM 16 GB, Windows 11 + Docker Desktop (WSL2) |
| Stack | `deploy/docker-compose.yml`: toàn bộ 14 container chạy trên cùng máy, kể cả trình duyệt đo |
| MediaMTX | 1.12.3 (image `-ffmpeg`), RTSP vào qua TCP, WebRTC qua UDP 8189 |
| Nguồn video | Bộ giả lập `tools/rtsp-sim`: 720p, 25 fps, GOP 2 s, 2 Mbit/s, AAC 16 kHz, **không có B-frame** |
| Trình duyệt đo | Google Chrome headless (image Playwright 1.50.1), chạy trong mạng `backend`; dùng `tools/poc-bench` |
| Cách đo first frame | Tính từ lúc tạo `RTCPeerConnection` tới khi `requestVideoFrameCallback` báo khung hình đầu tiên. Đã gồm cả gọi API cấp token, WHEP offer/answer, ICE và giải mã. |

Vì đo trên laptop dùng chung cho mọi dịch vụ, số liệu ở đây là **cận trên** (bi quan) so với máy chủ thật.

## 3. Xác thực từng phiên xem

- MediaMTX dùng `authMethod: http` và gọi Core cho mọi hành động `read`, `playback`, `publish`.
- Token là HMAC-SHA256, hết hạn sau 60 giây, gắn với path và hành động (`MediaTokenService`). Có unit test cho các trường hợp: sai path, sai hành động, hết hạn, chữ ký giả.
- Smoke test `tests/smoke/smoke.sh` xác nhận WHEP thiếu token bị từ chối. Lưu ý: MediaMTX trả **400** cho trình duyệt (không phải 401) khi Core từ chối; log ghi `authentication failed: server replied with code 401`.
- Trong lần đo có 192 phiên đọc được cho phép. Có 40 lượt `publish` bị từ chối: đó là lần thử đầu tiên không gửi mật khẩu theo chuẩn RTSP, sau đó bộ giả lập gửi lại kèm mật khẩu và được cho phép.

## 4. First frame WebRTC

Bench có 5 mức tải, mỗi mức 3 lượt. Mỗi lượt mở N luồng **cùng lúc** trong một tab, rồi giữ 10 giây.
Dữ liệu gốc: `docs/poc/results/first-frame-webrtc-20261023-211652.jsonl`.

| Số luồng | Phát được | Trung bình (ms) | P95 từng lượt (ms) | Lớn nhất (ms) |
| ---: | ---: | ---: | --- | ---: |
| 1 | 3/3 | 640 | 728 · 689 · 504 | 728 |
| 4 | 12/12 | 1.161 | 1.072 · 2.383 · 1.175 | 2.383 |
| 9 | 27/27 | 1.784 | 2.431 · 2.605 · 2.599 | 2.605 |
| 16 | 48/48 | 1.861 | 2.836 · **3.031** · 2.072 | 3.031 |
| 20 | 60/60 | 1.737 | 2.216 · 2.057 · 1.954 | 2.240 |

**Nhận xét**

- **Điểm nghẽn là trình duyệt, không phải MediaMTX.** Trong lúc đo, Chrome dùng trung bình 148% CPU, có lúc lên tới 420%, vì phải giải mã phần mềm tới 20 luồng 720p. MediaMTX trung bình chỉ 62%, tối đa 189%, dù cùng lúc nhận 20 luồng, ghi 20 luồng và phục vụ tới 20 người xem. Core dưới 5%.
- **Xem một camera đơn lẻ** (luồng chính) có first frame khoảng 0,5–0,7 s, thấp hơn nhiều so với ngưỡng 3 s.
- **Lưới 16–20 ô mở cùng lúc** sát ngưỡng 3 s trên laptop này. Thiết kế đã có biện pháp giảm tải, cần kiểm chứng lại ở S3:
  - Lưới lớn dùng **luồng phụ** (SADD 3.9.2, US-08), có độ phân giải thấp hơn nhiều so với luồng 720p dùng trong lần đo này.
  - Máy trạm thật có giải mã phần cứng.
  - Lần đo đầu tiên ghi nhận P95 3,4 s khi Chrome mới khởi động lạnh. Không tính vào bảng, nhưng cho thấy lần mở đầu tiên chậm hơn.
- **Phát hiện quan trọng: B-frame.** MediaMTX từ chối phát WebRTC với luồng H.264 có B-frame (`WebRTC doesn't support H264 streams with B-frames`). Ở lần chạy đầu, 19/20 luồng không có hình vì file mẫu có B-frame. Hệ quả cho sản phẩm:
  - US-04/US-05 (thêm camera, kiểm tra khả năng): khi thêm camera cần phát hiện B-frame và cảnh báo người dùng, gợi ý tắt B-frame trong cấu hình camera, hoặc dùng luồng `_h264` chuyển mã hay HLS.
  - Mẫu cấu hình (US-27) nên đặt sẵn “B-frame = 0” cho các hãng hỗ trợ.

## 5. Chuyển mã H.265 → H.264 (TS-03.5)

Bộ giả lập phát 10 luồng H.265. `tools/transcode-poc/measure.sh` mở K người đọc luồng `sim-N_h264`, mỗi lượt đo 40 giây.

| Phiên chuyển mã đồng thời | CPU MediaMTX (trung bình, 100% = 1 lõi) | Ghi chú |
| ---: | ---: | --- |
| 0 (nền: nhận và ghi 10 luồng H.265) | ≈ 11% | |
| 1 | 73% | ≈ 0,6 lõi cho 1 phiên |
| 4 | 455% | ≈ 1,1 lõi mỗi phiên; laptop 4 lõi đã quá tải nên hiệu suất giảm |

Dữ liệu gốc: `docs/poc/results/transcode-k1-*.csv`, `transcode-k4-*.csv`.

- FFmpeg chỉ khởi động khi có người xem, sẵn sàng sau khoảng 2 giây, và tự dừng 15 giây sau khi người xem cuối cùng rời đi (`runOnDemandCloseAfter`).
- **Đề xuất cho PR-01:** mỗi media node cho tối đa `số lõi ÷ 2` phiên chuyển mã đồng thời (với 720p; 1080p hoặc 4 MP cần nhiều CPU hơn, phải đo lại ở staging). Core từ chối khi vượt giới hạn và chuyển sang HLS. Ưu tiên cấu hình camera phát H.264 ở luồng phụ, vì phần lớn camera hỗ trợ.

## 6. Ghi hình (TS-03.4)

- Cấu hình ghi: `recordFormat: fmp4`, đoạn 60 giây, part 1 giây. MediaMTX không tự xóa (`recordDeleteAfter: 0s`), vì Core sẽ quản lý thời hạn lưu từ S4.
- 20 luồng được ghi liên tục: mỗi đoạn khoảng 15 MB, phù hợp 2 Mbit/s. Sau khoảng 15 phút đo, dung lượng là 3,7 GB.
- Hook `runOnRecordSegmentComplete` → `deploy/mediamtx/hooks/segment-complete.sh` → `POST /internal/media/segments`: 260/260 lần gọi thành công, metric `camhub_recording_segments_total` khớp.
- Ước lượng từ số đo: 1 camera 2 Mbit/s ghi liên tục cần khoảng 21,6 GB mỗi ngày, khớp với ước lượng dung lượng của SADD 3.12.

## 7. Việc còn lại trước khi đóng AR-01

| # | Việc | Phụ trách | Hạn |
| --- | --- | --- | --- |
| 1 | Đo lại trên máy chủ staging (D-01) với 50 camera thật của ≥ 3 hãng (D-04) và 450 luồng giả lập: CPU, RAM, I/O đĩa khi ghi 500 luồng | QA + OPS | Trước S3 |
| 2 | Đo first frame lưới 9 và 16 ô bằng **luồng phụ** trên máy trạm Operator thật | QA | S3 |
| 3 | Thử camera thật có B-frame, xác nhận cảnh báo và đường chuyển mã | PY | S2 (US-04) |
| 4 | Đo chuyển mã 1080p/4 MP, chốt giới hạn PR-01 | PY | S3 |
| 5 | Chọn kho S3 thay MinIO: Docker Hub đã ngừng image MinIO cộng đồng, môi trường dev đang dùng `bitnamilegacy/minio:2025.7.23` (đóng băng). Ứng viên: SeaweedFS, Garage, MinIO tự build. | SA | Trước GA (ADR mới) |

## 8. Cách chạy lại

```bash
cd deploy && ./scripts/dev.sh up && ./scripts/dev.sh sim 20
BENCH_COUNTS=1,4,9,16,20 BENCH_ROUNDS=3 ../tools/poc-bench/run.sh     # first frame
SIM_CODEC=h265 ./scripts/dev.sh sim 10 && ../tools/transcode-poc/measure.sh 4 40
```
