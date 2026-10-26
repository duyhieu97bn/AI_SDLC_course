# language: vi
# US-32 Phân quyền theo vai trò và phạm vi – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Phân quyền theo vai trò và phạm vi

  Kịch bản: Phạm vi theo địa điểm
    Giả sử Lan là Operator phạm vi Kho Bắc Ninh
    Khi Lan mở danh sách camera
    Thì chỉ thấy camera của Kho Bắc Ninh

  Kịch bản: Thu hồi quyền có hiệu lực nhanh
    Giả sử Lan đang xem camera Cổng sau
    Khi Admin gỡ quyền của Lan trên Kho Bắc Ninh
    Thì trong ≤ 60 giây ô xem dừng và hiển thị "Không có quyền"

  Kịch bản: Site Manager tạo người dùng
    Giả sử Hương là Site Manager của Chi nhánh Hà Nội
    Khi Hương tạo người dùng mới và chọn phạm vi
    Thì chỉ chọn được phạm vi trong Chi nhánh Hà Nội
    Và không chọn được vai trò Admin
