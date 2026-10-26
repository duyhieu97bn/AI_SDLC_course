# language: vi
# US-06 Kích hoạt bộ kết nối địa điểm – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Kích hoạt bộ kết nối địa điểm

  Kịch bản: Kích hoạt thành công
    Giả sử Admin tạo bộ kết nối cho Chi nhánh Đà Nẵng
    Khi Connector chạy với mã kích hoạt trong 24 giờ
    Thì trạng thái chuyển "Trực tuyến" và hiển thị thời điểm liên lạc gần nhất

  Kịch bản: Mã hết hạn
    Giả sử mã kích hoạt tạo đã 25 giờ
    Khi Connector dùng mã đó
    Thì kích hoạt bị từ chối và Admin phải tạo mã mới
