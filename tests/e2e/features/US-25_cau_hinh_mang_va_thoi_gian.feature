# language: vi
# US-25 Cấu hình mạng và thời gian – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Cấu hình mạng và thời gian

  Kịch bản: Đổi IP
    Giả sử Technician sửa địa chỉ IP
    Khi bấm "Áp dụng"
    Thì hệ thống cảnh báo có thể mất kết nối tạm thời
    Và sau khi đổi thành công, camera được cập nhật địa chỉ mới

  Kịch bản: Lệch giờ
    Giả sử camera lệch 7 giây so với máy chủ
    Khi mở danh sách camera
    Thì camera được đánh dấu lệch giờ
