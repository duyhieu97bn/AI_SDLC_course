# language: vi
# US-33 Nhật ký thao tác – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Nhật ký thao tác

  Kịch bản: Tra cứu
    Giả sử có thao tác xuất clip lúc 18:05
    Khi Admin lọc loại EXPORT_VIDEO ngày 21/10
    Thì thấy bản ghi gồm người, thời điểm, IP, đối tượng, kết quả

  Kịch bản: Không sửa được
    Giả sử một bản ghi nhật ký
    Khi bất kỳ vai trò nào cố sửa hoặc xóa (kể cả qua DB bằng tài khoản ứng dụng)
    Thì thao tác bị từ chối
