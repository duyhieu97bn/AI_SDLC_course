# language: vi
# US-30 Đăng nhập tài khoản cá nhân – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Đăng nhập tài khoản cá nhân

  Kịch bản: Đăng nhập thành công
    Giả sử tài khoản cục bộ hợp lệ
    Khi nhập đúng email và mật khẩu
    Thì vào trang mặc định theo vai trò
    Và nhật ký ghi LOGIN

  Kịch bản: Khóa tài khoản
    Giả sử người dùng đã sai mật khẩu 4 lần liên tiếp
    Khi sai lần thứ 5
    Thì tài khoản bị khóa và nhật ký ghi LOGIN_FAILED

  Kịch bản: Hết phiên
    Giả sử người dùng không thao tác 12 giờ
    Khi gọi một API
    Thì phải đăng nhập lại
