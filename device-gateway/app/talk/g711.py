"""Mã hóa G.711 (ITU-T) từ PCM 16 bit: μ-law (PCMU) và A-law (PCMA), bảng tra dựng sẵn cho mọi mẫu int16."""

from __future__ import annotations

import sys
from array import array

_BIAS = 0x84
_CLIP = 32635
_ALAW_SEG_END = (0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)


def ulaw(sample: int) -> int:
    sign = 0x80 if sample < 0 else 0
    magnitude = min(-sample if sample < 0 else sample, _CLIP) + _BIAS
    exponent = max((magnitude >> 7).bit_length() - 1, 0)
    mantissa = (magnitude >> (exponent + 3)) & 0x0F
    return ~(sign | (exponent << 4) | mantissa) & 0xFF


def alaw(sample: int) -> int:
    pcm = sample >> 3
    if pcm >= 0:
        mask = 0xD5
    else:
        mask = 0x55
        pcm = -pcm - 1
    seg = next((i for i, end in enumerate(_ALAW_SEG_END) if pcm <= end), 8)
    if seg >= 8:
        return 0x7F ^ mask
    value = seg << 4
    value |= (pcm >> 1) & 0x0F if seg < 2 else (pcm >> seg) & 0x0F
    return value ^ mask


# Chỉ số bảng là mẫu int16 cộng 32768
_ULAW = bytes(ulaw(s) for s in range(-32768, 32768))
_ALAW = bytes(alaw(s) for s in range(-32768, 32768))


def encode(pcm16le: bytes, codec: str) -> bytes:
    """``pcm16le``: PCM 16 bit little-endian, mono; ``codec``: ``PCMU`` hoặc ``PCMA``."""
    samples = array("h")
    samples.frombytes(pcm16le[: len(pcm16le) - len(pcm16le) % 2])
    if sys.byteorder == "big":
        samples.byteswap()
    table = _ULAW if codec == "PCMU" else _ALAW
    return bytes(table[s + 32768] for s in samples)
