"""Mã hóa ``ActiveCells`` của quy tắc ``tt:CellMotionDetector``: chuỗi bit theo hàng (bit cao trước), nén PackBits
rồi Base64 (ONVIF Analytics Service, mục Cell Motion Detector)."""

from __future__ import annotations

import base64
import binascii


def packbits_encode(data: bytes) -> bytes:
    out = bytearray()
    i, n = 0, len(data)
    while i < n:
        j = i
        while j + 1 < n and data[j + 1] == data[i] and j - i < 127:
            j += 1
        if j > i:
            out += bytes((257 - (j - i + 1), data[i]))
            i = j + 1
            continue
        start = i
        while i < n and i - start < 128 and not (i + 1 < n and data[i + 1] == data[i]):
            i += 1
        out.append(i - start - 1)
        out += data[start:i]
    return bytes(out)


def packbits_decode(data: bytes) -> bytes:
    out = bytearray()
    i, n = 0, len(data)
    while i < n:
        header = data[i]
        i += 1
        if header < 128:
            if i + header + 1 > n:
                raise ValueError("Khối literal PackBits bị cắt")
            out += data[i : i + header + 1]
            i += header + 1
        elif header > 128:
            if i >= n:
                raise ValueError("Khối lặp PackBits bị cắt")
            out += bytes((data[i],)) * (257 - header)
            i += 1
    return bytes(out)


def encode_active_cells(bits: list[bool]) -> str:
    raw = bytearray((len(bits) + 7) // 8)
    for index, on in enumerate(bits):
        if on:
            raw[index // 8] |= 0x80 >> (index % 8)
    return base64.b64encode(packbits_encode(bytes(raw))).decode("ascii")


def decode_active_cells(value: str, count: int) -> list[bool]:
    """Ném ``ValueError`` khi không phải Base64/PackBits hợp lệ hoặc thiếu bit so với số ô của lưới."""
    try:
        raw = packbits_decode(base64.b64decode(value.strip(), validate=True))
    except binascii.Error as e:
        raise ValueError("ActiveCells không phải Base64") from e
    if len(raw) * 8 < count:
        raise ValueError(f"ActiveCells chỉ có {len(raw) * 8} bit, lưới cần {count}")
    return [bool(raw[i // 8] & (0x80 >> (i % 8))) for i in range(count)]
