"""Sinh implement/plan/README.md và một file cho mỗi phase (sprint) từ backlog_data.py + plan_data.py."""
import sys
from collections import OrderedDict, defaultdict
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from backlog_data import EPICS, ITEMS, SPRINTS  # noqa: E402
from plan_data import PHASES, ROLES, TASKS  # noqa: E402

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
CAP = 30
BY_ID = {i["id"]: i for i in ITEMS}
EPIC = dict(EPICS)
ORDER = [s[0] for s in SPRINTS]

missing = [i["id"] for i in ITEMS if i["id"] not in TASKS]
extra = [k for k in TASKS if k not in BY_ID]
assert not missing and not extra, f"Thiếu task: {missing}; task thừa: {extra}"

COMPONENT = OrderedDict([
    ("core/", "Core Backend (Java, Spring Boot)"), ("device-gateway/", "Device Gateway (Python)"),
    ("media-worker/", "Media Worker (Python + FFmpeg)"), ("site-connector/", "Site Connector (Python)"),
    ("web/", "Web App (Vue 3)"), ("deploy/", "Triển khai (Docker Compose, cấu hình)"), (".github/", "CI/CD"),
    ("tests/", "Kiểm thử"), ("tools/", "Công cụ"), ("docs/", "Tài liệu"),
])


def component_of(path):
    for prefix, name in COMPONENT.items():
        if path.strip().startswith(prefix):
            return name
    return "Khác"


# Nhãn ngắn cho gantt, không chứa dấu phẩy, hai chấm, chấm phẩy (Mermaid hiểu sai các ký tự này)
GANTT_LABEL = {"S0": "PoC và nền tảng", "S1": "Đăng nhập và phân quyền", "S2": "Thêm camera và trạng thái",
               "S3": "Xem trực tiếp và PTZ", "S4": "Ghi hình và xem lại", "S5": "Xuất clip và cảnh báo",
               "S6": "Bộ kết nối và đầu ghi", "S7": "Cảnh báo đa kênh", "S8": "LDAP và pilot", "S9": "Kiểm thử và GA"}


def iso(d):
    return datetime.strptime(d, "%d/%m/%Y").strftime("%Y-%m-%d")


summary = []
for idx, (s, ms, goal) in enumerate(SPRINTS):
    ph = PHASES[s]
    items = [i for i in ITEMS if i["sprint"] == s]
    pts = sum(i["pts"] for i in items)
    role_hours = defaultdict(int)
    files = defaultdict(set)
    md = []
    md.append(f"# Phase {s} – {ph['name']}\n")
    md.append(f"> Sprint **{s}** · Mốc **{ms}** · {ph['start']} – {ph['end']} (2 tuần) · "
              f"Kế hoạch **{pts} / {CAP} điểm**")
    md.append("> Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md) (tiêu chí chấp nhận đầy đủ) · "
              "[Product_Backlog_IP_Camera_Web.xlsx](../Product_Backlog_IP_Camera_Web.xlsx) · [Tổng quan kế hoạch](README.md)\n")

    md.append("## 1. Mục tiêu\n")
    md.append(f"{goal}.\n")

    md.append("## 2. Phạm vi\n")
    md.append("| ID | Tiêu đề | Loại | Epic | Ưu tiên | Điểm | Phụ thuộc |\n| --- | --- | --- | --- | --- | --- | --- |")
    for it in items:
        md.append(f"| {it['id']} | {it['title']} | {it['type']} | {it['epic']} | {it['prio']} | {it['pts']} | {it['deps']} |")
    md.append(f"| | **Tổng** | | | | **{pts}** | |\n")

    md.append("## 3. Lịch thực hiện\n")
    md.append("| Tuần | Nội dung |\n| --- | --- |")
    for w, txt in enumerate(ph["weeks"], start=1):
        md.append(f"| Tuần {w} | {txt} |")
    md.append("")

    md.append("## 4. Task chi tiết\n")
    md.append("Mỗi task gắn với một item; mã task = `<ID item>.<số thứ tự>`. Giờ là ước lượng ban đầu, đội phát triển hiệu chỉnh khi lập kế hoạch sprint.\n")
    for it in items:
        md.append(f"### {it['id']} {it['title']} ({it['pts']} điểm)\n")
        md.append(f"*Là {it['persona']}, tôi muốn {it['want']}, để {it['so']}.*\n")
        md.append(f"Truy vết: yêu cầu {it['br']} · use case {it['uc']} · màn hình {it['screen']} · SADD: {it['sadd']}\n")
        md.append("| Task | Vai trò | Công việc | File / thư mục | Giờ |\n| --- | --- | --- | --- | --- |")
        for n, (role, work, path, hrs) in enumerate(TASKS[it["id"]], start=1):
            md.append(f"| {it['id']}.{n} | {role} | {work} | `{path}` | {hrs} |")
            role_hours[role] += hrs
            for p in path.split(","):
                p = p.strip()
                if p and not p.startswith("("):
                    files[component_of(p)].add(p)
        md.append("")
        md.append("Tiêu chí chấp nhận (kịch bản Gherkin):")
        md += [f"- [ ] {name}" for name, _ in it["scenarios"]]
        md.append("")

    md.append("## 5. File sẽ tạo hoặc sửa\n")
    for comp in list(COMPONENT.values()) + ["Khác"]:
        if files.get(comp):
            md.append(f"**{comp}**\n")
            md += [f"- `{p}`" for p in sorted(files[comp])]
            md.append("")

    total_h = sum(role_hours.values())
    md.append("## 6. Giờ công theo vai trò\n")
    md.append("| Vai trò | Giờ |\n| --- | --- |")
    for r in ROLES:
        if role_hours.get(r):
            md.append(f"| {r} – {ROLES[r]} | {role_hours[r]} |")
    md.append(f"| **Tổng** | **{total_h}** |\n")

    md.append("## 7. Điều kiện vào\n")
    md += [f"- [ ] {x}" for x in ph["entry"]]
    md.append("\n## 8. Điều kiện ra\n")
    md += [f"- [ ] {x}" for x in ph["exit"]]
    md.append("- [ ] Mọi item trong phase đạt Definition of Done (xem User_Stories mục 2)")
    md.append("\n## 9. Demo cuối phase\n")
    md += [f"{n}. {x}" for n, x in enumerate(ph["demo"], start=1)]
    md.append("\n## 10. Rủi ro\n")
    md += [f"- {x}" for x in ph["risks"]]
    if idx + 1 < len(SPRINTS):
        nxt = SPRINTS[idx + 1][0]
        md.append(f"\n## 11. Phase tiếp theo\n\n[Phase {nxt} – {PHASES[nxt]['name']}]({PHASES[nxt]['slug']}.md)")
    (OUT / f"{ph['slug']}.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    summary.append((s, ms, ph, items, pts, total_h))

# ---------- README ----------
r = []
r.append("# Kế hoạch triển khai theo phase – CamHub v1.0\n")
r.append("> Ngày: 23/10/2026 · Phiên bản: 0.1 · Nguồn: [User_Stories_IP_Camera_Web.md](../User_Stories_IP_Camera_Web.md), "
         "[SADD](../../requirements/SADD_IP_Camera_Web.md)\n")
r.append("Mỗi phase là một sprint 2 tuần và có một file riêng. Mỗi file gồm mục tiêu, phạm vi, lịch tuần, task chi tiết theo từng item "
         "(kèm file mã nguồn sẽ tạo/sửa theo cấu trúc SADD mục 3.8), giờ công, điều kiện vào/ra, demo và rủi ro.\n")
r.append("## 1. Danh sách phase\n")
r.append("| Phase | Mốc | Thời gian | Nội dung | Item | Điểm | Giờ task |\n| --- | --- | --- | --- | --- | --- | --- |")
for s, ms, ph, items, pts, h in summary:
    r.append(f"| [{s}]({ph['slug']}.md) | {ms} | {ph['start']} – {ph['end']} | {ph['name']} | {len(items)} | {pts} | {h} |")
r.append(f"| **Tổng** | | 26/10/2026 – 26/03/2027 | | {len(ITEMS)} | **{sum(x[4] for x in summary)}** | **{sum(x[5] for x in summary)}** |\n")

r.append("## 2. Lộ trình\n")
r.append("```mermaid\ngantt\n    title Lộ trình CamHub v1.0\n    dateFormat YYYY-MM-DD\n    axisFormat %d/%m")
cur = None
for s, ms, ph, items, pts, h in summary:
    if ms != cur:
        r.append(f"    section {ms}")
        cur = ms
    r.append(f"    {s} {GANTT_LABEL[s]} :{s.lower()}, {iso(ph['start'])}, {iso(ph['end'])}")
r.append("    section Nghỉ\n    Tết Nguyên đán (dự kiến) :crit, tet, 2027-02-01, 2027-02-12")
r.append("```\n")

r.append("## 3. Quy ước\n")
r.append("- **Ngày bắt đầu:** S0 bắt đầu thứ Hai 26/10/2026. Mỗi sprint 2 tuần, bắt đầu thứ Hai, kết thúc thứ Sáu tuần sau.")
r.append("- **Nghỉ Tết Nguyên đán:** dự kiến 01/02–12/02/2027. S7 lùi sang 15/02/2027, cần xác nhận lịch nghỉ chính thức của tổ chức.")
r.append("- **Đội:** " + "; ".join(f"{k} = {v}" for k, v in ROLES.items()) + ".")
r.append("- **Mã task** `US-30.2` = task thứ 2 của US-30. Mỗi file test Gherkin đặt tại `tests/e2e/features/<ID>_<tên>.feature`.")
r.append("- **Migration** Flyway đặt tên theo sprint: `V<sprint>_<n>__<mô_tả>.sql` (ví dụ `V4_1__recording.sql`).")
r.append("- **Cập nhật:** khi đổi sprint của một item, sửa trong backlog rồi sinh lại các file phase để tránh lệch.\n")

r.append("## 4. Cổng kiểm soát giữa các mốc\n")
r.append("| Cổng | Sau phase | Điều kiện chính |\n| --- | --- | --- |")
r.append("| G0 – Kết luận PoC | S0 | MediaMTX đạt NFR-01 trong PoC; nếu không, quyết định lại ADR-03 trước S1 |")
r.append("| G1 – Alpha | S5 | Toàn bộ 26 story Must chạy trên staging; diễn tập khôi phục đạt |")
r.append("| G2 – Beta | S8 | Toàn bộ story Should; pilot 20–50 camera, ≥ 3 hãng chạy ổn định |")
r.append("| G3 – GA | S9 | Tiêu chí nghiệm thu BRS mục 7; kiểm thử tải và kiểm thử xâm nhập đạt; ký phê duyệt |\n")
r.append("## 5. Câu hỏi mở ảnh hưởng kế hoạch\n")
r.append("- BQ-01: chấp nhận M1 dài 12 tuần (S0–S5)?")
r.append("- BQ-02: thêm S10 cho UAT/sửa lỗi hay chuyển US-15 sang phiên bản sau?")
r.append("- BQ-03: Zalo OA sẵn sàng trước S7?")
r.append("- Lịch nghỉ Tết Nguyên đán 2027 chính thức của tổ chức.")
(OUT / "README.md").write_text("\n".join(r) + "\n", encoding="utf-8")

for s, ms, ph, items, pts, h in summary:
    print(s, ph["slug"], pts, h)
