"""Phân loại profile MAIN/SUB, phát hiện B-frame và đọc tùy chọn bộ mã hóa."""

from __future__ import annotations

from app.capabilities.service import classify_profiles
from app.onvif.client import detect_b_frames, parse_encoder, parse_encoder_options
from app.onvif.soap import parse_xml

TT = 'xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:v="urn:vendor"'


def test_highest_is_main_second_distinct_is_sub() -> None:
    kinds = classify_profiles([("p1", 640, 360), ("p2", 1920, 1080), ("p3", 1280, 720)])
    assert kinds == {"p2": "MAIN", "p3": "SUB", "p1": "OTHER"}


def test_duplicate_resolution_goes_to_other() -> None:
    kinds = classify_profiles([("a", 1920, 1080), ("b", 1920, 1080), ("c", 640, 480)])
    assert kinds == {"a": "MAIN", "b": "OTHER", "c": "SUB"}


def test_single_and_unknown_resolution() -> None:
    assert classify_profiles([("only", 1280, 720)]) == {"only": "MAIN"}
    assert classify_profiles([("x", 0, 0), ("y", 640, 360)]) == {"x": "OTHER", "y": "MAIN"}
    assert classify_profiles([]) == {}


def _encoder(extra: str = "", profile: str = "High") -> str:
    return (
        f'<tt:VideoEncoderConfiguration token="v1" {TT}><tt:Name>Main</tt:Name><tt:Encoding>H264</tt:Encoding>'
        "<tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1440</tt:Height></tt:Resolution>"
        "<tt:RateControl><tt:FrameRateLimit>20</tt:FrameRateLimit><tt:BitrateLimit>6144</tt:BitrateLimit>"
        f"</tt:RateControl><tt:H264><tt:GovLength>40</tt:GovLength><tt:H264Profile>{profile}</tt:H264Profile>"
        f"</tt:H264>{extra}</tt:VideoEncoderConfiguration>"
    )


def test_b_frames_only_when_explicit() -> None:
    # H264 High không đủ để kết luận có B-frame
    assert detect_b_frames(parse_xml(_encoder().encode())) is False
    assert detect_b_frames(parse_xml(_encoder("<tt:Extension><v:BFrameNum>2</v:BFrameNum></tt:Extension>").encode()))
    assert not detect_b_frames(parse_xml(_encoder("<tt:Extension><v:BFrames>0</v:BFrames></tt:Extension>").encode()))
    assert detect_b_frames(parse_xml(_encoder('<tt:Extension v:b_frame="true"/>').encode()))


def test_parse_encoder_fields() -> None:
    enc = parse_encoder(parse_xml(_encoder().encode()))
    assert (enc.token, enc.codec, enc.width, enc.height, enc.fps, enc.bitrate_kbps, enc.gov_length) == (
        "v1",
        "H264",
        2560,
        1440,
        20,
        6144,
        40,
    )


def test_encoder_options_with_vendor_h265() -> None:
    xml = (
        f"<trt:Options xmlns:trt='http://www.onvif.org/ver10/media/wsdl' {TT}>"
        "<tt:QualityRange><tt:Min>0</tt:Min><tt:Max>100</tt:Max></tt:QualityRange>"
        "<tt:H264><tt:ResolutionsAvailable><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height>"
        "</tt:ResolutionsAvailable><tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>400</tt:Max></tt:GovLengthRange>"
        "<tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>30</tt:Max></tt:FrameRateRange>"
        "<tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported></tt:H264>"
        "<tt:Extension><tt:H264><tt:BitrateRange><tt:Min>32</tt:Min><tt:Max>16384</tt:Max></tt:BitrateRange>"
        "</tt:H264><v:H265/></tt:Extension></trt:Options>"
    )
    opts = parse_encoder_options(parse_xml(xml.encode()))
    assert opts.codecs == ["H264", "H265"]
    assert [(r.width, r.height) for r in opts.resolutions] == [(1920, 1080)]
    assert opts.bitrate_range is not None and opts.bitrate_range.max == 16384
    assert opts.gov_length_range is not None and opts.gov_length_range.max == 400
    assert opts.h264_profiles == ["Main"]
