# language: vi
# US-37 Xử lý và chuyển giao cảnh báo – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Xử lý và chuyển giao cảnh báo

  Kịch bản: Nhận xử lý
    Giả sử cảnh báo ở trạng thái Mới
    Khi Lan bấm "Nhận xử lý"
    Thì trạng thái Đang xử lý, phụ trách là Lan

  Kịch bản: Chuyển giao
    Giả sử Lan đang xử lý cảnh báo
    Khi Lan chuyển giao cho Minh kèm lý do
    Thì Minh thành người phụ trách và nhận thông báo
    Và lịch sử ghi lần chuyển giao

  Kịch bản: Đóng bắt buộc kết quả
    Giả sử cảnh báo đang xử lý
    Khi đóng mà không chọn kết quả
    Thì hệ thống không cho đóng
