# language: vi
# US-34 Cảnh báo mất kết nối – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Cảnh báo mất kết nối

  Kịch bản: Sau thời gian chờ
    Giả sử thời gian chờ 5 phút
    Khi camera mất kết nối 6 phút
    Thì tạo cảnh báo CAMERA_OFFLINE và gửi cho người nhận

  Kịch bản: Gộp theo địa điểm
    Giả sử bộ kết nối Kho Bắc Ninh mất kết nối
    Khi 7 camera cùng mất kết nối
    Thì chỉ tạo 1 cảnh báo SITE_OFFLINE

  Kịch bản: Tự phục hồi
    Giả sử cảnh báo mất kết nối đang mở
    Khi camera kết nối lại
    Thì cảnh báo chuyển "Đã phục hồi"
