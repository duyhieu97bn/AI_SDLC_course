# language: vi
# US-17 Xem lại theo dòng thời gian – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xem lại theo dòng thời gian

  Kịch bản: Phát lại nhanh
    Giả sử camera có video ngày 21/10
    Khi Operator chọn 13:58 trên timeline
    Thì video bắt đầu phát trong ≤ 3 giây

  Kịch bản: Không có dữ liệu
    Giả sử khoảng 05:12–05:18 không có ghi hình
    Khi chọn 05:15
    Thì nhảy tới đoạn có dữ liệu gần nhất và thông báo

  Kịch bản: Hiển thị timeline
    Giả sử ngày có đoạn ghi, sự kiện và khoảng trống
    Khi mở timeline 24 giờ
    Thì ba loại hiển thị bằng màu khác nhau
    Và lịch đánh dấu ngày có dữ liệu
