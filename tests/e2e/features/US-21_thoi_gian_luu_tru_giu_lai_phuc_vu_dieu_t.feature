# language: vi
# US-21 Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa

  Kịch bản: Ghi đè theo camera
    Giả sử mặc định tổ chức 30 ngày, camera Quầy 1 ghi đè 90 ngày
    Khi job xóa chạy
    Thì video Quầy 1 dưới 90 ngày không bị xóa

  Kịch bản: Giữ lại phục vụ điều tra
    Giả sử đoạn 13:45–14:15 ngày 15/10 được giữ lại
    Khi đoạn đó quá hạn lưu trữ
    Thì đoạn đó không bị xóa cho đến khi bỏ đánh dấu

  Kịch bản: Quyền giữ lại
    Giả sử Operator không có HOLD_EVIDENCE
    Khi mở màn hình Xem lại
    Thì không thấy nút "Giữ lại phục vụ điều tra"
