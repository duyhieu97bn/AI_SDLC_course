# language: vi
# US-44 Đăng nhập bằng LDAP/Active Directory – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Đăng nhập bằng LDAP/Active Directory

  Kịch bản: Đăng nhập lần đầu
    Giả sử Hương thuộc nhóm AD CamHub-SiteMgr-HN đã ánh xạ
    Khi Hương đăng nhập bằng tài khoản AD lần đầu
    Thì tài khoản được tạo với vai trò Site Manager phạm vi Chi nhánh Hà Nội

  Kịch bản: Nghỉ việc
    Giả sử tài khoản AD của Quang bị vô hiệu hóa
    Khi đến lần đồng bộ kế tiếp
    Thì tài khoản CamHub của Quang chuyển "Vô hiệu hóa"

  Kịch bản: Máy chủ thư mục lỗi
    Giả sử không kết nối được LDAP/AD
    Khi người dùng thư mục đăng nhập
    Thì thấy thông báo không kết nối được máy chủ thư mục
    Và Admin cục bộ vẫn đăng nhập được
