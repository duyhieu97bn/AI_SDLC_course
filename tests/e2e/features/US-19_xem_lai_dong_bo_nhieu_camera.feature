# language: vi
# US-19 Xem lại đồng bộ nhiều camera – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xem lại đồng bộ nhiều camera

  Kịch bản: Đồng bộ 4 camera
    Giả sử Operator chọn 4 camera vào chế độ đồng bộ
    Khi chọn 14:00 trên timeline chung
    Thì cả 4 camera phát từ 14:00, lệch nhau ≤ 1 giây

  Kịch bản: Giới hạn 4 camera
    Giả sử đã có 4 camera đồng bộ
    Khi chọn thêm camera thứ 5
    Thì hệ thống không cho thêm và thông báo giới hạn
