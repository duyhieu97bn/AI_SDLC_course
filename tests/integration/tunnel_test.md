# Kiểm thử đường hầm Site Connector – "chỉ outbound 443" (TS-06.4, ADR-10)

Mục tiêu: camera ở địa điểm từ xa không có IP tĩnh, mạng địa điểm chỉ cho đi ra cổng 443. Site Connector mở
đường hầm WSS 443 qua Nginx (`/tunnel/`) tới Tunnel Server (chisel, dịch vụ `tunnel`). Core tới camera qua:

- **ONVIF**: SOCKS5 ngược `socks5://tunnel:<socksPort>` (21000–21999), Device Gateway dùng.
- **RTSP**: chuyển tiếp TCP ngược `rtsp://user:pass@tunnel:<port>/...` (22000–23999), MediaMTX kéo (chỉ RTSP qua TCP).

## Mô hình mạng (docker compose, profile `site`)

| Mạng | Thành viên | Ý nghĩa |
| --- | --- | --- |
| `site-lan` (internal, 192.168.250.0/24) | site-camera, site-onvif, site-connector | LAN của địa điểm |
| `wan` (internal) | nginx, site-connector | "Internet": Site Connector chỉ thấy Nginx:443 |
| `backend` | core, device-gateway, mediamtx, tunnel, nginx… | Máy chủ CamHub; không có thành viên nào của site-lan |

- `site-camera`: MediaMTX tự phát `cam1` (H.264 1280×720 15 fps) và `cam2` (640×360), tài khoản đọc `admin/admin123`.
- `site-onvif`: onvif-sim (Axis P3245-LV SIM), GetStreamUri trả `rtsp://site-camera:8554/cam1|cam2`.
- `tunnel`: chisel 1.12.1 `server --reverse --authfile`, tệp xác thực do Core ghi vào volume `tunnelauth`.

## Chạy với Core thật

```bash
cd deploy
docker compose up -d tunnel nginx core                # tunnel thuộc profile mặc định
# Admin tạo bộ kết nối trên giao diện (hoặc POST /api/v1/connectors) và lấy mã kích hoạt
./scripts/dev.sh site <mã>                            # = CAMHUB_SITE_CODE=<mã> docker compose --profile site up -d site-connector
./scripts/dev.sh logs site-connector tunnel
```

Site Connector lưu thông tin đăng nhập trong volume `siteconnector`. Muốn kích hoạt lại thì xóa volume đó
(`docker compose --profile site rm -sf site-connector && docker volume rm camhub_siteconnector`).

## Chạy với Core giả (khi chưa có API Core)

`tests/integration/connector_stub.py` (chỉ thư viện chuẩn) giả lập `/api/v1/connector-agent/*`: mã `SITE-TEST-1`,
`socksPort` 21001, chuyển tiếp 22001 → `site-camera:8554`, một việc DISCOVERY, `POST /stub/revoke` để thu hồi.
API gọi vào stub, còn đường hầm vẫn đi qua Nginx `/tunnel/` tới `tunnel` thật.

```bash
R="$(pwd)"   # gốc repo; Git Bash trên Windows: export MSYS_NO_PATHCONV=1
docker compose -f deploy/docker-compose.yml --profile site up -d tunnel nginx site-camera site-onvif
docker run -d --name connector-stub --user 10001 --network camhub_wan --network-alias connector-stub \
  -v camhub_tunnelauth:/tunnel-auth -v "$R/deploy/nginx/certs:/certs:ro" -v "$R/tests/integration:/stub:ro" \
  python:3.12-slim python /stub/connector_stub.py
docker create --name site-connector-test --network camhub_site-lan \
  -e CAMHUB_SERVER=https://connector-stub -e CAMHUB_ACTIVATION_CODE=SITE-TEST-1 -e CAMHUB_TLS_INSECURE=true \
  -e CAMHUB_HEARTBEAT_SECONDS=10 -e CAMHUB_DISCOVERY_TARGETS=192.168.250.0/24 \
  -v sitetest:/var/lib/camhub-connector camhub/site-connector:dev
docker network connect camhub_wan site-connector-test && docker start site-connector-test
```

Dọn dẹp để Core thật tiếp quản: `docker rm -f connector-stub site-connector-test; docker volume rm sitetest`
và xóa `users.json` do stub ghi: `docker run --rm -v camhub_tunnelauth:/a alpine rm -f /a/users.json`.

## Các bước kiểm tra và kết quả (2026-10-25, chisel 1.12.1, stub)

| # | Bước | Kỳ vọng | Kết quả |
| --- | --- | --- | --- |
| 1 | Site Connector kích hoạt bằng mã | 200, lưu `credentials.json` (0600) | Đạt |
| 2 | Heartbeat → cấu hình `configVersion 1` | chisel client chạy với `R:0.0.0.0:21001:socks R:0.0.0.0:22001:site-camera:8554` | Đạt. Tunnel log `Open (user=conn-… remotes=…)`, `Listening` 21001 và 22001 |
| 3 | Heartbeat sau khi kết nối | `tunnelUp=true`, có rx/tx bytes, kbps | Đạt (`rxBytes 30443, txBytes 27702, rxKbps 20.5, txKbps 20.1`) |
| 4 | Việc DISCOVERY | Kết quả có site-onvif, hãng/model/MAC từ scope | Đạt: `{"host":"site-onvif","port":8080,"manufacturer":"Axis","model":"P3245-LV (SIM)","macAddress":"02:00:00:00:00:61",…}` |
| 5 | ONVIF qua SOCKS từ backend: `curl --socks5-hostname tunnel:21001 http://site-onvif:8080/onvif/device_service` (GetSystemDateAndTime) | SOAP trả lời | Đạt |
| 6 | RTSP qua cổng chuyển tiếp, từ container mediamtx: `ffprobe -rtsp_transport tcp rtsp://admin:admin123@tunnel:22001/cam1` | Đọc được luồng | Đạt: `h264 1280x720 15/1`; `cam2` cho `h264 640x360` |
| 7 | Backend tới thẳng site-lan (device-gateway → 192.168.250.2:8554, 192.168.250.3:8080; tên site-onvif/site-camera; core wget; mediamtx ffprobe trực tiếp) | Không tới được | Đạt: timeout / không phân giải được tên |
| 8 | Site Connector ra ngoài: nginx:443 | Mở | Đạt |
| 9 | Site Connector tới nginx:80, core, tunnel:8080, mediamtx, device-gateway (tên và IP backend 172.19.x), 1.1.1.1:443 | Không tới được | Đạt: connection refused / không phân giải / network unreachable |
| 10 | Thu hồi (heartbeat trả 401) | Dừng đường hầm, thoát mã 3, log rõ ràng; cổng 21001/22001 đóng | Đạt; chạy lại với mã cũ thoát mã 2 ngay ("cần mã kích hoạt mới"), không gọi Core |

Bước 7–9 chứng minh: địa điểm **chỉ có kết nối đi ra tới 443**, máy chủ không mở kết nối vào địa điểm, mọi
truy cập camera đều đi qua đường hầm do Site Connector mở.

## Ghi chú kỹ thuật

- **Định dạng authfile chisel**: regex so với `R:<địa chỉ nghe>:<cổng>` (không có đích, không có `:socks`), ví dụ
  `{"<user>:<pass>": ["^R:0\\.0\\.0\\.0:21001$", "^R:0\\.0\\.0\\.0:22001$"]}`. Remote SOCKS phải ghi rõ
  `R:0.0.0.0:<port>:socks`: dạng `R:<port>:socks` nghe ở 127.0.0.1 của Tunnel Server nên backend không tới được.
- **Tệp xác thực rỗng `{}` làm chisel TẮT xác thực** (đã kiểm chứng, kể cả khi nạp lại lúc đang chạy). Vì vậy
  `deploy/tunnel/entrypoint.sh` gộp tệp của Core với một tài khoản "lính gác" ngẫu nhiên không có quyền, rồi đưa
  cho chisel. chisel tự nạp lại khi tệp đổi, cả khi ghi đè tại chỗ lẫn khi đổi tên nguyên tử. Thu hồi chỉ chặn
  kết nối mới; đường hầm đang mở sẽ đóng khi Site Connector nhận 401 và thoát.
- **WS-Discovery**: Site Connector gửi Probe multicast trên mọi card IPv4 và Probe unicast tới
  `CAMHUB_DISCOVERY_TARGETS`. Mạng `internal` của Docker không có tuyến mặc định nên onvif-sim không tham gia được
  nhóm multicast (`No such device`). Vì vậy profile site đặt `CAMHUB_DISCOVERY_TARGETS=192.168.250.0/24`.
- **Đo băng thông**: chisel không có bộ đếm byte. Site Connector đọc `/proc/net/dev` của card đi ra máy chủ
  (chọn theo `/proc/net/route`, hoặc đặt `CAMHUB_WAN_INTERFACE`). Đây là số đo best effort, gồm cả lưu lượng heartbeat.
- **Host camera trong Core**: ONVIF trả XAddr/URI theo địa chỉ trong LAN địa điểm (ví dụ `rtsp://site-camera:8554/cam1`).
  Core phải dùng SOCKS cho ONVIF và đổi host:port RTSP thành `tunnel:<cổng chuyển tiếp>`.
