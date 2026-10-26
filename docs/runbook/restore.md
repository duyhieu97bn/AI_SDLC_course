# Runbook sao lưu và khôi phục CamHub (US-40.2)

Truy vết: NFR-10, NFR-11 · UC-61, UC-62 · SADD 3.12.3 · US-40.1, US-40.2, US-40.5.

| Mục tiêu | Giá trị | Cơ sở |
| --- | --- | --- |
| RTO (thời gian khôi phục) | ≤ 4 giờ | NFR-11; diễn tập: [restore-drill-01.md](restore-drill-01.md) |
| RPO (dữ liệu mất tối đa) | ≤ 1 giờ | WAL đẩy liên tục, `archive_timeout` 5 phút; MinIO sao chép hằng giờ |
| Người thực hiện | Đội vận hành IT (UCQ-03) | Admin tổ chức chỉ **xem** trạng thái ở tab Sao lưu, không có nút khôi phục trên web |

## 1. Những gì được sao lưu

| Dữ liệu | Cách sao lưu | Ghi chú |
| --- | --- | --- |
| PostgreSQL (toàn bộ cụm) | pgBackRest: bản đầy đủ 01:00 hằng ngày, bản differential mỗi giờ, WAL liên tục; giữ 30 bản đầy đủ | Kho: volume `pgbackrest`. Production phải có `repo2` off-site (mục 3) |
| MinIO (`camhub-exports`, `camhub-snapshots`) | `mc mirror` mỗi giờ (phút 30) | Dev: volume `miniobackup`. Production: S3/NAS off-site. Mặc định **không** xóa ở bản sao khi đối tượng bị xóa ở nguồn |
| Bí mật `deploy/secrets/` | **Thủ công, offline** (mục 4) | Không nằm trong kho sao lưu, không nằm trong Git |
| Cấu hình triển khai | Git (`deploy/`) | Ghi lại commit/tag đang chạy trong sổ vận hành |

Không sao lưu:

- **Video ghi hình (volume `recordings`)**: dung lượng lớn, đã có chính sách lưu giữ (US-21) tự xóa tệp quá hạn. Mất máy chủ media = mất video đã ghi; giảm rủi ro bằng RAID/ổ dự phòng ở tầng lưu trữ. Sau khôi phục, bảng `recording_segment` vẫn còn siêu dữ liệu của các đoạn video không còn tệp – xem lại khoảng thời gian đó sẽ không phát được cho tới khi các dòng này hết hạn lưu giữ.
- Redis (bộ đệm, phiên tạm), hàng đợi RabbitMQ (sự kiện chưa gửi nằm trong bảng `outbox_event` của PostgreSQL nên không mất), Prometheus, Loki, Grafana (dữ liệu giám sát).

## 2. Kiến trúc sao lưu (môi trường Docker Compose)

```text
 postgres (camhub/postgres:dev)                 backup (cùng image, crond)
 ├─ archive_command → pgbackrest archive-push   ├─ 01:00  camhub-backup pg full
 │      │                                       ├─ hh:00  camhub-backup pg diff   (bỏ qua nếu vừa có bản < 30 phút)
 │      ▼                                       ├─ hh:30  camhub-backup minio     (mc mirror minio → /miniobackup)
 │  volume pgbackrest  ◄────── backup/expire ───┤  */5    camhub-backup status    (làm mới status.json)
 │                                              │
 └─ volume pgsocket (Unix socket) ─────────────►┘  đọc pgdata (read-only), kết nối qua socket
                                                   ghi /backup-status/status.json ──► core (read-only) → GET /system/backups
```

- Vì sao dùng container `backup` riêng: pgBackRest chỉ sao lưu được khi đọc trực tiếp thư mục dữ liệu và kết nối tới PostgreSQL qua Unix socket (hoặc qua SSH/TLS server). Container `backup` gắn chung volume `pgdata` (read-only), `pgsocket` và `pgbackrest` với `postgres`, nên chạy như "cùng máy" mà không cần SSH/TLS, và lịch sao lưu không làm khởi động lại CSDL.
- Lần khởi động đầu, `backup` tự chạy `stanza-create`, `check`, một bản đầy đủ nếu kho chưa có bản nào, một lần sao chép MinIO.
- Cấu hình: [deploy/backup/pgbackrest.conf](../../deploy/backup/pgbackrest.conf), script: [deploy/backup/camhub-backup.sh](../../deploy/backup/camhub-backup.sh), image: [deploy/postgres/Dockerfile](../../deploy/postgres/Dockerfile).
- Lịch chỉnh trong `deploy/.env` (cú pháp cron, giờ theo `CAMHUB_TZ`, mặc định `Asia/Ho_Chi_Minh`):

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `CAMHUB_BACKUP_FULL_CRON` | `0 1 * * *` | Bản đầy đủ |
| `CAMHUB_BACKUP_DIFF_CRON` | `0 * * * *` | Bản hằng giờ |
| `CAMHUB_BACKUP_HOURLY_TYPE` | `diff` | `diff` (so với bản đầy đủ) hoặc `incr` (so với bản liền trước) |
| `CAMHUB_MINIO_MIRROR_CRON` | `30 * * * *` | Sao chép MinIO |
| `CAMHUB_MINIO_MIRROR_REMOVE` | `0` | `1`: xóa ở bản sao khi nguồn xóa (bản sao giống hệt nguồn, nhưng mất khả năng cứu đối tượng xóa nhầm) |
| `CAMHUB_BACKUP_STALE_HOURS` | `2` | Quá số giờ này không có bản mới → trạng thái `ERROR` |
| `CAMHUB_PG_ARCHIVE_TIMEOUT` | `300` | Buộc đóng đoạn WAL sau tối đa N giây khi ít ghi |

## 3. Yêu cầu bắt buộc cho production

1. **Kho off-site**: kho `repo1` trên volume cục bộ chỉ chống lỗi logic (xóa nhầm, hỏng dữ liệu), **không** chống mất máy chủ. Thêm `repo2` S3/NAS ở vị trí khác vào `pgbackrest.conf` (`repo2-type=s3`, `repo2-retention-full=30`) và bật `repo2-cipher-type=aes-256-cbc`; mật khẩu mã hóa kho đặt qua biến môi trường `PGBACKREST_REPO2_CIPHER_PASS` từ bí mật, lưu offline cùng khóa chủ (mục 4). Biến này phải có ở cả container `postgres` (archive-push) và `backup`.
2. **MinIO off-site**: đổi đích `mc mirror` từ volume `miniobackup` sang bucket S3/NAS off-site; dùng access key **chỉ đọc** trên MinIO nguồn thay cho tài khoản root.
3. Cảnh báo: theo dõi `status` ở tab Sao lưu (Core đọc `status.json`) và log container `backup`; cấu hình cảnh báo khi `postgres.status` hoặc `minio.status` là `ERROR`.
4. Diễn tập khôi phục ít nhất mỗi quý và trước mỗi lần nâng cấp major PostgreSQL (lần 2: TS-08.3, `restore-drill-02.md`).

## 4. Bí mật và khóa chủ – lưu offline

`camhub_master_key` là khóa chính AES-256 (mã `mk1`, biến `CAMHUB_MASTER_KEY_ID`) bọc khóa dữ liệu của từng Tổ chức; khóa dữ liệu mã hóa mật khẩu camera và thông tin nhạy cảm trong CSDL (`platform/crypto/CryptoService`). **Bản sao lưu CSDL vô dụng một phần nếu mất khóa này**: CamHub vẫn chạy nhưng không giải mã được mật khẩu camera – phải nhập lại mật khẩu cho từng camera. Khôi phục với khóa sai hoặc mã khóa khác cho kết quả tương tự (lỗi `CryptoException` trong log Core).

Quy định:

- Ngay sau khi triển khai (và sau mỗi lần xoay khóa), Đội vận hành IT xuất toàn bộ `deploy/secrets/` thành tệp mã hóa (`tar czf - secrets | gpg --symmetric --cipher-algo AES256 > camhub-secrets-YYYYMMDD.tgz.gpg`), lưu **hai bản ở hai nơi khác nhau**: kho mật khẩu của tổ chức và thiết bị offline (USB mã hóa trong két). Không lưu cùng nơi với kho sao lưu.
- Ghi `sha256sum secrets/camhub_master_key` vào sổ vận hành để đối chiếu khi khôi phục.
- Các bí mật phải giữ nguyên khi khôi phục:

| Bí mật | Bắt buộc giữ nguyên? | Lý do |
| --- | --- | --- |
| `camhub_master_key` | **Có** | Giải mã mật khẩu camera |
| `db_owner_password`, `db_app_password` | Có (hoặc đặt lại) | Mật khẩu role `camhub`, `camhub_app` nằm trong CSDL được khôi phục; nếu mất thì đặt lại bằng `alter role ... password` qua socket trong container `postgres` |
| `media_token_secret`, `jwt_secret` | Không | Sinh mới làm mọi phiên đăng nhập/token xem video hết hiệu lực – người dùng đăng nhập lại |
| `minio_root_password`, `rabbitmq_password`, `rabbitmq-users.json`, `gateway_*`, `grafana_admin_password` | Không | Sinh mới bằng `dev.sh init` là được (xóa `rabbitmq-users.json` và `gateway_rabbitmq_url` cùng lúc với `rabbitmq_password`) |
| `bootstrap_admin_password` | Không | Chỉ dùng khi CSDL trống |

## 5. Kiểm tra hằng ngày

```bash
cd deploy
docker compose exec backup cat /backup-status/status.json          # trạng thái Core hiển thị
docker compose exec backup su-exec postgres pgbackrest --stanza=camhub info
docker compose exec backup camhub-backup check                      # WAL archive hoạt động
docker compose logs --tail 100 backup
```

Chạy tay khi cần: `docker compose exec backup camhub-backup pg full|diff|incr`, `docker compose exec backup camhub-backup minio`.

## 6. Khôi phục sang máy chủ mới (mất máy chủ)

Ước tính thời gian cho CSDL ~20 GB (diễn tập lần 1 với CSDL nhỏ: phần kỹ thuật dưới 1 phút, xem [restore-drill-01.md](restore-drill-01.md)):

| Bước | Thời gian ước tính |
| --- | --- |
| 6.1 Quyết định, chọn mốc khôi phục | 15 phút |
| 6.2 Chuẩn bị máy chủ, mã nguồn, bí mật | 45 phút |
| 6.3 Lấy kho sao lưu về | 30 phút |
| 6.4 Khôi phục PostgreSQL (restore + replay WAL) | 30–60 phút |
| 6.5 Khôi phục MinIO | 15–30 phút |
| 6.6 Khởi động, kiểm tra | 30 phút |
| **Tổng** | **≈ 3 giờ – trong RTO 4 giờ** |

### 6.1 Quyết định và mốc khôi phục

- Người chỉ huy sự cố quyết định khôi phục, ghi giờ bắt đầu (tính RTO).
- Mất máy chủ: khôi phục tới **điểm mới nhất** có trong kho (không cần `--type`).
- Lỗi logic (xóa nhầm, dữ liệu hỏng): xem mục 7 trước – thường không cần quay lui toàn bộ hệ thống.

### 6.2 Chuẩn bị máy chủ

```bash
git clone <repo> camhub && cd camhub && git checkout <tag đang chạy trước sự cố>
cd deploy
# Giải mã bản bí mật offline vào deploy/secrets/
gpg -d camhub-secrets-YYYYMMDD.tgz.gpg | tar xzf -
sha256sum secrets/camhub_master_key           # đối chiếu với sổ vận hành
./scripts/dev.sh init                          # chỉ sinh bí mật còn thiếu, chứng chỉ TLS, .env
docker compose build postgres                  # image camhub/postgres:dev (PostgreSQL 16 + pgBackRest)
```

Phiên bản PostgreSQL trên máy mới phải **cùng major (16)** với bản sao lưu.

### 6.3 Lấy kho sao lưu về

- Production (`repo2` S3 off-site): cấu hình `repo2-*` trong `pgbackrest.conf` như máy cũ, thêm `--repo=2` vào lệnh restore ở 6.4. Không cần sao chép.
- Dev/staging (kho trên volume): chép volume từ máy cũ hoặc nơi lưu tệp nén:

```bash
# Máy cũ (nếu còn truy cập được):
docker run --rm -v camhub_pgbackrest:/repo:ro -v "$PWD":/out alpine tar czf /out/pgbackrest.tgz -C /repo .
docker run --rm -v camhub_miniobackup:/m:ro  -v "$PWD":/out alpine tar czf /out/miniobackup.tgz -C /m .
# Máy mới:
docker volume create camhub_pgbackrest && docker volume create camhub_miniobackup
docker run --rm -v camhub_pgbackrest:/repo -v "$PWD":/in alpine sh -c 'tar xzf /in/pgbackrest.tgz -C /repo && chown -R 70:70 /repo'
docker run --rm -v camhub_miniobackup:/m  -v "$PWD":/in alpine sh -c 'tar xzf /in/miniobackup.tgz -C /m && chown -R 70:70 /m'
```

Kiểm tra kho đọc được và có bản sao lưu:

```bash
docker compose run --rm --no-deps --user postgres --entrypoint pgbackrest postgres --stanza=camhub info
```

### 6.4 Khôi phục PostgreSQL

Volume `pgdata` trên máy mới phải **trống** (chưa từng `up` postgres). Nếu đã lỡ khởi tạo, `docker compose rm -sf postgres && docker volume rm camhub_pgdata`.

```bash
# Điểm mới nhất:
docker compose run --rm --no-deps --user postgres --entrypoint pgbackrest postgres \
  --stanza=camhub restore
# Hoặc PITR tới một thời điểm (giờ UTC, ghi rõ +00):
docker compose run --rm --no-deps --user postgres --entrypoint pgbackrest postgres \
  --stanza=camhub --type=time "--target=2026-10-24 03:25:20+00" --target-action=promote restore

docker compose up -d postgres
docker compose logs -f postgres     # chờ "recovery stopping…"/"redo done", "selected new timeline", "ready to accept connections"
docker compose exec postgres psql -U camhub -d camhub -XAtc "select pg_is_in_recovery()"   # phải là f
```

pgBackRest ghi sẵn `restore_command` vào `postgresql.auto.conf`; PostgreSQL tự lấy WAL từ kho và replay. Sau khi promote, cụm sang timeline mới và tiếp tục đẩy WAL vào kho như bình thường.

Bật sao lưu trở lại và tạo ngay một bản đầy đủ mới (mốc an toàn sau sự cố):

```bash
docker compose up -d backup
docker compose exec backup camhub-backup check
docker compose exec backup camhub-backup pg full
```

### 6.5 Khôi phục MinIO

```bash
docker compose up -d minio
# Chép ngược bản sao vào MinIO mới (dev: từ volume miniobackup; production: thay /miniobackup/$b bằng off-site/$b)
docker compose run --rm --no-deps --entrypoint sh backup -c '
  export MC_CONFIG_DIR=/tmp/.mc MC_HOST_dst="http://camhub:$(cat /run/secrets/minio_root_password)@minio:9000"
  for d in /miniobackup/*/; do b=$(basename "$d"); mc mb -q --ignore-existing "dst/$b"; mc mirror -q --overwrite "$d" "dst/$b"; done
  mc ls -r dst | wc -l'
```

### 6.6 Khởi động và kiểm tra

```bash
./scripts/dev.sh up            # production: lệnh triển khai tương ứng, không bật profile sim
./scripts/dev.sh smoke
```

Kiểm tra tay, ghi kết quả vào biên bản sự cố:

- [ ] Đăng nhập bằng tài khoản thật (không phải bootstrap)
- [ ] Danh sách camera đầy đủ; **kết nối được camera** (chứng minh `camhub_master_key` đúng – sai khóa thì Core báo lỗi giải mã mật khẩu camera)
- [ ] Xem trực tiếp, xem lại một đoạn ghi **sau** thời điểm khôi phục
- [ ] Tải được một tệp xuất clip cũ (MinIO)
- [ ] Tab Sao lưu: `postgres.status = OK`, có bản đầy đủ mới
- [ ] Ghi giờ kết thúc, tính RTO; tính RPO = thời điểm sự cố − `lastWalArchivedAt` gần nhất trước sự cố

## 7. Khôi phục dữ liệu bị xóa nhầm (không dừng hệ thống)

Không quay lui CSDL đang chạy. Khôi phục PITR vào một container tạm, lấy dữ liệu cần thiết rồi đưa vào CSDL thật:

```bash
cd deploy
docker volume create camhub_pitr
docker run --rm -u postgres -v camhub_pgbackrest:/var/lib/pgbackrest:ro -v camhub_pitr:/var/lib/postgresql/data \
  camhub/postgres:dev pgbackrest --stanza=camhub --type=time "--target=<trước lúc xóa nhầm>+00" --target-action=promote restore
# archive_mode=off: bản tạm KHÔNG được đẩy WAL vào kho thật
docker run -d --name camhub-pitr --network none -v camhub_pgbackrest:/var/lib/pgbackrest:ro \
  -v camhub_pitr:/var/lib/postgresql/data camhub/postgres:dev postgres -c archive_mode=off
docker exec camhub-pitr pg_dump -U camhub -d camhub --data-only -t public.<bảng> > rescue.sql
# Xem xét rescue.sql, nạp phần cần thiết vào CSDL thật, sau đó dọn:
docker rm -f camhub-pitr && docker volume rm camhub_pitr
```

Script diễn tập [deploy/backup/restore-drill.sh](../../deploy/backup/restore-drill.sh) làm đúng quy trình này và đối chiếu dữ liệu tự động.

## 8. Tệp trạng thái cho Core (GET /system/backups)

Container `backup` ghi `/backup-status/status.json` (volume `backupstatus`, gắn read-only vào `core` tại `/backup-status`, biến `CAMHUB_BACKUP_STATUS_DIR=/backup-status`). Ghi nguyên tử (tệp tạm + `mv`) khi khởi động, sau mỗi job và mỗi 5 phút.

```json
{
  "generatedAt": "2026-10-24T03:22:43Z",
  "postgres": {
    "stanza": "camhub", "status": "OK", "message": null,
    "lastBackupAt": "2026-10-24T03:22:43Z", "lastBackupType": "full",
    "backupCount": 1, "fullCount": 1,
    "lastWalArchivedAt": "2026-10-24T03:22:43Z", "repoSizeBytes": 7815168,
    "backups": [{ "label": "20261024-102239F", "type": "full",
                  "startedAt": "2026-10-24T03:22:39Z", "finishedAt": "2026-10-24T03:22:43Z", "sizeBytes": 4023153 }]
  },
  "minio": { "status": "OK", "message": null, "lastMirrorAt": "2026-10-24T03:22:43Z", "objects": 0 }
}
```

- Thời gian dạng ISO-8601 UTC. `backups`: mới nhất trước, tối đa 50. `sizeBytes`: dung lượng bản đó chiếm trong kho (sau nén, với diff/incr chỉ phần thay đổi). `repoSizeBytes`: toàn bộ kho gồm WAL.
- `postgres.status = ERROR` khi: lần sao lưu gần nhất lỗi; pgBackRest báo lỗi stanza/kho; đẩy WAL đang lỗi (`pg_stat_archiver.last_failed_time` mới hơn `last_archived_time`); hoặc bản mới nhất cũ hơn `CAMHUB_BACKUP_STALE_HOURS`. `message` nối các lý do bằng `"; "`.
- `minio.status`: `UNKNOWN` khi chưa chạy lần nào; `ERROR` khi lần gần nhất lỗi hoặc lần thành công gần nhất cũ hơn 3 giờ. `lastMirrorAt` là lần **thành công** gần nhất.
- Thư mục `/backup-status/.state/` là trạng thái nội bộ của job, Core không đọc.

## 9. Xử lý sự cố thường gặp

| Hiện tượng | Nguyên nhân / cách xử lý |
| --- | --- |
| `WAL archive ... lỗi`, thư mục `pg_wal` phình to | Kho đầy hoặc không ghi được. Kiểm tra dung lượng volume `pgbackrest`, `docker compose logs postgres \| grep archive-push`. PostgreSQL giữ WAL tới khi đẩy được – đừng xóa tay `pg_wal` |
| `stanza-create` báo không khớp cụm (system-id) | CSDL bị khởi tạo lại trên kho cũ. Không xóa kho; đổi `repo1-path` sang thư mục mới hoặc chuyển kho cũ đi lưu trữ rồi tạo stanza mới, chạy bản đầy đủ |
| `unable to find primary cluster` / không kết nối được | Container `postgres` chưa chạy hoặc volume `pgsocket` không được gắn; `docker compose up -d postgres backup` |
| Bản hằng giờ báo "Bỏ qua" | Bình thường khi vừa có bản khác trong 30 phút (ví dụ 01:00 sau bản đầy đủ) |
| Sau nâng cấp major PostgreSQL | Chạy `pgbackrest --stanza=camhub stanza-upgrade` rồi một bản đầy đủ; diễn tập lại |
