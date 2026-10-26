# language: vi
# US-08 Xem trực tiếp trên trình duyệt – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xem trực tiếp trên trình duyệt

  Kịch bản: Phát nhanh
    Giả sử Operator có VIEW_LIVE trên camera Cổng chính
    Khi mở camera trên Chrome, Edge, Firefox hoặc Safari
    Thì hình hiển thị trong ≤ 3 giây không cần plugin
    Và độ trễ ≤ 2 giây trong LAN

  Kịch bản: Không có quyền
    Giả sử Viewer không có quyền trên camera Kho A – Lối 1
    Khi gọi API tạo phiên xem cho camera đó
    Thì API trả 403 và MediaMTX không cấp luồng

  Kịch bản: Mạng chặn WebRTC
    Giả sử mạng của người dùng chặn UDP
    Khi mở xem trực tiếp
    Thì trình duyệt tự chuyển sang HLS
