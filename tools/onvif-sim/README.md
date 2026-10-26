# Bộ giả lập camera ONVIF (TS-05.4)

Giả lập một camera IP ONVIF (Profile S/T rút gọn) để phát triển và kiểm thử Device Gateway khi chưa có đủ camera thật. Trạng thái nằm trong bộ nhớ: khởi động lại thì mọi thay đổi (preset, hình ảnh, mật khẩu…) trở về mặc định.

Bộ giả lập hỗ trợ:

| Dịch vụ | Thao tác |
| --- | --- |
| Device (`/onvif/device_service`) | GetSystemDateAndTime (không cần xác thực), SetSystemDateAndTime, GetDeviceInformation, GetServices, GetCapabilities, GetNetworkInterfaces, GetHostname, GetNTP, SetNTP, SystemReboot, GetUsers, SetUser |
| Media ver10 (`/onvif/media_service`) | GetProfiles (MainStream 1920x1080, SubStream 640x360), GetVideoSources, GetStreamUri, GetSnapshotUri, GetVideoEncoderConfiguration(s), GetVideoEncoderConfigurationOptions (có H.265 ở phần mở rộng), SetVideoEncoderConfiguration |
| PTZ ver20 (`/onvif/ptz_service`, khi `SIM_PTZ=true`) | ContinuousMove, Stop, GetPresets, GotoPreset, SetPreset, RemovePreset |
| Imaging ver20 (`/onvif/imaging_service`) | GetImagingSettings, SetImagingSettings, GetOptions |
| Events (`/onvif/events_service`) | GetEventProperties, CreatePullPointSubscription, PullMessages, Renew, Unsubscribe |
| Search (`/onvif/search_service`, Profile G) | GetServiceCapabilities, GetRecordingSummary, GetRecordingInformation, FindRecordings, GetRecordingSearchResults, FindEvents (`tns1:RecordingHistory/Track/State`), GetEventSearchResults, EndSearch |
| Recording (`/onvif/recording_service`, Profile G) | GetServiceCapabilities, GetRecordings, GetRecordingJobs |
| Replay (`/onvif/replay_service`, Profile G) | GetServiceCapabilities, GetReplayUri |
| WS-Discovery (UDP 3702) | Trả ProbeMatches cho Probe multicast `239.255.255.250` và unicast |

Mọi thao tác, trừ GetSystemDateAndTime, đều yêu cầu WS-Security UsernameToken. Bộ giả lập kiểm tra PasswordDigest, độ lệch `Created` (tối đa 5 phút so với đồng hồ giả lập) và chặn nonce dùng lại. Sai tài khoản thì trả SOAP Fault `ter:NotAuthorized` với HTTP 400, theo ONVIF Core. Device Gateway hiểu cả HTTP 400 kèm Fault lẫn HTTP 401. Thao tác không có trả `ter:ActionNotSupported` (HTTP 500).

Ảnh chụp ở `GET /onvif/snapshot/{profileToken}` là một ảnh JPEG nhỏ nhúng sẵn, dùng HTTP Basic.

## Cấu hình (biến môi trường)

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `SIM_NAME` | `CamHub Sim` | Tên (scope `name/`) |
| `SIM_MANUFACTURER` | `CamHub` | Hãng |
| `SIM_MODEL` | `SIM-1000` | Model (scope `hardware/`) |
| `SIM_MAC` | `02:00:00:00:00:01` | Địa chỉ MAC |
| `SIM_USER` / `SIM_PASSWORD` | `admin` / `admin123` | Tài khoản ONVIF |
| `SIM_HTTP_PORT` | `8080` | Cổng HTTP lắng nghe |
| `SIM_ADVERTISE_HOST` | IP card mạng chính | Host đặt trong XAddr, URI ảnh chụp và URI RTSP mặc định |
| `SIM_ADVERTISE_PORT` | `SIM_HTTP_PORT` | Cổng đặt trong XAddr, khi map cổng Docker khác cổng trong container |
| `SIM_RTSP_MAIN` / `SIM_RTSP_SUB` | `rtsp://<host>:554/Streaming/Channels/101` / `…/102` | URI luồng trả trong GetStreamUri |
| `SIM_PTZ` | `true` | Có dịch vụ PTZ hay không |
| `SIM_BFRAMES` | `false` | Luồng chính khai báo B-frame (phần mở rộng `sim:BFrames`) |
| `SIM_DISCOVERY_PORT` | `3702` | Cổng UDP WS-Discovery; `0` để tắt |
| `SIM_CLOCK_OFFSET_SECONDS` | `0` | Cho đồng hồ giả lập lệch (giây, dương là chạy nhanh): kiểm tra bù giờ khi tính digest và cảnh báo lệch giờ (US-25); đổi lúc chạy bằng `POST /sim/clock` |
| `SIM_NTP_SYNC` | `false` | `true`: SetSystemDateAndTime chế độ NTP coi như đồng bộ thành công, độ lệch đồng hồ về 0 |
| `SIM_ZONES` | `true` | Có dịch vụ Analytics (vùng chuyển động: lưới 22×18, tối đa 4 đa giác `MotionRegionDetector`) và Media2 (vùng che: tối đa 4 vùng, mỗi vùng tối đa 8 điểm) |
| `SIM_MOTION_TOPIC` | `cell` | `cell` → `tns1:RuleEngine/CellMotionDetector/Motion` (`IsMotion`); `alarm` → `tns1:VideoSource/MotionAlarm` (`State`) |
| `SIM_CHANNELS` | `0` | > 0 thì giả lập đầu ghi NVR với số kênh này (mỗi kênh một `VideoSource_N`, profile `Profile_N01` 1920x1080 và `Profile_N02` 640x360, tên kênh ở `VideoSourceConfiguration/Name`), kèm Profile G: mỗi kênh có tín hiệu một bản ghi `rec_chNN` (nguồn `VideoSource_N`) |
| `SIM_EDGE_RECORDING` | `false` | Camera đơn: `true` thì có Profile G (Search/Recording/Replay) với một bản ghi `rec_1` của nguồn `vs0` |
| `SIM_EDGE_HOURS` | `24` | Bản ghi phủ từ "bây giờ − N giờ" tới bây giờ (cửa sổ trượt theo đồng hồ giả lập), trừ khoảng trống cố định 02:00–02:30 UTC mỗi ngày |
| `SIM_SIGNAL_CHANNELS` | mọi kênh | Kênh có tín hiệu, ví dụ `1,2,3` (`none` là không kênh nào). Kênh mất tín hiệu trả `VideoSource` với `Framerate` 0 và `Resolution` 0x0 |
| `SIM_CHANNEL_RTSP_MAIN` / `SIM_CHANNEL_RTSP_SUB` | `rtsp://<host>:554/Streaming/Channels/<N>01` / `<N>02` | Danh sách URI (phân cách dấu phẩy) theo thứ tự `SIM_SIGNAL_CHANNELS`; thiếu luồng phụ thì dùng luồng chính |
| `SIM_CHANNEL_NAMES` | `Cổng,Sảnh,Bãi xe,…` | Nhãn kênh theo thứ tự; tên kênh là `Kênh 01 – <nhãn>` |

## Chạy

```bash
# Trực tiếp (Python 3.12+)
cd tools/onvif-sim
pip install .
SIM_ADVERTISE_HOST=127.0.0.1 SIM_PASSWORD=Sim@12345 python -m onvif_sim

# Docker
docker build -t camhub/onvif-sim:dev tools/onvif-sim
docker run --rm -p 8080:8080 -p 3702:3702/udp \
  -e SIM_ADVERTISE_HOST=127.0.0.1 -e SIM_PASSWORD=Sim@12345 \
  -e SIM_RTSP_MAIN=rtsp://127.0.0.1:8554/sim-1 camhub/onvif-sim:dev
```

Trong Docker Compose, đặt `SIM_ADVERTISE_HOST` bằng tên service (ví dụ `onvif-sim-1`) để Gateway gọi được các XAddr. Muốn có luồng video thật thì trỏ `SIM_RTSP_MAIN`/`SIM_RTSP_SUB` tới các luồng `sim-N` mà [rtsp-sim](../rtsp-sim/README.md) phát vào MediaMTX.

## Điều khiển khi thử

```bash
# Phát sự kiện chuyển động cho mọi PullPoint đang mở
curl -X POST localhost:8080/sim/motion -H 'Content-Type: application/json' -d '{"state": true}'
curl -X POST localhost:8080/sim/motion -H 'Content-Type: application/json' -d '{"state": false}'

# Xem trạng thái hiện tại (hình ảnh, bộ mã hóa, preset, NTP, số lần reboot, vùng chuyển động, vùng che…)
curl localhost:8080/sim/state

# Đặt độ lệch đồng hồ camera (giây). SetNetworkInterfaces/SetNetworkDefaultGateway/SetDNS chỉ đổi trạng thái trong bộ nhớ
# (GetNetworkInterfaces báo IP mới, IP thật của container không đổi); SetSystemDateAndTime Manual đặt lại đồng hồ
curl -X POST localhost:8080/sim/clock -H 'Content-Type: application/json' -d '{"offsetSeconds": 7}'

# Đầu ghi: bật/tắt tín hiệu một kênh
curl -X POST localhost:8080/sim/channels/7/signal -H 'Content-Type: application/json' -d '{"signal": true}'
```

`/sim/*` không yêu cầu xác thực. Chỉ dùng bộ giả lập trong mạng dev/test.

## Giới hạn

- Không phát luồng RTSP; chỉ trả URI. Luồng thật lấy từ rtsp-sim/MediaMTX.
- Media2 chỉ có các thao tác vùng che (GetMaskOptions, GetMasks, CreateMask, SetMask, DeleteMask), chỉ kiểu `Color`.
- Analytics chỉ có quy tắc `CellMotionDetector` (1 quy tắc) và `MotionRegionDetector` (4 quy tắc), mô-đun `CellMotionEngine` (độ nhạy). Vùng không ảnh hưởng tới sự kiện chuyển động (vẫn phát qua `/sim/motion`).
- Profile G rút gọn: bản ghi không lưu dữ liệu thật. GetReplayUri trả nguyên URI RTSP luồng chính của camera/kênh (rtsp-sim/MediaMTX phát trực tiếp, không tua theo `starttime`/`Range`), đủ để kiểm tra luồng phát lại đầu-cuối. Tìm kiếm trả hết kết quả trong một trang (`SearchState` = `Completed`).
- WS-Discovery multicast chỉ hoạt động khi mạng cho multicast đi qua. Trên Docker bridge, gói multicast thường không đi từ LAN của máy chủ vào container. Gateway vẫn tìm được bộ giả lập bằng Probe unicast (`targets`).
- Lệnh reboot chỉ tăng bộ đếm, dịch vụ không tạm ngừng.
