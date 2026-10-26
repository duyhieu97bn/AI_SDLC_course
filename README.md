# CamHub

Nền tảng web kết nối camera IP của nhiều hãng: xem trực tiếp, xem lại, cấu hình tập trung.

## Tài liệu

| Bước | Tài liệu |
| --- | --- |
| Yêu cầu nghiệp vụ (ISO/IEC/IEEE 29148) | [requirements/BRD_IP_Camera_Web.md](requirements/BRD_IP_Camera_Web.md) |
| Yêu cầu sản phẩm | [requirements/PRD_IP_Camera_Web.md](requirements/PRD_IP_Camera_Web.md) |
| Class diagram, use case | [requirements/](requirements/) |
| Thiết kế kiến trúc (IEEE 1016) | [requirements/SADD_IP_Camera_Web.md](requirements/SADD_IP_Camera_Web.md) |
| Thiết kế giao diện (HTML tĩnh) | [design/index.html](design/index.html) |
| User story, backlog | [implement/](implement/) |
| Kế hoạch theo phase | [implement/plan/README.md](implement/plan/README.md) |

## Cấu trúc mã nguồn (SADD 3.8)

```text
core/             Core Backend – Java 21, Spring Boot 3, Gradle; modular monolith 11 module + platform
device-gateway/   Python 3.12, FastAPI – giao tiếp camera qua ONVIF
media-worker/     Python + FFmpeg – xuất clip, chuyển mã, chụp ảnh
site-connector/   Python – agent tại địa điểm, đường hầm WSS 443
web/              Vue 3 + Vite + PrimeVue – giao diện web
deploy/           Docker Compose, Nginx, MediaMTX, RabbitMQ, Prometheus, Grafana, Loki
tests/            smoke test, (S1+) e2e
tools/            bộ giả lập RTSP, PoC chuyển mã
docs/             báo cáo PoC và tài liệu kỹ thuật
```

## Chạy môi trường dev

Yêu cầu: Docker Desktop (hoặc Docker Engine có Compose v2), Bash (Git Bash trên Windows), openssl.

```bash
cd deploy
./scripts/dev.sh up        # lần đầu: sinh bí mật + chứng chỉ TLS tự ký, build, chờ mọi dịch vụ healthy
./scripts/dev.sh smoke     # smoke test
./scripts/dev.sh sim 20    # bộ giả lập RTSP phát 20 luồng sim-1..sim-20
```

| Địa chỉ | Nội dung |
| --- | --- |
| https://localhost:8443 | Web App (trang PoC đo luồng video ở S0) |
| http://localhost:3000 | Grafana: tài khoản `admin`, mật khẩu trong `deploy/secrets/grafana_admin_password` |
| http://localhost:9090 | Prometheus |
| http://localhost:15672 | RabbitMQ: tài khoản `camhub`, mật khẩu trong `deploy/secrets/rabbitmq_password` |

Bí mật nằm trong `deploy/secrets/`, chứng chỉ TLS nằm trong `deploy/nginx/certs/`. Cả hai được sinh tự động và không được đưa vào Git.

Sao lưu chạy tự động trong dịch vụ `backup` (pgBackRest: bản đầy đủ 01:00, differential mỗi giờ, WAL liên tục; sao chép MinIO mỗi giờ). Khôi phục và diễn tập: [docs/runbook/restore.md](docs/runbook/restore.md), `cd deploy && bash backup/restore-drill.sh`. Riêng `deploy/secrets/camhub_master_key` phải được cất giữ offline – mất khóa này thì không giải mã được mật khẩu camera.

## Phát triển từng thành phần

```bash
# Core
cd core && ./gradlew test               # unit test + ArchitectureTest
cd core && ./gradlew integrationTest    # Testcontainers: PostgreSQL + RabbitMQ thật (cần Docker)

# Python (mỗi dịch vụ)
cd device-gateway && pip install -e ".[dev]" && ruff check . && mypy && pytest

# Web
cd web && npm ci && npm run dev         # http://localhost:5173, proxy /api và /media sang https://localhost:8443
cd web && npm run lint && npm run typecheck && npm test
```

CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) chạy các bước trên cho mọi pull request, cùng với OWASP Dependency-Check, pip-audit và npm audit.
