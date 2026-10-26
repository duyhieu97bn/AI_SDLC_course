# Kiểm thử tải (TS-08)

Mô phỏng Gatling (Java DSL) cho API Core: 100 Operator đồng thời trong 1 giờ (NFR-03). Kịch bản và nhịp gọi mô tả ở
đầu [ApiLoadSimulation.java](src/gatling/java/camhub/load/ApiLoadSimulation.java).

```bash
./deploy/scripts/dev.sh up            # stack đang chạy (profile sim)
./tests/load/run.sh                   # 100 người dùng, 60 phút
./tests/load/run.sh -Dusers=20 -Dminutes=5 -DrampSeconds=30
```

- Lần đầu tạo 100 tài khoản `loadtest-NNN@camhub.local` (Operator, toàn Tổ chức) bằng API quản trị; chạy lại thì dùng
  lại tài khoản cũ.
- Mặc định gọi thẳng `http://core:8080`, vì Nginx giới hạn 30 yêu cầu/giây mỗi IP, mà mọi người dùng ảo cùng một IP.
  Muốn đo cả Nginx thì thêm `-DbaseUrl=https://nginx` và nới `limit_req` cho IP máy chạy tải.
- Ngưỡng đạt (assertion): lỗi < 1%, P95 mọi yêu cầu < 500 ms, P95 cấp phiên xem trực tiếp < 300 ms.
- Phần video (first frame, 500 luồng ghi) đo bằng `tools/poc-bench` và bộ giả lập `tools/rtsp-sim`; xem
  [docs/test/load-report.md](../../docs/test/load-report.md).
