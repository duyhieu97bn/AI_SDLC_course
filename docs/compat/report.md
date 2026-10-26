# Ma trận tương thích thiết bị (TS-05.4)

Sinh tự động bởi `device-gateway/tests/compat` lúc 2026-10-26 14:29 UTC. Không sửa tay.

Ký hiệu: **ĐẠT**, **LỖI**, **KHÔNG HỖ TRỢ** (thiết bị không khai báo năng lực), **BỎ QUA** (không kết nối được, thiếu tài khoản, hoặc phép thử chỉ chạy với bộ giả lập).

| Thiết bị | Loại | Model / firmware | Thông tin | Profile + RTSP | Ảnh chụp | PTZ | Preset | Hình ảnh | Bộ mã hóa | Mạng/NTP | Sự kiện chuyển động |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ONVIF sim (PTZ, B-frame, lệch giờ) | Giả lập | SIM-PTZ / 1.0.0 | ĐẠT | ĐẠT | ĐẠT | ĐẠT | ĐẠT | ĐẠT | ĐẠT | ĐẠT | ĐẠT |
| ONVIF sim (cố định, MotionAlarm) | Giả lập | SIM-FIX / 1.0.0 | ĐẠT | ĐẠT | ĐẠT | KHÔNG HỖ TRỢ | KHÔNG HỖ TRỢ | ĐẠT | ĐẠT | ĐẠT | ĐẠT |
| Hikvision DS-2CD2143G2-I | Camera thật | — | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA |
| Dahua IPC-HFW2431S | Camera thật | — | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA |
| Hikvision PTZ DS-2DE4425IW | Camera thật | — | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA | BỎ QUA |

## Ghi chú

- **Hikvision DS-2CD2143G2-I**: thiếu biến môi trường COMPAT_HIK_USER/COMPAT_HIK_PASSWORD
- **Dahua IPC-HFW2431S**: thiếu biến môi trường COMPAT_DAHUA_USER/COMPAT_DAHUA_PASSWORD
- **Hikvision PTZ DS-2DE4425IW**: thiếu biến môi trường COMPAT_HIKPTZ_USER/COMPAT_HIKPTZ_PASSWORD
