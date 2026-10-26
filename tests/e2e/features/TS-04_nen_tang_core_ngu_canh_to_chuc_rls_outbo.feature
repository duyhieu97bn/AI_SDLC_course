# language: vi
# TS-04 Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Nền tảng Core: ngữ cảnh Tổ chức, RLS, outbox, mã hóa, sự kiện nhật ký

  Kịch bản: Cách ly dữ liệu theo Tổ chức
    Giả sử 2 Tổ chức A và B có dữ liệu camera
    Khi một truy vấn chạy với ngữ cảnh Tổ chức A
    Thì chỉ trả về camera của A, kể cả khi truy vấn quên lọc organization_id

  Kịch bản: Không mất sự kiện khi broker lỗi
    Giả sử RabbitMQ đang dừng
    Khi một giao dịch nghiệp vụ ghi sự kiện vào outbox
    Thì sự kiện được gửi đi khi RabbitMQ hoạt động lại
