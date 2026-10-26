# language: vi
# US-40 Sao lưu và khôi phục – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Sao lưu và khôi phục

  Kịch bản: Sao lưu định kỳ
    Giả sử hệ thống đang chạy
    Khi mỗi giờ
    Thì có bản sao lưu mới và giữ ≥ 30 bản

  Kịch bản: Diễn tập khôi phục
    Giả sử máy chủ ứng dụng bị hỏng
    Khi đội vận hành IT làm theo runbook
    Thì hệ thống hoạt động lại trong ≤ 4 giờ, mất tối đa 1 giờ dữ liệu

  Kịch bản: Admin không khôi phục được
    Giả sử Admin của tổ chức
    Khi mở tab Sao lưu
    Thì chỉ thấy trạng thái, không có nút khôi phục
