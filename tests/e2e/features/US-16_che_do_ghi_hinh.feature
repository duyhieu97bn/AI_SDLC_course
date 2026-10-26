# language: vi
# US-16 Chế độ ghi hình – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Chế độ ghi hình

  Kịch bản: Ghi theo sự kiện
    Giả sử camera đặt chế độ "Theo sự kiện", trước 10 giây, sau 30 giây
    Khi camera phát hiện chuyển động lúc 23:40:05
    Thì có đoạn ghi từ 23:39:55 đến ít nhất 23:40:35

  Kịch bản: Áp cho nhiều camera
    Giả sử Admin chọn 10 camera
    Khi áp chế độ "Liên tục"
    Thì cả 10 camera hiển thị "REC" trên ô xem trực tiếp
