# language: vi
# US-28 Đổi mật khẩu, khởi động lại camera – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Đổi mật khẩu, khởi động lại camera

  Kịch bản: Đổi mật khẩu hàng loạt
    Giả sử 4 camera được chọn
    Khi đổi mật khẩu
    Thì mật khẩu mới được lưu cho các camera đổi thành công
    Và camera đổi thất bại giữ mật khẩu cũ và được báo lỗi

  Kịch bản: Khởi động lại
    Giả sử camera Kho số 7 treo
    Khi Technician xác nhận khởi động lại
    Thì hệ thống theo dõi đến khi camera trực tuyến lại
