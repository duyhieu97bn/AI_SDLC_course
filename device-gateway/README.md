# CamHub Device Gateway

Dịch vụ trung gian giữa Core và thiết bị (SADD 3.3). Gateway nói ONVIF với camera: SOAP 1.2 viết tay qua `httpx`, WS-Security PasswordDigest có bù lệch đồng hồ thiết bị. Gateway nhận lệnh REST nội bộ từ Core rồi đẩy sự kiện vào RabbitMQ (exchange `device.events`).

| Module | Nội dung |
| --- | --- |
| `app/onvif` | Client ONVIF: Device, Media ver10, PTZ ver20, Imaging ver20, Events (PullPoint) |
| `app/discovery` | WS-Discovery: Probe multicast và unicast (IP/CIDR, tối đa 1024 địa chỉ) |
| `app/capabilities` | Ghép thông tin thiết bị, năng lực, profile MAIN/SUB |
| `app/edge_playback` | Phát lại bản ghi trên thiết bị (Profile G): tìm bản ghi, khoảng có dữ liệu, URI phát lại |
| `app/events` | Đăng ký PullPoint theo camera, chuẩn hóa chuyển động, theo dõi online/offline, publisher RabbitMQ |
| `app/routes.py` | REST `/v1` (hợp đồng bên dưới) |
| `app/security.py` | Middleware `X-Internal-Token` |

## Chạy và kiểm thử

```bash
cd device-gateway
pip install -e ".[dev]"
uvicorn app.main:app --port 8000

ruff check . && ruff format --check . && mypy && pytest -q
pytest -m compat            # chỉ bộ tương thích thiết bị (TS-05.4), ghi docs/compat/report.md
```

`pytest` mặc định có chạy bộ tương thích với hai bộ giả lập ONVIF ([tools/onvif-sim](../tools/onvif-sim/README.md)) trên localhost. Muốn thử camera thật thì chép `tests/compat/cameras.example.yaml` thành `tests/compat/cameras.yaml`, khai báo tên biến môi trường chứa tài khoản, rồi chạy `pytest -m compat`. Camera không kết nối được hoặc thiếu tài khoản được đánh dấu BỎ QUA.

## Cấu hình (`CAMHUB_GW_*`)

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `CAMHUB_GW_INTERNAL_TOKEN` | rỗng | Token Core gửi trong `X-Internal-Token`. Để rỗng thì tắt kiểm tra (chỉ dùng cho dev) |
| `CAMHUB_GW_RABBITMQ_URL` | `amqp://camhub:camhub@localhost:5672/camhub` | Kết nối RabbitMQ |
| `CAMHUB_GW_RABBITMQ_EXCHANGE` | `device.events` | Exchange topic |
| `CAMHUB_GW_LOG_LEVEL` | `INFO` | |
| `CAMHUB_GW_DEVICE_TIMEOUT_SECONDS` | `10` | Timeout mỗi lời gọi ONVIF |
| `CAMHUB_GW_DEVICE_TLS_VERIFY` | `false` | Kiểm tra chứng chỉ HTTPS của camera |
| `CAMHUB_GW_REWRITE_XADDR` | `true` | Thay host:port trong XAddr khi khác địa chỉ Core gửi (camera sau NAT) |
| `CAMHUB_GW_SESSION_CACHE_SECONDS` | `300` | Thời gian nhớ độ lệch đồng hồ và danh sách dịch vụ |
| `CAMHUB_GW_DISCOVERY_PORT` / `CAMHUB_GW_DISCOVERY_MULTICAST` | `3702` / `true` | WS-Discovery |
| `CAMHUB_GW_EVENTS_PULL_TIMEOUT_SECONDS` | `10` | Timeout PullMessages |
| `CAMHUB_GW_EVENTS_SUBSCRIPTION_TTL_SECONDS` | `60` | Thời hạn PullPoint; gia hạn sau mỗi nửa thời hạn |
| `CAMHUB_GW_EVENTS_BACKOFF_MAX_SECONDS` | `60` | Backoff tối đa khi mất kết nối camera |
| `CAMHUB_GW_MONITOR_TIMEOUT_SECONDS` / `_CONCURRENCY` / `_FAILURE_THRESHOLD` | `5` / `50` / `2` | Theo dõi online/offline |

Có thể đặt bí mật bằng Docker secret thay cho biến môi trường: file `/run/secrets/camhub_gw_internal_token` và `/run/secrets/camhub_gw_rabbitmq_url`.

## Hợp đồng REST `/v1`

Mọi endpoint `/v1` đều cần header `X-Internal-Token`. Thiếu hoặc sai token thì trả `401 {"code":"GATEWAY_UNAUTHORIZED"}`. `/health` và `/metrics` không cần token. JSON dùng camelCase.

Thiết bị trong body luôn có dạng `device = {"host","port","username","password","proxy"?}`. `proxy` (TS-06.3) là proxy SOCKS5 của tunnel site, chỉ nhận `socks5://host:port` hoặc `socks5h://host:port` (sai dạng trả 422): mọi lời gọi HTTP tới thiết bị (SOAP, XAddr kể cả địa chỉ LAN của site, PullPoint, URI ảnh chụp) đi qua proxy, tên/địa chỉ thiết bị được phân giải ở phía connector. Target theo dõi `PUT /v1/monitor/targets` cũng nhận `proxy` (kiểm tra ONVIF và RTSP đều qua tunnel). Mỗi proxy dùng một connection pool riêng (`app/transport/tunnel_router.py`); bộ nhớ phiên thiết bị phân biệt theo (proxy, host, port). Mật khẩu không bao giờ được ghi log. Lỗi kiểm tra đầu vào (422) không trả lại giá trị đã gửi.

Lỗi thiết bị trả **HTTP 424** `{"code","detail"}`, với `code` là một trong các giá trị sau:

- `AUTH_FAILED`: HTTP 401 hoặc Fault NotAuthorized.
- `UNREACHABLE`: không kết nối được hoặc hết thời gian chờ.
- `NOT_ONVIF`: thiết bị trả phản hồi không phải SOAP.
- `NOT_SUPPORTED`: ActionNotSupported, hoặc thiết bị không có dịch vụ hay năng lực cần dùng.
- `DEVICE_ERROR`: Fault khác; `detail` là lý do thiết bị trả về.
- `LIMIT_EXCEEDED`: yêu cầu vượt giới hạn thiết bị (số vùng, số điểm mỗi vùng che).
- `INVALID_ZONE`: vùng không khớp với thiết bị (ví dụ lưới ô sai số hàng/cột).

| Endpoint | Body | Phản hồi |
| --- | --- | --- |
| `POST /v1/discovery` | `{targets: [IP\|CIDR], timeoutSeconds}` | `[{host, port, manufacturer, model, macAddress, hardware, scopes}]`; mục tiêu sai trả 422 `INVALID_TARGET` |
| `POST /v1/devices/info` | `device` | `{manufacturer, model, firmware, serialNumber, macAddress, capabilities{ptz, audioIn, audioOut, imaging, events, edgeRecording, motionDetection, privacyMask}, profiles[{token, name, type: MAIN\|SUB\|OTHER, codec, width, height, fps, bitrateKbps, rtspUrl, bFrames}]}` |
| `POST /v1/devices/recorder/channels` | `{device}` | `{manufacturer, model, channelCount, channels[{channel (từ 1), name, videoSourceToken, profileToken, subProfileToken, hasSignal, resolution{width,height}\|null, rtspUrl, subRtspUrl}]}` (US-03.1, quy tắc suy đoán ở `app/discovery/recorder_channels.py`) |
| `POST /v1/devices/recordings/search` | `{device, from, to, videoSourceToken?}` (ISO-8601, `to` > `from`, tối đa 31 ngày) | `{supported, reason, recordings[{recordingToken, videoSourceToken, trackToken, ranges[{start, end}]}]}` (US-22.1). Thiết bị thiếu Search/Replay trả 200 `supported: false` kèm `reason`; `ranges` là các khoảng có dữ liệu cắt theo cửa sổ. Quy tắc ở `app/edge_playback/service.py` |
| `POST /v1/devices/recordings/replay-uri` | `{device, recordingToken, start}` | `{uri}`: URI GetReplayUri (RTSP), thêm `?starttime=YYYYMMDDTHHMMSSZ` (UTC, kiểu Hikvision) khi URI chưa có query vì MediaMTX/ffmpeg không gửi được header `Range: clock=`; thiết bị không có Profile G trả 424 `NOT_SUPPORTED` |
| `POST /v1/devices/ptz/move` | `{device, profileToken, pan, tilt, zoom, timeoutMs?}` (−1..1) | 204 |
| `POST /v1/devices/ptz/stop` | `{device, profileToken}` | 204 |
| `POST /v1/devices/ptz/presets` | `{device, profileToken}` | `[{token, name}]` |
| `POST /v1/devices/ptz/presets/goto` | `{device, profileToken, presetToken}` | 204 |
| `POST /v1/devices/ptz/presets/set` | `{device, profileToken, name, presetToken?}` | `{token}` |
| `POST /v1/devices/ptz/presets/remove` | `{device, profileToken, presetToken}` | 204 |
| `POST /v1/devices/imaging/get` | `{device, videoSourceToken?}` | `{videoSourceToken, brightness, contrast, irMode: AUTO\|DAY\|NIGHT, wdr, options{brightness{min,max}, contrast{min,max}, irModes[], wdr}}` |
| `POST /v1/devices/imaging/set` | `{device, videoSourceToken?, settings{brightness?, contrast?, irMode?, wdr?}}` | 204 |
| `POST /v1/devices/encoder/get` | `{device}` | `[{token, name, codec, width, height, fps, bitrateKbps, govLength, quality, h264Profile, bFrames, options{codecs[], resolutions[{width,height}], fpsRange, bitrateRange, govLengthRange, qualityRange, h264Profiles[]}}]` |
| `POST /v1/devices/encoder/set` | `{device, configurationToken, codec?: H264\|H265\|JPEG\|MPEG4, width?, height?, fps?, bitrateKbps?, govLength?}` | 204 |
| `POST /v1/devices/network/get` | `{device}` | `{ipAddress, macAddress, hostname, ntpServers[], ntpFromDhcp, fromDhcp, deviceTime, clockDriftSeconds}` |
| `POST /v1/devices/ntp/set` | `{device, servers[1..4]}` | 204 (đồng thời chuyển DateTimeType sang NTP) |
| `POST /v1/devices/reboot` | `{device}` | `{message}` |
| `POST /v1/devices/password` | `{device, username, newPassword}` | 204 |
| `POST /v1/devices/snapshot` | `{device, profileToken?}` | `image/jpeg` (mặc định dùng profile MAIN) |
| `POST /v1/devices/zones/get` | `{device}` | `{motion{supported, mode: CELL\|POLYGON\|null, grid{columns, rows}\|null, cells[rows][columns]\|null, polygons[[{x,y}]], sensitivity 0..100\|null, maxPolygons\|null}, masks{supported, maxMasks\|null, maxPoints\|null, items[{token, points[{x,y}], enabled}]}}` |
| `POST /v1/devices/zones/motion/set` | `{device, mode: CELL\|POLYGON, cells?, polygons?[[{x,y}]], sensitivity?}` | 204; số đa giác vượt `maxPolygons` → 424 `LIMIT_EXCEEDED`; lưới sai kích thước → 424 `INVALID_ZONE` |
| `POST /v1/devices/zones/masks/set` | `{device, items[{points[{x,y}], enabled}]}` (thay toàn bộ) | 204; vượt `maxMasks`/`maxPoints` → 424 `LIMIT_EXCEEDED` |
| `POST /v1/events/subscriptions` | `{cameraId, device}` | 202 `{cameraId, host, port, status, since, lastError, lastEventAt, motion}` |
| `GET /v1/events/subscriptions[/{cameraId}]` | | danh sách hoặc một mục; không có thì 404 `SUBSCRIPTION_NOT_FOUND` |
| `DELETE /v1/events/subscriptions/{cameraId}` | | 204 / 404 |
| `PUT /v1/monitor/targets` | `{targets[{cameraId, host, port, protocol: ONVIF\|RTSP, rtspHost?, rtspPort?}], intervalSeconds}` | `[{…target, status: ONLINE\|OFFLINE\|UNKNOWN, lastChangeAt, lastCheckAt, lastError}]`; `cameraId` trùng trả 422 `DUPLICATE_CAMERA` |
| `GET /v1/monitor/targets` | | như trên |

`status` của đăng ký sự kiện là một trong `STARTING`, `ACTIVE`, `RECONNECTING`, `OFFLINE`, `UNSUPPORTED`, `STOPPED`.

Đăng ký sự kiện và danh sách theo dõi chỉ nằm trong bộ nhớ. Core cần gửi lại (idempotent) khi khởi động và khi Gateway khởi động lại (ví dụ khi `/health` đổi `version`, hoặc định kỳ).

## Thông điệp RabbitMQ (`device.events`, persistent, `message_id` là uuid4)

| Routing key | JSON |
| --- | --- |
| `motion.detected` | `{messageId, type: "MotionDetected", cameraId, state, occurredAt, source: "DEVICE", topic}` |
| `device.offline` | `{messageId, type: "DeviceOffline", cameraId, occurredAt, reason, source: "EVENTS"\|"MONITOR"}` |
| `device.online` | `{messageId, type: "DeviceOnline", cameraId, occurredAt, reason, source: "EVENTS"\|"MONITOR"}` |

`motion.detected` chỉ được phát khi trạng thái chuyển động thay đổi. Trạng thái "không chuyển động" ban đầu không được phát. Theo dõi online/offline chỉ báo OFFLINE sau 2 lần kiểm tra thất bại liên tiếp. Sau mỗi lần `PUT`, lần kiểm tra có kết luận đầu tiên luôn phát trạng thái hiện tại. Khi RabbitMQ mất kết nối, thông điệp được giữ trong hàng đợi bộ nhớ (tối đa 10.000) và Gateway tự kết nối lại.

## mTLS (staging/production)

Không cần sửa code. Chạy uvicorn với chứng chỉ máy chủ và CA dùng để ký chứng chỉ client của Core; `--ssl-cert-reqs 2` (`CERT_REQUIRED`) bắt buộc client trình chứng chỉ:

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8443 \
  --ssl-certfile /run/secrets/gateway.crt \
  --ssl-keyfile /run/secrets/gateway.key \
  --ssl-ca-certs /run/secrets/internal-ca.crt \
  --ssl-cert-reqs 2
```

Phía Core, cấu hình `camhub.device.gateway.ssl-bundle` trỏ tới bundle chứa chứng chỉ client và CA, và `camhub.device.gateway.url=https://device-gateway:8443`. Nên giữ `X-Internal-Token` kể cả khi đã bật mTLS. Healthcheck trong Dockerfile đang gọi `http://localhost:8000`, nên khi bật mTLS cần đổi healthcheck (ví dụ thêm một listener HTTP chỉ cho `/health`, hoặc kiểm tra bằng `openssl s_client`).

## Giới hạn hiện tại

- Luồng và bộ mã hóa dùng Media ver10. H.265 chỉ đặt được khi thiết bị khai báo H.265 trong phần mở rộng của GetVideoEncoderConfigurationOptions. Media2 chỉ dùng cho vùng che; `privacyMask` chỉ `true` khi dịch vụ Media2 báo `Mask="true"`.
- Vùng (US-26): tọa độ phía Core chuẩn hóa 0..1, gốc trên-trái; Gateway đổi sang hệ ONVIF −1..1 (trục y hướng lên). Lưới ô dùng mô-đun `CellMotionEngine` (lưới `CellLayout`, `Sensitivity`) và quy tắc `CellMotionDetector` (`ActiveCells` nén PackBits). Đa giác dùng quy tắc `MotionRegionDetector` (mỗi đa giác một quy tắc, tên `CamHubMotionRegionN`); khi chuyển sang đa giác, mọi ô của quy tắc lưới bị tắt thay vì xóa quy tắc. Cấu hình phân tích lấy từ `VideoAnalyticsConfiguration` của profile độ phân giải cao nhất. Vùng che dùng VideoSourceConfiguration của profile đó; camera chỉ cho hình chữ nhật (`RectangleOnly`) thì Gateway dùng hình bao.
- `bFrames` chỉ `true` khi cấu hình bộ mã hóa có trường hãng (ví dụ `BFrames`/`BFrameNum` > 0). ONVIF chuẩn không có trường này.
- IrCutFilter `ON` = DAY (kính lọc chắn hồng ngoại, ảnh màu), `OFF` = NIGHT, `AUTO` = AUTO.
- WS-Discovery multicast không ra khỏi Docker bridge network. Khi chạy trong Docker, dùng `targets` (Probe unicast) hoặc `network_mode: host` (chỉ trên Linux).
- `edge_playback` (Profile G) chưa làm.
