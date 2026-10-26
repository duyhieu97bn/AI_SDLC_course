# language: vi
# US-04 Trạng thái camera – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Trạng thái camera

  Kịch bản: Cập nhật trạng thái
    Giả sử camera Kho số 7 đang trực tuyến
    Khi camera mất kết nối lúc 14:32
    Thì trong ≤ 60 giây trạng thái chuyển "Mất kết nối" kèm "từ 14:32"

  Kịch bản: Tổng quan theo địa điểm
    Giả sử Kho Bắc Ninh có 7 camera, 2 mất kết nối
    Khi mở trang Tổng quan
    Thì địa điểm hiển thị "5 / 7" trực tuyến
