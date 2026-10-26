# language: vi
# TS-03 PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: PoC MediaMTX: WHEP, xác thực ngoài, ghi hình, chuyển mã

  Kịch bản: Đạt ngưỡng hiệu năng
    Giả sử 50 camera thật và 450 luồng RTSP giả lập
    Khi 20 phiên WHEP mở đồng thời
    Thì first frame trung vị ≤ 3 giây và độ trễ ≤ 2 giây trong LAN
    Và CPU media node ≤ 70%

  Kịch bản: Xác thực từng phiên xem
    Giả sử MediaMTX cấu hình xác thực HTTP tới Core
    Khi trình duyệt gửi WHEP offer với token sai
    Thì MediaMTX từ chối phiên xem
