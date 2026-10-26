# Triển khai CamHub lên máy chủ 192.168.1.201

> Cập nhật: 26/10/2026. Máy chủ dùng chung cho dev/kiểm thử. Bản này ghi lại lần chuyển toàn bộ stack từ máy dev
> (Docker Desktop) sang máy chủ, kèm dữ liệu.

## Máy chủ

| Mục | Giá trị |
| --- | --- |
| Địa chỉ | 192.168.1.201 (LAN) |
| HĐH | Armbian 26.2 (Ubuntu 24.04 noble), x86_64, 2 CPU, 7 GB RAM, 294 GB đĩa |
| Docker | 29.6, Compose v5.3 |
| Thư mục | `/opt/camhub` (mã nguồn + `deploy/`); dữ liệu nằm trong volume Docker `camhub_*` |
| Truy cập | `https://192.168.1.201:8443` (chứng chỉ tự ký: CN/SAN = 192.168.1.201) |
| WebRTC | UDP/TCP 8189, `CAMHUB_WEBRTC_HOSTS=192.168.1.201,127.0.0.1` trong `deploy/.env` |

Máy chủ còn chạy dịch vụ khác (cổng 8000, 9000, 5678, 445). CamHub chỉ mở 8443 và 8189 ra LAN; Grafana (3000),
Prometheus (9090), RabbitMQ (15672), MinIO console (9001), Mailpit (8025), chat-mock (8026) chỉ nghe 127.0.0.1 –
xem qua đường hầm SSH, ví dụ `ssh -L 3000:127.0.0.1:3000 root@192.168.1.201`.

Tài khoản đăng nhập máy chủ do quản trị hạ tầng giữ, không ghi trong repo.

## Quy trình đã làm

1. **Mã nguồn:** đóng gói `git ls-files -co --exclude-standard` + `deploy/.env` + `deploy/secrets/` +
   `deploy/nginx/certs/` → `/opt/camhub`. Bí mật đi kèm vì dữ liệu cũ mã hóa bằng `camhub_master_key`
   (thiếu khóa này thì không giải mã được mật khẩu camera, INV-17).
2. **Cấu hình riêng máy chủ:** `deploy/.env` đặt `CAMHUB_WEBRTC_HOSTS` và `CAMHUB_PUBLIC_URL=https://192.168.1.201:8443`;
   tạo lại chứng chỉ tự ký có SAN là IP máy chủ.
3. **Image:** build ngay trên máy chủ: `docker compose --profile sim --profile site build`. Lần đầu mất khoảng 25 phút
   trên 2 CPU.
4. **Dữ liệu:** dừng stack ở máy dev, xuất từng volume bằng `tar` (container alpine) → chép lên `/opt/camhub/migrate`
   → `restore-volumes.sh` tạo volume có nhãn Compose (project `camhub`) rồi giải nén. Gồm: pgdata, pgbackrest,
   recordings (≈10 GB), miniodata, miniobackup, rabbitdata, redisdata, grafanadata, promdata, lokidata, backupstatus,
   tunnelauth, siteconnector, simsamples. Bỏ qua cache (e2e, bench) và `pgsocket`.
5. **Chạy:** `cd /opt/camhub/deploy && docker compose --profile sim up -d`; Site Connector giả lập:
   `docker compose --profile site up -d site-connector` (thông tin kích hoạt đã có trong volume `siteconnector`).

## Vận hành thường ngày

```bash
cd /opt/camhub/deploy
docker compose --profile sim ps                 # trạng thái
docker compose logs -f core                     # log Core
docker compose --profile sim up -d --build core # cập nhật sau khi chép mã mới
../tests/e2e/run.sh specs/s8.spec.js            # e2e chạy ngay trên máy chủ (mạng camhub_backend)
```

## Lưu ý

- **Tài nguyên:** 2 CPU là sát với bộ giả lập 24 luồng RTSP + ghi hình + chuyển mã theo yêu cầu. Nếu tải cao kéo dài,
  giảm `SIM_STREAMS` trong `.env` hoặc tắt bớt camera giả lập.
- **Sao lưu:** dịch vụ `backup` vẫn ghi kho pgBackRest và bản sao MinIO trên cùng máy. Production cần `repo2`
  off-site (xem [restore.md](restore.md)).
- **Máy dev:** stack cũ trên Docker Desktop đã dừng (không xóa volume) để tránh chạy hai bản cùng dữ liệu.
