# Biên bản diễn tập khôi phục lần 2 (TS-08.3)

| Mục | Nội dung |
| --- | --- |
| Ngày | 26/10/2026, 23:02–23:05 (giờ Việt Nam) |
| Môi trường | Dev, Docker Desktop trên Windows 11, stack profile `sim` đang chạy (Core trên Spring Boot 3.5.16) |
| Người thực hiện | Đội phát triển, thay Đội vận hành IT (UCQ-03) |
| Quy trình | [restore.md](restore.md) mục 6–7; script [deploy/backup/restore-drill.sh](../../deploy/backup/restore-drill.sh), có thêm bước 4b |
| Phiên bản | PostgreSQL 16, pgBackRest 2.58.0 |
| Kết quả | **ĐẠT**: dữ liệu khớp từng bảng, mọi bí mật giải mã được bằng khóa chính, RTO/RPO trong mục tiêu |

Mục tiêu lần này là đóng hai việc còn mở từ [lần 1](restore-drill-01.md):

- **#2:** bản khôi phục dùng được với `camhub_master_key`, nghĩa là mật khẩu camera, LDAP… không vô dụng.
- **#5:** đo với dữ liệu lớn hơn CSDL dev (13 MB).

Nguyên tắc vẫn như lần 1:

- Khôi phục vào container/volume tạm; kho sao lưu gắn read-only.
- Sau diễn tập, kho thật không có tệp `.history` (vẫn timeline 1).
- Container/volume tạm đã được dọn.

## Dữ liệu diễn tập

Để đo thông lượng, thêm tạm bảng `drill_ballast`: 4 triệu dòng, 1.078 MB, dạng văn bản hex khó nén như nhật ký kiểm toán. Thêm vào CSDL dev đang chạy rồi xóa sau diễn tập.

| Hạng mục | Giá trị |
| --- | --- |
| Kích thước CSDL `camhub` | 1.092 MB (13 MB dữ liệu thật + 1.078 MB dữ liệu đệm) |
| Số bảng / số dòng đối chiếu | 74 bảng (gồm phân vùng), 4.002.962 dòng |
| Sao lưu đầy đủ `20261026-230214F` | 15,2 s cho 1,1 GB → 197,6 MB sau nén zstd (tỉ lệ 5,6:1) |
| Kho pgBackRest sau diễn tập | 441 MB |
| Bí mật trong CSDL | 54 mật khẩu camera, 6 mật khẩu đầu ghi, 1 mật khẩu đường hầm, 1 tài khoản LDAP, 1 bí mật kênh thông báo, 1 TOTP, 1 khóa dữ liệu Tổ chức |

## Kết quả (`bash backup/restore-drill.sh`)

| Bước | Thời gian | Kết quả |
| --- | --- | --- |
| `pgbackrest restore` (bản đầy đủ 1,1 GB) | 11,8 s | ≈ 93 MB/s dữ liệu CSDL |
| Replay WAL tới T, promote | 4,6 s | Dừng đúng trước commit đầu tiên sau T (giao dịch 4083) |
| Đối chiếu 74 bảng (số dòng + md5) | 48,8 s | **ĐẠT** 74/74 |
| 4b. Giải mã bí mật bằng `camhub_master_key` | < 10 s | **ĐẠT** mọi loại (bảng trên), 0 lỗi |
| Khôi phục MinIO (bản sao → MinIO tạm) | 11,2 s | **ĐẠT** 18/18 đối tượng |
| **Tổng PostgreSQL** | **65,2 s** | |

```text
Mốc PITR (T)   : 2026-10-26 16:03:34.086622+00
Bản nền        : 20261026-230214F (full)
recovery stopping before commit of transaction 4083, time 2026-10-26 16:03:34.091753+00
redo done at 0/B04020E8
selected new timeline ID: 2
Bí mật         : camera 54/54, channel 1/1, connector 1/1, data_key 1/1, ldap 1/1, recorder 6/6, totp 1/1
```

### Bước 4b mới: kiểm tra khóa chính

Script đọc mọi cột `*_enc` từ bản khôi phục:

- `organization.data_key_enc`;
- `camera`, `recorder`, `connector`, `app_user`, `notification_channel_config`, `directory_connection`.

Sau đó chạy [verify-secrets.py](../../deploy/backup/verify-secrets.py) trong container `python:3.12-slim`, với khóa chính gắn read-only. Script làm như `CryptoService`:

1. Mở khóa dữ liệu từng Tổ chức bằng khóa chính (AES-256-GCM, AAD là mã Tổ chức).
2. Giải mã từng bí mật.

Script chỉ in số lượng, không in giá trị. Sai hoặc thiếu khóa chính thì bước này báo LỖI, dù bước so sánh dữ liệu đạt. Đây là trường hợp "khôi phục được nhưng không dùng được" mà lần 1 chưa phát hiện được.

## Đánh giá RTO / RPO

**RTO ≤ 4 giờ – ĐẠT.** Tốc độ restore đo được khoảng 93 MB/s trên SSD laptop. Ước tính cho CSDL production 20 GB:

| Phần việc | Ước tính |
| --- | --- |
| Restore | ≈ 4 phút |
| Replay WAL (tối đa 24 giờ) | 10–30 phút |
| Đối chiếu | 15 phút; có thể bỏ khi khôi phục thật |

Cộng thao tác con người theo [restore.md](restore.md) mục 6 (≈ 3 giờ) vẫn dưới 4 giờ. Phần lớn RTO là thao tác con người (quyết định, chuẩn bị máy, lấy khóa chính offline), không phải thời gian máy.

**RPO ≤ 1 giờ – ĐẠT.** Không đổi so với lần 1:

- WAL được đẩy khi đầy đoạn hoặc sau tối đa 5 phút (`archive_timeout=300`).
- `pgbackrest check` trong diễn tập xác nhận WAL chứa T đã vào kho.
- MinIO sao chép hằng giờ.

## Việc còn mở từ lần 1

| # (lần 1) | Nội dung | Trạng thái |
| --- | --- | --- |
| 1 | Kho off-site `repo2` có mã hóa | **Mở**: cần hạ tầng production (G-02 trong [security-review.md](../test/security-review.md)) |
| 2 | Kiểm tra giải mã bằng khóa chính sau khôi phục | **Đóng**: bước 4b, tự động mỗi lần diễn tập |
| 3 | Tệp video không nằm trong sao lưu | Giới hạn đã chấp nhận (restore.md mục 1) |
| 5 | Đo với dữ liệu lớn | **Đóng một phần**: 1,1 GB. Đo ≥ 10 GB cần máy staging; laptop dev không đủ dung lượng trống cho dữ liệu đệm và kho |

## Phát hiện mới

| # | Phát hiện | Việc cần làm |
| --- | --- | --- |
| 1 | Từ 24/10 không có bản sao lưu đầy đủ mới. Lịch `0 1 * * *` không chạy khi máy dev tắt qua đêm; chỉ có bản differential hằng giờ, chuỗi bám vào bản đầy đủ cũ | Production chạy 24/7 nên ít gặp. Tab Sao lưu (US-40) có hiện thời điểm các bản sao lưu nhưng **chưa cảnh báo** khi bản đầy đủ quá cũ: đề xuất thêm cảnh báo "bản đầy đủ quá 36 giờ" |
| 2 | Bản đầy đủ của diễn tập (197,6 MB, chứa dữ liệu đệm) còn trong kho tới khi bị xoay vòng (`repo1-retention-full=30`) | Dev: chấp nhận. Lần sau có thể chạy `pgbackrest expire --set=<bản>` sau diễn tập |
| 3 | Bước đối chiếu md5 chiếm 75% thời gian với 1 GB (48,8 s) | Với CSDL lớn chỉ dùng trong diễn tập, không dùng khi khôi phục thật |

## Chạy lại

```bash
cd deploy
bash backup/restore-drill.sh          # gồm bước 4b giải mã bí mật
KEEP=1 bash backup/restore-drill.sh   # giữ lại camhub-drill-pg để kiểm tra thêm
```
