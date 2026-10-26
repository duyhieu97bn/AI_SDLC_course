# language: vi
# TS-01 Khung dự án và CI/CD – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Khung dự án và CI/CD

  Kịch bản: Pipeline chạy khi có pull request
    Giả sử một pull request vào nhánh main
    Khi pipeline CI chạy
    Thì build, unit test, lint và kiểm tra kiến trúc của cả 4 thành phần đều chạy
    Và pull request bị chặn nếu một bước thất bại

  Kịch bản: Phát hiện phụ thuộc vòng giữa module
    Giả sử module recording gọi trực tiếp repository của module device
    Khi kiểm tra kiến trúc chạy
    Thì build thất bại và chỉ ra vi phạm
