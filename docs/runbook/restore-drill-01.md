# Biên bản diễn tập khôi phục lần 1 (US-40.5)

| Mục | Nội dung |
| --- | --- |
| Ngày | 24/10/2026, 10:25–10:30 (giờ Việt Nam) |
| Môi trường | Dev, Docker Desktop trên Windows 11, stack `deploy/scripts/dev.sh up` (profile sim) đang chạy |
| Người thực hiện | Đội vận hành IT (UCQ-03) |
| Quy trình | [restore.md](restore.md) mục 6.3–6.4 và mục 7; script [deploy/backup/restore-drill.sh](../../deploy/backup/restore-drill.sh) |
| Phiên bản | PostgreSQL 16.15, pgBackRest 2.58.0, MinIO client 2025-07-21 |
| Kết quả | **ĐẠT** – RTO và RPO trong mục tiêu |

Nguyên tắc: không đụng CSDL đang chạy (volume `pgdata`) và MinIO đang chạy. Mọi lần khôi phục đều vào container/volume tạm, kho sao lưu gắn **read-only** (phần A) hoặc là **bản chép** của kho (phần B), nên bản khôi phục không thể đẩy WAL timeline mới vào kho thật. Sau diễn tập đã kiểm tra: kho thật chỉ có timeline 1, không có tệp `00000002.history`.

## Dữ liệu tại thời điểm diễn tập

| Hạng mục | Giá trị |
| --- | --- |
| Kích thước CSDL `camhub` | 11 MB (cả cụm 33,1 MB) |
| Số bảng schema `public` | 53 (gồm bảng cha và các phân vùng `audit_log_*`, `recording_segment_*`) |
| Kho pgBackRest | 8,2 MB: 1 bản đầy đủ `20261024-102239F` (3,8 MB sau nén zstd) + 1 bản differential (373 B) + WAL |
| Bản sao MinIO | 1 đối tượng thử `camhub-snapshots/_backup-drill/probe.txt` (đã xóa sau diễn tập) |

## Phần A – PITR tới một thời điểm, đối chiếu từng bảng (`restore-drill.sh`)

Các bước script tự động làm:

1. Mở giao dịch, khóa `SHARE` toàn bộ 53 bảng (chặn ghi vài mili giây), đếm dòng và tính md5 nội dung từng bảng, lấy mốc `T = clock_timestamp()`. Vì không giao dịch nào commit được khi đang khóa, trạng thái tại T đúng bằng ảnh chụp.
2. Tạo một commit sau T rồi `pgbackrest check` để đoạn WAL chứa T vào kho.
3. `pgbackrest restore --type=time --target=T --target-action=promote` vào volume tạm, khởi động PostgreSQL tạm (`--network none`, `archive_mode=off`), chờ `pg_is_in_recovery() = f`.
4. So sánh số dòng + md5 của 53 bảng.
5. Dựng MinIO tạm, chép ngược bản sao `miniobackup` vào, đếm đối tượng.

Kết quả (`bash backup/restore-drill.sh`, tổng thời gian chạy 36 giây):

| Bước | Thời gian |
| --- | --- |
| `pgbackrest restore` (bản đầy đủ + differential) | 4,7 s |
| Replay WAL tới T và promote | 7,5 s |
| Đối chiếu dữ liệu 53 bảng | 3,0 s |
| **Tổng PostgreSQL** | **15,2 s** |
| Khôi phục MinIO (khởi động MinIO tạm + chép ngược) | 5,0 s |

```text
Mốc PITR (T)   : 2026-10-24 03:25:20.859856+00
Bản nền        : 20261024-102239F_20261024-102318D (diff)
PostgreSQL     : ĐẠT – 53/53 bảng khớp số dòng và md5 nội dung
MinIO          : ĐẠT – 1/1 đối tượng
recovery stopping before commit of transaction 908, time 2026-10-24 03:25:21.890249+00
redo done at 0/9001958
selected new timeline ID: 2
```

Recovery dừng đúng trước commit đầu tiên sau T (giao dịch 908 do script tạo ở bước 2) – PITR theo thời gian hoạt động chính xác.

## Phần B – Khôi phục kiểu "máy chủ mới" bằng đúng lệnh trong runbook

Mô phỏng máy chủ mới bằng một Compose project riêng (`-p camhubdrill`, volume rỗng), làm theo mục 6.3–6.4:

| Bước (runbook) | Lệnh | Thời gian |
| --- | --- | --- |
| 6.3 Xuất kho thành tệp nén (máy cũ) | `tar czf pgbackrest.tgz` từ volume `camhub_pgbackrest` → 7,5 MB | 8,1 s |
| 6.3 Nạp kho vào volume máy mới | `tar xzf` + `chown 70:70` | 2,6 s |
| 6.4 Restore tới điểm mới nhất | `docker compose run … pgbackrest --stanza=camhub restore` | 6,7 s |
| 6.4 Khởi động, replay WAL, promote | `docker compose up -d postgres` | 3,5 s |
| 6.2 Build image `camhub/postgres` không dùng cache | `docker build --no-cache` | 12 s |

Đối chiếu nhanh sau khôi phục (cùng thời điểm, CSDL thật / bản khôi phục): `camera_stream` 45/45, `audit_log` 50/50, `user_session` 32/32. Log: `consistent recovery state reached`, `redo done at 0/9001A20`, `selected new timeline ID: 2`, `archive recovery complete`. Project `camhubdrill` đã được xóa toàn bộ sau diễn tập.

## Đánh giá RTO / RPO

**RTO ≤ 4 giờ – ĐẠT.** Phần kỹ thuật (lấy kho, restore, replay, MinIO) đo được dưới 1 phút với CSDL 11 MB. Với CSDL production ước tính 20 GB, tốc độ restore của pgBackRest (zstd, `process-max=2`) trên ổ SSD thường ở mức vài trăm MB/s → khoảng 5–15 phút, cộng replay tối đa 24 giờ WAL; ước tính đầy đủ theo [restore.md](restore.md) mục 6 là ≈ 3 giờ, phần lớn là thao tác con người (quyết định, chuẩn bị máy, lấy bí mật offline, kiểm tra). Diễn tập lần 2 (TS-08.3) cần đo lại với dữ liệu kích thước thật.

**RPO ≤ 1 giờ – ĐẠT.** Dữ liệu có thể mất khi mất máy chủ = phần WAL chưa kịp đẩy vào kho:

- WAL được đẩy ngay khi mỗi đoạn 16 MB đầy, và `archive_timeout=300` buộc đóng đoạn sau tối đa 5 phút khi ít ghi → RPO PostgreSQL ≤ 5 phút (cộng độ trễ đồng bộ sang kho off-site ở production).
- Quan sát trong diễn tập: `pg_stat_archiver.failed_count = 0`; ở phần B, bản khôi phục lúc 03:29 chứa dữ liệu tới đoạn WAL đẩy lúc 03:25:22 – khoảng hở ~4 phút, đúng với `archive_timeout`.
- MinIO sao chép hằng giờ → RPO tệp xuất clip/ảnh chụp ≤ 1 giờ.

## Phát hiện và việc cần làm

| # | Phát hiện | Việc cần làm | Phụ trách |
| --- | --- | --- | --- |
| 1 | Kho `repo1` và bản sao MinIO ở dev nằm cùng máy Docker → không chống được mất máy chủ | Production: thêm `repo2` S3 off-site có mã hóa và đích `mc mirror` off-site trước Go-live (restore.md mục 3) | Đội vận hành IT |
| 2 | Diễn tập chưa khởi động Core trên CSDL khôi phục để giải mã mật khẩu camera bằng `camhub_master_key` | Diễn tập lần 2: khôi phục toàn bộ stack trên máy riêng, kiểm tra kết nối camera | QA + Đội vận hành IT, S9 |
| 3 | Siêu dữ liệu `recording_segment` còn sau khôi phục nhưng tệp video không được sao lưu | Ghi nhận là giới hạn đã chấp nhận (restore.md mục 1); cân nhắc job đối soát tệp thiếu | Core |
| 4 | Ban đầu `flock -w` không có trong BusyBox, job lỗi khóa | Đã sửa trong `camhub-backup.sh` (chờ khóa bằng vòng lặp `flock -n`) | Đã xong |
| 5 | CSDL dev quá nhỏ để đo thông lượng | Diễn tập lần 2 với dữ liệu sinh tải ≥ 10 GB | QA, S9 |

## Chạy lại

```bash
cd deploy
bash backup/restore-drill.sh          # in kết quả và thời gian, tự dọn container/volume tạm
KEEP=1 bash backup/restore-drill.sh   # giữ lại camhub-drill-pg để kiểm tra thêm
```
