# Công cụ sinh backlog và kế hoạch phase

Dữ liệu nằm ở 2 file; mọi tài liệu trong `implement/` được sinh từ đây, không sửa tay các file sinh ra.

| File | Nội dung |
| --- | --- |
| `backlog_data.py` | Epic, sprint, item backlog (story, điểm, sprint, Gherkin), DoR/DoD |
| `plan_data.py` | Task chi tiết theo item, thông tin từng phase (ngày, điều kiện vào/ra, demo, rủi ro) |

Chạy từ thư mục `implement/tools` (cần Python 3.10+ và `openpyxl`):

```bash
python build_backlog.py ..        # sinh User_Stories_IP_Camera_Web.md và Product_Backlog_IP_Camera_Web.xlsx
python build_plan.py ../plan      # sinh plan/README.md và các file phase
```

`build_backlog.py` dừng và báo lỗi nếu một item phụ thuộc item ở sprint sau, hoặc điểm không theo thang Fibonacci.
