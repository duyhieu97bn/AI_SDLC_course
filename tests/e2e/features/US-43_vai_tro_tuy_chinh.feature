# language: vi
# US-43 Vai trò tùy chỉnh – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Vai trò tùy chỉnh

  Kịch bản: Tạo từ vai trò có sẵn
    Giả sử Admin sao chép vai trò Operator
    Khi đặt tên "Bảo vệ ca đêm" và bỏ quyền VIEW_PLAYBACK
    Thì vai trò mới được tạo với các quyền còn lại

  Kịch bản: Không xóa vai trò đang dùng
    Giả sử vai trò "Bảo vệ ca đêm" đang gán cho 4 người
    Khi Admin bấm xóa
    Thì bị từ chối và hiển thị số người đang dùng

  Kịch bản: Vai trò mặc định
    Giả sử vai trò Operator là vai trò hệ thống
    Khi Admin mở vai trò đó
    Thì không sửa, đổi tên hoặc xóa được
