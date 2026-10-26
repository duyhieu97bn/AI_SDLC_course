# language: vi
# US-20 Xuất clip – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xuất clip

  Kịch bản: Xuất thành công
    Giả sử Site Manager có EXPORT_VIDEO
    Khi chọn 13:58–14:03 và bấm "Xuất"
    Thì nhận thông báo khi clip sẵn sàng
    Và file MP4 phát được bằng trình phát phổ biến
    Và nhật ký ghi EXPORT_VIDEO

  Kịch bản: Quá 60 phút
    Giả sử khoảng chọn dài 75 phút
    Khi bấm "Xuất"
    Thì hệ thống yêu cầu rút ngắn và không tạo công việc xuất

  Kịch bản: Không có quyền
    Giả sử Operator không có EXPORT_VIDEO
    Khi mở màn hình Xem lại
    Thì không thấy nút "Xuất clip"

  Kịch bản: Liên kết hết hạn
    Giả sử clip READY từ 25 giờ trước
    Khi người dùng mở liên kết tải
    Thì trạng thái "Hết hạn" và phải xuất lại
