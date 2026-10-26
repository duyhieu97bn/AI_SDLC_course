# language: vi
# US-09 Lưới nhiều camera – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Lưới nhiều camera

  Kịch bản: Đổi lưới
    Giả sử Operator đang xem lưới 4
    Khi chọn lưới 9 và kéo 9 camera vào
    Thì 9 ô phát luồng phụ

  Kịch bản: Phóng to một ô
    Giả sử lưới 9 đang phát
    Khi nhấp đúp ô Bãi xe
    Thì ô đó phóng to và chuyển sang luồng chính

  Kịch bản: Camera mất kết nối trong lưới
    Giả sử một camera trong lưới mất kết nối
    Khi lưới hiển thị
    Thì ô hiển thị "Mất kết nối" kèm thời điểm thay vì màn hình đen
