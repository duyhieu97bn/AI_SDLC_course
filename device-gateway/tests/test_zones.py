"""Vùng chuyển động và vùng che riêng tư (US-26): mã hóa ActiveCells, đổi tọa độ, REST qua bộ giả lập."""

from __future__ import annotations

from typing import Any

import pytest

from app.control.zones import (
    decode_cells,
    encode_cells,
    from_onvif,
    packbits_decode,
    packbits_encode,
    to_onvif,
)
from app.models import Point
from onvif_sim.cells import decode_active_cells
from tests.conftest import Gateway, RunningSim

ZONE_KEYS = {"motion", "masks"}
MOTION_KEYS = {"supported", "mode", "grid", "cells", "polygons", "sensitivity", "maxPolygons"}
MASK_KEYS = {"supported", "maxMasks", "maxPoints", "items"}
SQUARE = [{"x": 0.1, "y": 0.1}, {"x": 0.4, "y": 0.1}, {"x": 0.4, "y": 0.4}, {"x": 0.1, "y": 0.4}]


@pytest.mark.parametrize(
    "data",
    [b"", b"\x00", b"\xff" * 49 + b"\xf0", b"\x01\x02\x03\x03\x03\x04", bytes(range(200)), b"\xaa" * 300],
)
def test_packbits_roundtrip(data: bytes) -> None:
    assert packbits_decode(packbits_encode(data)) == data


def test_packbits_known_vector() -> None:
    # Ví dụ chuẩn trong đặc tả TIFF PackBits
    raw = bytes.fromhex("AAAAAA80002AAAAAAAAA80002A22" + "AA" * 10)
    packed = bytes.fromhex("FEAA0280002AFDAA0380002A22F7AA")
    assert packbits_decode(packed) == raw


def test_cells_roundtrip_and_simulator_agrees() -> None:
    cells = [[(r + c) % 3 == 0 for c in range(22)] for r in range(18)]
    active = encode_cells(cells)
    assert decode_cells(active, 22, 18) == cells
    flat = decode_active_cells(active, 22 * 18)
    assert flat == [bit for row in cells for bit in row]


def test_decode_cells_tolerates_garbage() -> None:
    assert decode_cells("không-phải-base64", 3, 2) == [[False] * 3, [False] * 3]


def test_coordinates_roundtrip() -> None:
    assert to_onvif(Point(x=0, y=0)) == (-1, 1)
    assert to_onvif(Point(x=1, y=1)) == (1, -1)
    assert to_onvif(Point(x=0.5, y=0.25)) == (0, 0.5)
    assert from_onvif(0, 0.5) == Point(x=0.5, y=0.25)
    assert from_onvif(-2, 2) == Point(x=0, y=0)


def _zones(gateway: Gateway, sim: RunningSim) -> Any:
    res = gateway.post("/v1/devices/zones/get", {"device": sim.device()})
    assert res.status_code == 200, res.text
    return res.json()


def test_zones_get_shape_and_limits(gateway: Gateway, sim: RunningSim) -> None:
    zones = _zones(gateway, sim)
    assert set(zones) == ZONE_KEYS
    motion, masks = zones["motion"], zones["masks"]
    assert set(motion) == MOTION_KEYS and set(masks) == MASK_KEYS
    assert motion["supported"] is True
    assert motion["grid"] == {"columns": 22, "rows": 18}
    assert motion["maxPolygons"] == 4
    assert len(motion["cells"]) == 18 and all(len(row) == 22 for row in motion["cells"])
    assert masks["supported"] is True
    assert (masks["maxMasks"], masks["maxPoints"]) == (4, 8)


def test_motion_cells_roundtrip(gateway: Gateway, sim: RunningSim) -> None:
    cells = [[c < 11 and r < 9 for c in range(22)] for r in range(18)]
    body = {"device": sim.device(), "mode": "CELL", "cells": cells, "sensitivity": 70}
    res = gateway.post("/v1/devices/zones/motion/set", body)
    assert res.status_code == 204, res.text
    state = sim.simulator.state
    assert state.cell_sensitivity == 70
    assert not any(r.type == "MotionRegionDetector" for r in state.rules.values())
    motion = _zones(gateway, sim)["motion"]
    assert motion["mode"] == "CELL"
    assert motion["cells"] == cells
    assert motion["sensitivity"] == 70
    assert motion["polygons"] == []


def test_motion_polygons_roundtrip_and_limit(gateway: Gateway, sim: RunningSim) -> None:
    triangle = [{"x": 0.5, "y": 0.2}, {"x": 0.8, "y": 0.8}, {"x": 0.2, "y": 0.8}]
    body = {"device": sim.device(), "mode": "POLYGON", "polygons": [SQUARE, triangle], "sensitivity": 35}
    res = gateway.post("/v1/devices/zones/motion/set", body)
    assert res.status_code == 204, res.text
    regions = [r for r in sim.simulator.state.rules.values() if r.type == "MotionRegionDetector"]
    assert len(regions) == 2
    assert regions[0].polygon[0] == pytest.approx((-0.8, 0.8))
    motion = _zones(gateway, sim)["motion"]
    assert motion["mode"] == "POLYGON"
    assert motion["sensitivity"] == 35
    assert motion["polygons"] == [SQUARE, triangle]
    # Quy tắc lưới bị tắt hết ô khi chuyển sang đa giác
    assert not any(any(row) for row in motion["cells"])

    too_many = {**body, "polygons": [SQUARE] * 5}
    res = gateway.post("/v1/devices/zones/motion/set", too_many)
    assert res.status_code == 424
    assert res.json()["code"] == "LIMIT_EXCEEDED"
    # Không thay đổi gì khi bị từ chối
    assert len([r for r in sim.simulator.state.rules.values() if r.type == "MotionRegionDetector"]) == 2


def test_motion_cells_wrong_grid_is_invalid_zone(gateway: Gateway, sim: RunningSim) -> None:
    body = {"device": sim.device(), "mode": "CELL", "cells": [[True] * 10] * 10}
    res = gateway.post("/v1/devices/zones/motion/set", body)
    assert res.status_code == 424
    assert res.json()["code"] == "INVALID_ZONE"


def test_motion_validation(gateway: Gateway, sim: RunningSim) -> None:
    bad_point = {"device": sim.device(), "mode": "POLYGON", "polygons": [[{"x": 1.5, "y": 0}] * 3]}
    assert gateway.post("/v1/devices/zones/motion/set", bad_point).status_code == 422
    two_points = {"device": sim.device(), "mode": "POLYGON", "polygons": [SQUARE[:2]]}
    assert gateway.post("/v1/devices/zones/motion/set", two_points).status_code == 422
    bad_sens = {"device": sim.device(), "mode": "CELL", "cells": [], "sensitivity": 101}
    assert gateway.post("/v1/devices/zones/motion/set", bad_sens).status_code == 422


def test_masks_replace_all(gateway: Gateway, sim: RunningSim) -> None:
    other = [{"x": 0.6, "y": 0.6}, {"x": 0.9, "y": 0.6}, {"x": 0.9, "y": 0.9}]
    body = {"device": sim.device(), "items": [{"points": SQUARE, "enabled": True}, {"points": other, "enabled": False}]}
    res = gateway.post("/v1/devices/zones/masks/set", body)
    assert res.status_code == 204, res.text
    res = gateway.post("/v1/devices/zones/masks/set", body)
    assert res.status_code == 204, res.text
    assert len(sim.simulator.state.masks) == 2
    items = _zones(gateway, sim)["masks"]["items"]
    assert [i["points"] for i in items] == [SQUARE, other]
    assert [i["enabled"] for i in items] == [True, False]
    assert all(i["token"] for i in items)

    res = gateway.post("/v1/devices/zones/masks/set", {"device": sim.device(), "items": []})
    assert res.status_code == 204
    assert sim.simulator.state.masks == {}


def test_masks_limits(gateway: Gateway, sim: RunningSim) -> None:
    five = {"device": sim.device(), "items": [{"points": SQUARE}] * 5}
    res = gateway.post("/v1/devices/zones/masks/set", five)
    assert res.status_code == 424
    assert res.json()["code"] == "LIMIT_EXCEEDED"
    nine = [{"x": 0.1 * i, "y": 0.05 * (i % 2)} for i in range(9)]
    res = gateway.post("/v1/devices/zones/masks/set", {"device": sim.device(), "items": [{"points": nine}]})
    assert res.status_code == 424
    assert res.json()["code"] == "LIMIT_EXCEEDED"


def test_unsupported_camera(gateway: Gateway, sim_fixed: RunningSim) -> None:
    res = gateway.post("/v1/devices/zones/get", {"device": sim_fixed.device()})
    assert res.status_code == 200, res.text
    zones = res.json()
    assert zones["motion"] == {
        "supported": False,
        "mode": None,
        "grid": None,
        "cells": None,
        "polygons": [],
        "sensitivity": None,
        "maxPolygons": None,
    }
    assert zones["masks"] == {"supported": False, "maxMasks": None, "maxPoints": None, "items": []}
    for path, extra in (
        ("/v1/devices/zones/motion/set", {"mode": "POLYGON", "polygons": [SQUARE]}),
        ("/v1/devices/zones/masks/set", {"items": [{"points": SQUARE}]}),
    ):
        res = gateway.post(path, {"device": sim_fixed.device(), **extra})
        assert res.status_code == 424
        assert res.json()["code"] == "NOT_SUPPORTED"
