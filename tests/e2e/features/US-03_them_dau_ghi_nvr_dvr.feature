# language: vi
# US-03 Thêm đầu ghi NVR/DVR – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Thêm đầu ghi NVR/DVR

  Kịch bản: Chọn kênh
    Giả sử đầu ghi 16 kênh, 3 kênh có tín hiệu
    Khi Technician thêm đầu ghi và chọn 3 kênh
    Thì 3 camera được tạo với tên kênh lấy từ đầu ghi

  Kịch bản: Đầu ghi mất kết nối
    Giả sử đầu ghi đang có 3 kênh
    Khi đầu ghi mất kết nối
    Thì cả 3 camera chuyển trạng thái "Mất kết nối"
