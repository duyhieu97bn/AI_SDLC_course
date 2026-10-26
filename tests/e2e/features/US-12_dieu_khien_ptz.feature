# language: vi
# US-12 Điều khiển PTZ – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Điều khiển PTZ

  Kịch bản: Chỉ hiện với camera PTZ
    Giả sử camera Cổng chính không hỗ trợ PTZ
    Khi Operator chọn camera đó
    Thì không hiển thị bảng điều khiển PTZ

  Kịch bản: Tranh chấp điều khiển
    Giả sử Hương (Site Manager) đang điều khiển camera Bãi xe
    Khi Lan (Operator, thứ hạng thấp hơn) gửi lệnh PTZ
    Thì lệnh của Lan bị từ chối
    Và Lan thấy "Đang được điều khiển bởi Hương"
