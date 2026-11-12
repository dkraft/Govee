"""Advertisement decoder. Layouts match GoveeDecoder.kt.

Temperatures are Celsius. The H5151 name is a gateway and carries no
temperature, even when the manufacturer payload is short.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field

MIN_TEMP = -40.0
MAX_TEMP = 100.0


@dataclass
class Probe:
    id: int
    temperature_c: float
    high_alarm_c: float | None = None
    low_alarm_c: float | None = None


@dataclass
class Reading:
    model: str
    kind: str
    label: str
    temperature_c: float | None = None
    humidity_pct: float | None = None
    battery_pct: int | None = None
    co2_ppm: int | None = None
    pm25: int | None = None
    probes: list[Probe] = field(default_factory=list)
    events: list[str] = field(default_factory=list)
    occupied: bool | None = None
    motion: bool | None = None
    window_open: bool | None = None


def decode(local_name: str, manufacturer_id: int, data: bytes) -> list[Reading]:
    if manufacturer_id == 0x004C:
        return []
    name = local_name.strip()
    if "H5151" in name:
        return [Reading("H5151", "GATEWAY", "Wi-Fi gateway")]
    payload = data
    if len(payload) > 25 and b"INTELLI_ROCKS" in payload:
        payload = payload[:-25]
    parsed = _parse(name, manufacturer_id, payload)
    if parsed:
        return parsed
    if name.lower().startswith("govee") or name.startswith("GV"):
        model = _model_from_name(name) or "Govee"
        return [Reading(model, "OTHER", "Seen, no reading in this packet")]
    return []


def _parse(name: str, manufacturer_id: int, data: bytes) -> list[Reading]:
    n = len(data)
    if n == 24:
        return _decrypt_contact(name, data)
    if n == 6 and (
        data.startswith(bytes([0xEC, 0x00, 0x01, 0x01])) or data.startswith(bytes([0xEC, 0x00, 0x02, 0x01]))
    ) and ("H5127" in name or manufacturer_id in (0x8803, 0x8843)):
        return [
            Reading(
                "H5127",
                "SENSOR",
                "Presence",
                occupied=data[4] == 1,
                motion=data[5] == 17,
            )
        ]
    if n == 6 and ("H5072" in name or "H5075" in name or "H5129" in name or manufacturer_id == 0xEC88):
        if "H5072" in name:
            model = "H5072"
        elif "H5075" in name:
            model = "H5075"
        elif "H5129" in name:
            model = "H5129"
        else:
            model = "H5072/H5075"
        return _packed_thermo(model, data[1:5])
    if n == 8 and ("GV5112" in name or "H5112" in name):
        probe_id = {0x41: 1, 0x82: 2}.get(data[7])
        if probe_id is None:
            return []
        temp, humi, batt, err = _unpack_packed(data[2:6])
        probes = [] if err or not (MIN_TEMP <= temp <= MAX_TEMP) else [Probe(probe_id, temp)]
        humidity = humi if probe_id == 1 and probes else None
        return [
            Reading(
                "H5112",
                "SENSOR",
                f"Probe {probe_id}",
                humidity_pct=humidity,
                battery_pct=batt,
                probes=probes,
            )
        ]
    if n == 8 and "5140" in name:
        temp, humi = _unpack_temp_humid(data[2:5])
        co2 = _u16be(data[5], data[6])
        err = data[7] != 0
        if err or not (MIN_TEMP <= temp <= MAX_TEMP):
            return []
        return [Reading("H5140", "SENSOR", "Air", temp, humi, co2_ppm=co2)]
    if n in (6, 8):
        named = _named_thermo(name, n, manufacturer_id)
        if named:
            return _packed_thermo(named, data[2:6])
    if n == 7 and ("H5074" in name or manufacturer_id == 0xEC88):
        temp = _s16le(data[1], data[2]) / 100.0
        humi = _u16le(data[3], data[4]) / 100.0
        batt = data[5]
        return [Reading("H5074", "SENSOR", "Thermo-hygrometer", temp, humi, batt)]
    if n == 9 and (manufacturer_id == 0xEC88 or "H5051" in name or "H5052" in name or "H5071" in name):
        if "H5071" in name:
            model = "H5071"
        elif "H5052" in name:
            model = "H5052"
        else:
            model = "H5051"
        temp = _s16le(data[1], data[2]) / 100.0
        humi = _u16le(data[3], data[4]) / 100.0
        return [Reading(model, "SENSOR", "Thermo-hygrometer", temp, humi, data[5])]
    if n == 9 and ("H5178" in name or "B5178" in name or manufacturer_id == 0x0001):
        temp, humi, batt, err = _unpack_packed(data[3:7])
        remote = data[2] == 1
        ok = not err and MIN_TEMP <= temp <= MAX_TEMP
        return [
            Reading(
                "H5178-REMOTE" if remote else "H5178",
                "SENSOR",
                "Remote probe" if remote else "Primary",
                temperature_c=temp if ok else None,
                humidity_pct=humi if ok else None,
                battery_pct=batt,
            )
        ]
    if n == 9 and ("H5179" in name or manufacturer_id == 0x8801):
        temp = _s16le(data[4], data[5]) / 100.0
        humi = _u16le(data[6], data[7]) / 100.0
        return [Reading("H5179", "SENSOR", "Thermo-hygrometer", temp, humi, data[8])]
    if n == 6 and "H5106" in name:
        word = int.from_bytes(data[2:6], "big")
        return [
            Reading(
                "H5106",
                "SENSOR",
                "Air",
                temperature_c=_decode4_temp(word),
                humidity_pct=_decode4_humi(word),
                pm25=(word & 0x7FFFFFFF) % 1000,
            )
        ]
    probe = _probe_reading(name, manufacturer_id, data)
    if probe is not None:
        return [probe]
    return []


def _named_thermo(name: str, n: int, manufacturer_id: int) -> str | None:
    for model in ("H5108", "H5100", "H5101", "H5102", "H5103", "H5104", "H5105", "H5174", "H5177", "H5110", "GV5179"):
        if model in name:
            return model
    if n == 8 and manufacturer_id == 0x0001:
        return "H5108"
    return None


def _probe_reading(name: str, manufacturer_id: int, data: bytes) -> Reading | None:
    n = len(data)
    if n == 14 and ("H5181" in name or manufacturer_id in (0xF861, 0x388A, 0xEA42, 0xAAA2, 0xD14B)):
        return _probes("H5181", [_probe_pair(data, 8, 1)])
    if n == 17 and ("H5182" in name or manufacturer_id == 0x2730):
        return _probes("H5182", [_probe_pair(data, 8, 1), _probe_pair(data, 13, 2)])
    if n == 14 and ("H5183" in name or manufacturer_id in (0x67DD, 0xE02F, 0xF79F)):
        return _probes("H5183", [_probe_pair(data, 8, 1)])
    if n == 17 and ("H5184" in name or manufacturer_id == 0x1B36):
        ids = _four_probe_ids(data[6])
        if ids is None:
            return None
        return _probes("H5184", [_probe_pair(data, 8, ids[0]), _probe_pair(data, 13, ids[1])])
    if n == 20 and ("H5185" in name or manufacturer_id in (0x4A32, 0x332, 0x4C32)):
        return _probes("H5185", [_probe_pair(data, 8, 1), _probe_pair(data, 14, 2)])
    if n == 20 and ("H5198" in name or manufacturer_id == 0x3022):
        ids = _four_probe_ids(data[6])
        if ids is None:
            return None
        return _probes("H5198", [_probe_triple(data, 8, ids[0]), _probe_triple(data, 14, ids[1])])
    return None


def _probes(model: str, probes: list[Probe]) -> Reading:
    return Reading(model, "SENSOR", "Meat probe", probes=[probe for probe in probes if probe.temperature_c >= 0])


def _probe_pair(data: bytes, offset: int, ident: int) -> Probe:
    temp = _s16be(data[offset], data[offset + 1]) / 100.0
    alarm = _s16be(data[offset + 2], data[offset + 3]) / 100.0
    return Probe(ident, temp, high_alarm_c=alarm if alarm >= 0 else None)


def _probe_triple(data: bytes, offset: int, ident: int) -> Probe:
    temp = _s16be(data[offset], data[offset + 1]) / 100.0
    high = _s16be(data[offset + 2], data[offset + 3]) / 100.0
    low = _s16be(data[offset + 4], data[offset + 5]) / 100.0
    return Probe(ident, temp, high if high >= 0 else None, low if low >= 0 else None)


def _four_probe_ids(sensor_id: int) -> tuple[int, int] | None:
    if sensor_id in (1, 65, 129, 193):
        return (1, 2)
    if sensor_id in (2, 66, 130, 194):
        return (3, 4)
    return None


def _packed_thermo(model: str, four: bytes) -> list[Reading]:
    temp, humi, batt, err = _unpack_packed(four)
    ok = not err and MIN_TEMP <= temp <= MAX_TEMP
    return [
        Reading(
            model,
            "SENSOR",
            "Thermo-hygrometer",
            temperature_c=temp if ok else None,
            humidity_pct=humi if ok else None,
            battery_pct=batt,
        )
    ]


def _unpack_packed(four: bytes) -> tuple[float, float, int, bool]:
    temp, humi = _unpack_temp_humid(four[:3])
    last = four[3]
    return temp, humi, last & 0x7F, (last & 0x80) != 0


def _unpack_temp_humid(three: bytes) -> tuple[float, float]:
    base = (three[0] << 16) | (three[1] << 8) | three[2]
    negative = (base & 0x800000) != 0
    magnitude = base & 0x7FFFFF
    temp = (magnitude // 1000) / 10.0
    if negative:
        temp = -temp
    humidity = (magnitude % 1000) / 10.0
    return temp, humidity


def _decode4_temp(packet: int) -> float:
    negative = (packet & 0x80000000) != 0
    magnitude = packet & 0x7FFFFFFF
    value = (magnitude // 1_000_000) / 10.0
    return -value if negative else value


def _decode4_humi(packet: int) -> float:
    magnitude = packet & 0x7FFFFFFF
    return ((magnitude % 1_000_000) // 1000) / 10.0


def _decrypt_contact(name: str, data: bytes) -> list[Reading]:
    enc = data[6:22]
    crc = _u16be(data[22], data[23])
    if _crc16(enc) != crc:
        return []
    key = data[2:6] + bytes(12)
    try:
        clear = _aes_ecb_decrypt(key[::-1], enc[::-1])[::-1]
    except (OSError, subprocess.CalledProcessError, ValueError):
        return []
    model_id = clear[2]
    model = _contact_model(name, model_id)
    if model is None:
        return []
    battery = clear[4]
    state = clear[5]
    events: list[str] = []
    window = None
    occupied = None
    if model == "H5123":
        window = state == 2
    elif model == "H5124":
        if state == 1:
            events.append("vibration")
    elif model == "H5121":
        if state == 1:
            events.append("motion")
    elif model == "H5130":
        if state & 0x10:
            events.append("button")
        if state != 0x10:
            occupied = (state & 0x01) != 0
    elif state != 0:
        events.append(f"button {state}")
    return [
        Reading(
            model,
            "SENSOR",
            "Contact",
            battery_pct=battery,
            events=events,
            occupied=occupied,
            window_open=window,
            motion=True if "motion" in events else None,
        )
    ]


def _contact_model(name: str, model_id: int) -> str | None:
    if "GV5121" in name or model_id == 3:
        return "H5121"
    if "GV5122" in name or model_id == 8:
        return "H5122"
    if "GV5123" in name or model_id == 2:
        return "H5123"
    if "GV5124" in name or model_id == 9:
        return "H5124"
    if "GV5125" in name or model_id == 10:
        return "H5125"
    if "GV5126" in name or model_id == 11:
        return "H5126"
    if "GV5130" in name or model_id == 13:
        return "H5130"
    return None


def _crc16(data: bytes) -> int:
    crc = 0x1D0F
    for value in data:
        for shift in range(7, -1, -1):
            mask = 0x1021 if (((crc >> 15) ^ (value >> shift)) & 1) == 1 else 0
            crc = ((crc << 1) ^ mask) & 0xFFFF
    return crc


def _aes_ecb_decrypt(key: bytes, data: bytes) -> bytes:
    proc = subprocess.run(
        ["openssl", "enc", "-aes-128-ecb", "-d", "-K", key.hex(), "-nopad"],
        input=data,
        capture_output=True,
        check=True,
    )
    if len(proc.stdout) != len(data):
        raise ValueError("short decrypt")
    return proc.stdout


def _model_from_name(name: str) -> str:
    match = re.search(r"(H|GVH?)[0-9A-Z]{4}", name)
    return match.group(0) if match else ""


def _u16le(b0: int, b1: int) -> int:
    return b0 | (b1 << 8)


def _s16le(b0: int, b1: int) -> int:
    value = _u16le(b0, b1)
    return value - 0x10000 if value & 0x8000 else value


def _u16be(b0: int, b1: int) -> int:
    return (b0 << 8) | b1


def _s16be(b0: int, b1: int) -> int:
    value = _u16be(b0, b1)
    return value - 0x10000 if value & 0x8000 else value
