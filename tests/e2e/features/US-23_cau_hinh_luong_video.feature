# language: vi
# US-23 Cấu hình luồng video – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Cấu hình luồng video

  Kịch bản: Áp dụng thành công
    Giả sử camera trực tuyến
    Khi Technician đổi bitrate luồng chính và bấm "Áp dụng"
    Thì trong ≤ 15 giây hiển thị "Đã áp dụng" cho tham số đó

  Kịch bản: Áp dụng một phần
    Giả sử Technician đổi 3 tham số
    Khi camera từ chối 1 tham số
    Thì 2 tham số được áp dụng
    Và tham số bị từ chối hiển thị thông báo lỗi do camera trả về
