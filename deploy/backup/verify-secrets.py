"""Diễn tập khôi phục (TS-08.3): kiểm tra bí mật trong CSDL khôi phục giải mã được bằng camhub_master_key.

Đọc từ stdin các dòng ``loại<TAB>organization_id<TAB>bản mã`` (khóa dữ liệu Tổ chức ``mk1:<base64>`` hoặc bí mật
``v1:<base64>``), mở khóa dữ liệu bằng khóa chính rồi giải mã từng bí mật – cùng định dạng ``CryptoService`` của
Core: AES-256-GCM, IV 12 byte đứng đầu, AAD là organization_id. Chỉ in số lượng, không in giá trị đã giải mã.

    python verify-secrets.py /run/secrets/camhub_master_key [mã khóa chính, mặc định mk1] < secrets.tsv
"""

import base64
import sys
from collections import Counter

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def open_sealed(key: bytes, sealed: bytes, org: str) -> bytes:
    return AESGCM(key).decrypt(sealed[:12], sealed[12:], org.encode("ascii"))


def main() -> int:
    master = base64.b64decode(open(sys.argv[1], encoding="ascii").read().strip())
    key_id = sys.argv[2] if len(sys.argv) > 2 else "mk1"
    rows = [line.rstrip("\n").split("\t") for line in sys.stdin if line.strip()]
    data_keys: dict[str, bytes] = {}
    ok: Counter[str] = Counter()
    failed: Counter[str] = Counter()
    for kind, org, value in (r for r in rows if r[0] == "data_key"):
        prefix, _, payload = value.partition(":")
        try:
            if prefix != key_id:
                raise ValueError(f"khóa chính {prefix}")
            data_keys[org] = open_sealed(master, base64.b64decode(payload), org)
            ok[kind] += 1
        except Exception:  # noqa: BLE001 - mọi lỗi giải mã đều tính là hỏng
            failed[kind] += 1
    for kind, org, value in (r for r in rows if r[0] != "data_key"):
        try:
            if not value.startswith("v1:") or org not in data_keys:
                raise ValueError("định dạng")
            open_sealed(data_keys[org], base64.b64decode(value[3:]), org)
            ok[kind] += 1
        except Exception:  # noqa: BLE001
            failed[kind] += 1
    for kind in sorted(set(ok) | set(failed)):
        print(f"{kind}: {ok[kind]}/{ok[kind] + failed[kind]}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
