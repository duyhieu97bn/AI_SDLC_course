# language: vi
# US-11 Tự chuyển luồng theo băng thông – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Tự chuyển luồng theo băng thông

  Kịch bản: Tự hạ chất lượng
    Giả sử ô đang phát luồng chính 4 Mbps ở chế độ Tự động
    Khi băng thông đo được dưới 4 Mbps trong 5 giây liên tục
    Thì ô chuyển sang luồng phụ và hiển thị "Chất lượng thấp"

  Kịch bản: Chế độ thủ công
    Giả sử người dùng chọn "Cao" cho một ô
    Khi băng thông giảm
    Thì ô đó không tự chuyển luồng
