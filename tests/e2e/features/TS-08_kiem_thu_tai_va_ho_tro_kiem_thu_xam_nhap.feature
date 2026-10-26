# language: vi
# TS-08 Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)
Tính năng: Kiểm thử tải và hỗ trợ kiểm thử xâm nhập trước GA

  Kịch bản: Đạt NFR dưới tải
    Giả sử 500 camera và 100 người dùng đồng thời
    Khi chạy kịch bản tải 1 giờ
    Thì NFR-01, NFR-02 đạt ở phân vị 95

  Kịch bản: Không còn lỗi bảo mật nghiêm trọng
    Giả sử báo cáo kiểm thử xâm nhập
    Khi review trước GA
    Thì không còn lỗi mức nghiêm trọng/cao chưa xử lý
