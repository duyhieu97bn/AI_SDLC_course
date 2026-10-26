"""Vùng phát hiện chuyển động và vùng che riêng tư (US-26).

- Chuyển động dạng lưới ô: mô-đun ``tt:CellMotionEngine`` (độ nhạy ``Sensitivity``, lưới ``tt:CellLayout``) và
  quy tắc ``tt:CellMotionDetector`` (``ActiveCells`` = Base64 của chuỗi bit đã nén PackBits, theo hàng, bit cao trước).
- Chuyển động dạng đa giác: mỗi đa giác là một quy tắc ``tt:MotionRegionDetector`` (ElementItem ``MotionRegion``).
- Vùng che: Media2 ``GetMaskOptions``/``GetMasks``/``CreateMask``/``DeleteMask`` trên VideoSourceConfiguration.

Tọa độ phía Core chuẩn hóa 0..1 gốc trên-trái; phía ONVIF là −1..1 với trục y hướng lên.
"""

from __future__ import annotations

import base64
import binascii
import logging
from dataclasses import dataclass, field
from typing import Final
from xml.etree import ElementTree as ET  # noqa: S405 - chỉ dựng/sửa phần tử đã phân tích an toàn
from xml.etree.ElementTree import Element  # noqa: S405

from app.models import (
    Grid,
    MotionZones,
    MotionZonesSetRequest,
    Point,
    PrivacyMask,
    PrivacyMasks,
    PrivacyMasksSetRequest,
    ZoneMode,
    ZonesConfig,
)
from app.onvif import soap
from app.onvif.client import OnvifClient, ProfileData, polygon_points
from app.onvif.errors import DeviceError, ErrorCode
from app.onvif.soap import attr, local_name

log = logging.getLogger(__name__)

CELL_RULE: Final = "CellMotionDetector"
REGION_RULE: Final = "MotionRegionDetector"
CELL_ENGINE: Final = "CellMotionEngine"
CELL_RULE_NAME: Final = "CamHubCellMotion"
REGION_RULE_PREFIX: Final = "CamHubMotionRegion"
DEFAULT_SENSITIVITY: Final = 50
_AXT = "http://www.onvif.org/ver20/analytics"
# Lỗi kết nối/tài khoản phải báo ngay; lỗi khác ở bước đọc chỉ làm phần đó thành "không hỗ trợ"
_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)


# ---- PackBits và lưới ô -------------------------------------------------------------------------------


def packbits_encode(data: bytes) -> bytes:
    """Nén PackBits (TIFF): chuỗi lặp ≥ 2 byte thành ``(257 − n, byte)``, còn lại là khối literal ≤ 128 byte."""
    out = bytearray()
    i, n = 0, len(data)
    while i < n:
        j = i
        while j + 1 < n and data[j + 1] == data[i] and j - i < 127:
            j += 1
        run = j - i + 1
        if run >= 2:
            out += bytes((257 - run, data[i]))
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
            out += data[i : i + header + 1]
            i += header + 1
        elif header > 128 and i < n:
            out += bytes((data[i],)) * (257 - header)
            i += 1
    return bytes(out)


def encode_cells(cells: list[list[bool]]) -> str:
    bits = [bit for row in cells for bit in row]
    raw = bytearray((len(bits) + 7) // 8)
    for index, on in enumerate(bits):
        if on:
            raw[index // 8] |= 0x80 >> (index % 8)
    return base64.b64encode(packbits_encode(bytes(raw))).decode("ascii")


def decode_cells(active: str | None, columns: int, rows: int) -> list[list[bool]]:
    """Giải ``ActiveCells``; thiếu bit hoặc dữ liệu hỏng thì các ô còn lại coi như tắt."""
    raw = b""
    if active:
        try:
            raw = packbits_decode(base64.b64decode(active.strip(), validate=True))
        except (binascii.Error, ValueError):
            log.info("ActiveCells không giải mã được, coi như mọi ô tắt")

    def bit(index: int) -> bool:
        return index // 8 < len(raw) and bool(raw[index // 8] & (0x80 >> (index % 8)))

    return [[bit(r * columns + c) for c in range(columns)] for r in range(rows)]


# ---- Tọa độ -------------------------------------------------------------------------------------------


def to_onvif(point: Point) -> tuple[float, float]:
    return (round(point.x * 2 - 1, 4), round(1 - point.y * 2, 4))


def from_onvif(x: float, y: float) -> Point:
    def clamp(v: float) -> float:
        return round(min(1.0, max(0.0, v)), 4)

    return Point(x=clamp((x + 1) / 2), y=clamp((1 - y) / 2))


def bounding_box(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    left, right, bottom, top = min(xs), max(xs), min(ys), max(ys)
    return [(left, top), (right, top), (right, bottom), (left, bottom)]


# ---- Phần tử tt:Config (Rule / AnalyticsModule) --------------------------------------------------------


def config_type(el: Element) -> str:
    return soap.strip_prefix(el.get("Type", ""))


def simple_item(el: Element, name: str) -> str | None:
    params = el.find("{*}Parameters")
    if params is None:
        return None
    for item in params.iterfind("{*}SimpleItem"):
        if item.get("Name") == name:
            return item.get("Value")
    return None


def set_simple_item(el: Element, name: str, value: str) -> None:
    """Gán SimpleItem trong ``Parameters``, thêm mới sau SimpleItem cuối nếu chưa có (giữ thứ tự lược đồ)."""
    tt = soap.NAMESPACES["tt"]
    params = el.find("{*}Parameters")
    if params is None:
        params = ET.SubElement(el, f"{{{tt}}}Parameters")
    items = list(params)
    for item in items:
        if local_name(item.tag) == "SimpleItem" and item.get("Name") == name:
            item.set("Value", value)
            return
    index = max((i + 1 for i, c in enumerate(items) if local_name(c.tag) == "SimpleItem"), default=0)
    new = ET.Element(f"{{{tt}}}SimpleItem", {"Name": name, "Value": value})
    params.insert(index, new)


def serialize_config(el: Element, name: str) -> str:
    """Serialize lại phần tử đọc từ thiết bị thành ``tan:<name>``; ``Type`` chuẩn hóa về tiền tố ``tt:``."""
    el.set("Type", "tt:" + config_type(el))
    return soap.serialize_as(el, "tan", name)


def cell_layout(elements: list[Element]) -> Grid | None:
    for el in elements:
        for node in el.iter():
            if local_name(node.tag) != "CellLayout":
                continue
            try:
                columns, rows = int(node.get("Columns", "")), int(node.get("Rows", ""))
            except ValueError:
                continue
            if columns > 0 and rows > 0:
                return Grid(columns=columns, rows=rows)
    return None


def _int_value(raw: str | None) -> int | None:
    try:
        return max(0, min(100, round(float(raw)))) if raw is not None else None
    except ValueError:
        return None


def _points_xml(points: list[tuple[float, float]]) -> str:
    return "".join(f"<tt:Point x={attr(x)} y={attr(y)}/>" for x, y in points)


def cell_rule_xml(active_cells: str) -> str:
    return (
        f'<tan:Rule Name="{CELL_RULE_NAME}" Type="tt:{CELL_RULE}"><tt:Parameters>'
        '<tt:SimpleItem Name="MinCount" Value="5"/><tt:SimpleItem Name="AlarmOnDelay" Value="1000"/>'
        '<tt:SimpleItem Name="AlarmOffDelay" Value="1000"/>'
        f'<tt:SimpleItem Name="ActiveCells" Value={attr(active_cells)}/>'
        "</tt:Parameters></tan:Rule>"
    )


def region_rule_xml(name: str, points: list[tuple[float, float]], sensitivity: int | None) -> str:
    sens = f'<tt:SimpleItem Name="Sensitivity" Value="{sensitivity}"/>' if sensitivity is not None else ""
    return (
        f'<tan:Rule Name={attr(name)} Type="tt:{REGION_RULE}"><tt:Parameters>{sens}'
        f'<tt:ElementItem Name="MotionRegion"><axt:MotionRegion xmlns:axt="{_AXT}" Armed="true" Mask="false">'
        f"<axt:Polygon>{_points_xml(points)}</axt:Polygon></axt:MotionRegion></tt:ElementItem>"
        "</tt:Parameters></tan:Rule>"
    )


# ---- Chọn profile -------------------------------------------------------------------------------------


def pick_profile(profiles: list[ProfileData], need: str) -> ProfileData | None:
    """Profile có cấu hình cần dùng (``need`` là tên thuộc tính token), ưu tiên độ phân giải cao nhất."""
    candidates = [p for p in profiles if getattr(p, need)]
    if not candidates:
        return None
    return max(candidates, key=lambda p: (p.encoder.width * p.encoder.height) if p.encoder else 0)


# ---- Đọc trạng thái phát hiện chuyển động --------------------------------------------------------------


@dataclass
class MotionContext:
    token: str
    supported_rules: dict[str, int | None]
    rules: list[Element]
    modules: list[Element]
    grid: Grid | None = field(init=False)

    def __post_init__(self) -> None:
        self.grid = cell_layout(self.modules) or cell_layout(self.rules)

    @property
    def cell_rule(self) -> Element | None:
        return next((r for r in self.rules if config_type(r) == CELL_RULE), None)

    @property
    def region_rules(self) -> list[Element]:
        return [r for r in self.rules if config_type(r) == REGION_RULE]

    @property
    def engine(self) -> Element | None:
        return next((m for m in self.modules if config_type(m) == CELL_ENGINE), None)

    @property
    def cell_supported(self) -> bool:
        return self.grid is not None and (CELL_RULE in self.supported_rules or self.cell_rule is not None)

    @property
    def polygon_supported(self) -> bool:
        return REGION_RULE in self.supported_rules or bool(self.region_rules)

    @property
    def max_polygons(self) -> int | None:
        return self.supported_rules.get(REGION_RULE) if self.polygon_supported else None


async def motion_context(client: OnvifClient, profiles: list[ProfileData]) -> MotionContext | None:
    profile = pick_profile(profiles, "analytics_token")
    if not client.has_service("analytics") or profile is None or profile.analytics_token is None:
        return None
    token = profile.analytics_token
    supported: dict[str, int | None] = {}
    try:
        supported = await client.get_supported_rules(token)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không trả GetSupportedRules: %s", client.target.netloc, e.detail)
    try:
        rules = await client.get_rules(token)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không trả GetRules: %s", client.target.netloc, e.detail)
        return None
    modules: list[Element] = []
    try:
        modules = await client.get_analytics_modules(token)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không trả GetAnalyticsModules: %s", client.target.netloc, e.detail)
    return MotionContext(token, supported, rules, modules)


def describe_motion(ctx: MotionContext | None) -> MotionZones:
    if ctx is None or not (ctx.cell_supported or ctx.polygon_supported):
        return MotionZones(supported=False)
    regions = ctx.region_rules
    mode: ZoneMode = "POLYGON" if regions or not ctx.cell_supported else "CELL"
    cells = None
    if ctx.cell_supported and ctx.grid is not None:
        rule = ctx.cell_rule
        active = simple_item(rule, "ActiveCells") if rule is not None else None
        cells = decode_cells(active, ctx.grid.columns, ctx.grid.rows)
    polygons = [[from_onvif(x, y) for x, y in polygon_points(_region_polygon(r))] for r in regions]
    engine = ctx.engine
    sensitivity = None
    if mode == "POLYGON" and regions:
        sensitivity = _int_value(simple_item(regions[0], "Sensitivity"))
    if sensitivity is None and engine is not None:
        sensitivity = _int_value(simple_item(engine, "Sensitivity"))
    return MotionZones(
        supported=True,
        mode=mode,
        grid=ctx.grid if ctx.cell_supported else None,
        cells=cells,
        polygons=[p for p in polygons if p],
        sensitivity=sensitivity,
        max_polygons=ctx.max_polygons,
    )


def _region_polygon(rule: Element) -> Element | None:
    return next((n for n in rule.iter() if local_name(n.tag) == "Polygon"), None)


# ---- Đọc vùng che -------------------------------------------------------------------------------------


@dataclass(frozen=True)
class MaskContext:
    token: str
    max_masks: int | None
    max_points: int | None
    rectangle_only: bool
    mask_type: str


async def mask_context(client: OnvifClient, profiles: list[ProfileData]) -> MaskContext | None:
    profile = pick_profile(profiles, "video_source_config_token")
    if not client.has_service("media2") or profile is None or profile.video_source_config_token is None:
        return None
    if soap.bool_text(client.service_caps.get("media2", {}).get("Mask")) is False:
        return None
    token = profile.video_source_config_token
    try:
        options = await client.get_mask_options(token)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không hỗ trợ vùng che: %s", client.target.netloc, e.detail)
        return None
    mask_type = "Color" if "Color" in options.types or not options.types else options.types[0]
    return MaskContext(token, options.max_masks, options.max_points, options.rectangle_only, mask_type)


async def describe_masks(client: OnvifClient, ctx: MaskContext | None) -> PrivacyMasks:
    if ctx is None:
        return PrivacyMasks(supported=False)
    items = [
        PrivacyMask(token=m.token or None, points=[from_onvif(x, y) for x, y in m.points], enabled=m.enabled)
        for m in await client.get_masks(ctx.token)
    ]
    return PrivacyMasks(supported=True, max_masks=ctx.max_masks, max_points=ctx.max_points, items=items)


# ---- API cho routes -----------------------------------------------------------------------------------


async def get_zones(client: OnvifClient) -> ZonesConfig:
    profiles = await client.get_profiles()
    motion = describe_motion(await motion_context(client, profiles))
    masks = await describe_masks(client, await mask_context(client, profiles))
    return ZonesConfig(motion=motion, masks=masks)


async def set_motion(client: OnvifClient, req: MotionZonesSetRequest) -> None:
    ctx = await motion_context(client, await client.get_profiles())
    if ctx is None or not (ctx.cell_supported or ctx.polygon_supported):
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không hỗ trợ cấu hình vùng phát hiện chuyển động")
    if req.mode == "CELL":
        await _set_cells(client, ctx, req)
    else:
        await _set_polygons(client, ctx, req)
    log.info("Đã đặt vùng chuyển động %s trên thiết bị %s", req.mode, client.target.netloc)


async def _set_cells(client: OnvifClient, ctx: MotionContext, req: MotionZonesSetRequest) -> None:
    grid = ctx.grid
    if not ctx.cell_supported or grid is None:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không hỗ trợ vùng chuyển động dạng lưới ô")
    cells = req.cells
    if cells is None or len(cells) != grid.rows or any(len(row) != grid.columns for row in cells):
        raise DeviceError(ErrorCode.INVALID_ZONE, f"Lưới ô phải có {grid.rows} hàng × {grid.columns} cột theo thiết bị")
    active = encode_cells(cells)
    rule = ctx.cell_rule
    if rule is not None:
        set_simple_item(rule, "ActiveCells", active)
        await client.modify_rules(ctx.token, [serialize_config(rule, "Rule")])
    else:
        await client.create_rules(ctx.token, [cell_rule_xml(active)])
    await client.delete_rules(ctx.token, [r.get("Name", "") for r in ctx.region_rules if r.get("Name")])
    engine = ctx.engine
    if req.sensitivity is not None:
        if engine is None:
            log.info("Thiết bị %s không có CellMotionEngine, bỏ qua độ nhạy", client.target.netloc)
        else:
            set_simple_item(engine, "Sensitivity", str(req.sensitivity))
            await client.modify_analytics_modules(ctx.token, [serialize_config(engine, "AnalyticsModule")])


async def _set_polygons(client: OnvifClient, ctx: MotionContext, req: MotionZonesSetRequest) -> None:
    if not ctx.polygon_supported:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không hỗ trợ vùng chuyển động dạng đa giác")
    limit = ctx.max_polygons
    if limit is not None and len(req.polygons) > limit:
        raise DeviceError(ErrorCode.LIMIT_EXCEEDED, f"Thiết bị chỉ cho phép tối đa {limit} vùng đa giác")
    regions = ctx.region_rules
    sensitivity = req.sensitivity
    if sensitivity is None and regions:
        sensitivity = _int_value(simple_item(regions[0], "Sensitivity"))
    # Thay toàn bộ: xóa quy tắc đa giác cũ trước để không vượt số quy tắc tối đa của thiết bị
    await client.delete_rules(ctx.token, [r.get("Name", "") for r in regions if r.get("Name")])
    await client.create_rules(
        ctx.token,
        [
            region_rule_xml(f"{REGION_RULE_PREFIX}{i + 1}", [to_onvif(p) for p in polygon], sensitivity)
            for i, polygon in enumerate(req.polygons)
        ],
    )
    # Tắt mọi ô của quy tắc lưới (không xóa quy tắc vì nhiều camera không cho xóa quy tắc mặc định)
    rule, grid = ctx.cell_rule, ctx.grid
    if rule is not None and grid is not None:
        set_simple_item(rule, "ActiveCells", encode_cells([[False] * grid.columns for _ in range(grid.rows)]))
        await client.modify_rules(ctx.token, [serialize_config(rule, "Rule")])


async def set_masks(client: OnvifClient, req: PrivacyMasksSetRequest) -> None:
    ctx = await mask_context(client, await client.get_profiles())
    if ctx is None:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không hỗ trợ vùng che riêng tư")
    if ctx.max_masks is not None and len(req.items) > ctx.max_masks:
        raise DeviceError(ErrorCode.LIMIT_EXCEEDED, f"Thiết bị chỉ cho phép tối đa {ctx.max_masks} vùng che")
    shapes = []
    for item in req.items:
        if ctx.max_points is not None and len(item.points) > ctx.max_points:
            raise DeviceError(
                ErrorCode.LIMIT_EXCEEDED, f"Mỗi vùng che chỉ được tối đa {ctx.max_points} điểm theo thiết bị"
            )
        points = [to_onvif(p) for p in item.points]
        shapes.append((bounding_box(points) if ctx.rectangle_only else points, item.enabled))
    # Thay toàn bộ: xóa vùng che cũ trước để không vượt MaxMasks
    for mask in await client.get_masks(ctx.token):
        if mask.token:
            await client.delete_mask(mask.token)
    for points, enabled in shapes:
        await client.create_mask(ctx.token, points, enabled, ctx.mask_type)
    log.info("Đã đặt %d vùng che trên thiết bị %s", len(shapes), client.target.netloc)
