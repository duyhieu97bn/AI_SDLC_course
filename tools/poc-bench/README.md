# Bench first frame (TS-03.6)

Công cụ đo first frame của trang PoC (`/poc/player`) bằng Google Chrome headless. Chrome chạy trong container Playwright, gắn vào mạng `camhub_backend`, nên ICE đi thẳng tới IP nội bộ của MediaMTX.

```bash
./deploy/scripts/dev.sh up && ./deploy/scripts/dev.sh sim 20
BENCH_COUNTS=1,4,9,16,20 BENCH_ROUNDS=3 BENCH_MODE=webrtc ./tools/poc-bench/run.sh
```

Mỗi lượt in ra một dòng JSON gồm số luồng, số luồng phát được, trung bình, P95 và giá trị lớn nhất. Kết quả được ghi vào `docs/poc/results/first-frame-*.jsonl`.

Công cụ dùng Chrome (`channel: 'chrome'`), vì Chromium của Playwright không có bộ giải mã H.264. Lần chạy đầu tiên sẽ cài Chrome vào container, mất khoảng 1 phút.
