"""Tách các khối Gherkin trong User_Stories_IP_Camera_Web.md thành tests/e2e/features/<ID>_<tên>.feature.

Chạy: python implement/tools/extract_features.py  (từ gốc repo). Ghi đè file cũ để luôn khớp tài liệu.
"""
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "implement" / "User_Stories_IP_Camera_Web.md"
OUT = ROOT / "tests" / "e2e" / "features"


def slug(text: str) -> str:
    t = unicodedata.normalize("NFD", text.replace("đ", "d").replace("Đ", "D"))
    t = "".join(c for c in t if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"[^a-z0-9]+", "_", t).strip("_")[:40]


def main() -> int:
    md = SRC.read_text(encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    count = 0
    for m in re.finditer(r"^#### ((?:US|TS)-\d+) (.+?)\n(.*?)(?=^#### |^### |\Z)", md, re.S | re.M):
        item_id, title, body = m.group(1), m.group(2).strip(), m.group(3)
        g = re.search(r"```gherkin\n(.*?)```", body, re.S)
        if not g:
            continue
        header = f"# language: vi\n# {item_id} {title} – nguồn: implement/User_Stories_IP_Camera_Web.md (sinh tự động)\n"
        (OUT / f"{item_id}_{slug(title)}.feature").write_text(header + g.group(1), encoding="utf-8")
        count += 1
    print(f"Đã ghi {count} file .feature vào {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
