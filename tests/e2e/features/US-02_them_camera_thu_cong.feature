# language: vi
# US-02 Thêm camera thủ công – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Thêm camera thủ công

  Kịch bản: Kiểm tra kết nối thành công
    Giả sử Technician nhập IP, cổng, tài khoản đúng
    Khi bấm "Kiểm tra kết nối"
    Thì trong ≤ 10 giây hiển thị khung hình xem trước
    Và nút "Lưu camera" được bật

  Kịch bản: Kiểm tra kết nối thất bại
    Giả sử địa chỉ IP không tới được
    Khi bấm "Kiểm tra kết nối"
    Thì hiển thị lỗi "Không tới được địa chỉ"
    Và chỉ lưu được khi tick "Lưu dù chưa kết nối"
