# language: vi
# US-36 Cảnh báo chuyển động đa kênh – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Cảnh báo chuyển động đa kênh

  Kịch bản: Trong khung giờ
    Giả sử quy tắc 22:00–06:00 cho Kho Bắc Ninh, kênh Telegram
    Khi camera Cổng sau báo chuyển động lúc 23:40
    Thì Lan nhận Telegram gồm tên camera, thời điểm, ảnh và liên kết

  Kịch bản: Ngoài khung giờ
    Giả sử cùng quy tắc
    Khi có chuyển động lúc 10:00
    Thì không gửi thông báo

  Kịch bản: Chống spam
    Giả sử chống spam 120 giây
    Khi camera báo chuyển động 3 lần trong 1 phút
    Thì chỉ gửi 1 thông báo; các sự kiện vẫn được đánh dấu trên timeline

  Kịch bản: Camera không hỗ trợ
    Giả sử camera không có chức năng phát hiện chuyển động
    Khi tạo quy tắc chuyển động cho camera đó
    Thì tùy chọn bị ẩn kèm lý do
