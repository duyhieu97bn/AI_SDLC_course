# language: vi
# US-01 Dò tìm camera ONVIF – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Dò tìm camera ONVIF

  Kịch bản: Tìm thấy camera
    Giả sử địa điểm Kho Bắc Ninh có 3 camera ONVIF chưa thêm
    Khi Technician bấm "Dò tìm"
    Thì trong ≤ 30 giây danh sách hiển thị IP, hãng, model, MAC của 3 camera

  Kịch bản: Camera đã thêm
    Giả sử camera 10.20.1.11 đã có trong hệ thống
    Khi kết quả dò tìm hiển thị
    Thì camera đó được đánh dấu "Đã thêm" và không chọn được

  Kịch bản: Một camera sai mật khẩu
    Giả sử Technician chọn 3 camera và nhập tài khoản chung
    Khi một camera từ chối đăng nhập
    Thì 2 camera được thêm thành công
    Và camera lỗi hiển thị "Sai mật khẩu" kèm nút nhập lại
