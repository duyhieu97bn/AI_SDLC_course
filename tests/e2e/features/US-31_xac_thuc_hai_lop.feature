# language: vi
# US-31 Xác thực hai lớp – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xác thực hai lớp

  Kịch bản: Bắt buộc 2FA
    Giả sử Admin bật bắt buộc 2FA cho vai trò Technician
    Khi Minh (Technician) đăng nhập lần kế tiếp
    Thì Minh phải thiết lập 2FA trước khi vào hệ thống

  Kịch bản: Mã dự phòng
    Giả sử người dùng mất điện thoại
    Khi nhập một mã dự phòng hợp lệ
    Thì đăng nhập thành công và mã đó không dùng lại được
