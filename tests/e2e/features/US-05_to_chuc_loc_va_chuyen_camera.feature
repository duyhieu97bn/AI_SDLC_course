# language: vi
# US-05 Tổ chức, lọc và chuyển camera – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Tổ chức, lọc và chuyển camera

  Kịch bản: Lọc nhanh
    Giả sử tổ chức có 500 camera
    Khi lọc theo trạng thái "Mất kết nối" và hãng "Dahua"
    Thì kết quả hiển thị trong ≤ 1 giây

  Kịch bản: Một camera một khu vực
    Giả sử camera thuộc khu vực Kho A
    Khi kéo camera sang Kho B
    Thì camera chỉ còn thuộc Kho B và vẫn giữ các nhóm đã gán

  Kịch bản: Chuyển camera giữ video cũ
    Giả sử camera có video 30 ngày ở Tầng 1
    Khi chuyển camera sang Tầng 2
    Thì toàn bộ video, cảnh báo, đánh dấu giữ lại vẫn còn
    Và người có quyền trên Tầng 2 xem được video cũ
    Và nhật ký ghi MOVE_CAMERA
