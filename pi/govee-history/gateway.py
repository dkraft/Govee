"""The Pi's H5151 session.

One adapter. Most of the minute is a connection that sends the status
query. A short passive scan records sensor candidates. Nothing is marked
bound, and no command other than 0xAA is sent, until a captured notify
has a layout we can name.
"""

from __future__ import annotations

import argparse
import struct
import time
from dataclasses import dataclass

from decode import decode
from store import History

# The light layout. This H5151 does not publish it.
LIGHT_SERVICE = "00010203-0405-0607-0809-0a0b0c0d1910"
LIGHT_WRITE = "00010203-0405-0607-0809-0a0b0c0d2b11"
LIGHT_NOTIFY = "00010203-0405-0607-0809-0a0b0c0d2b10"
# ASCII "INTELLI_ROCKS_HW". The live gateway's write and notify characteristics.
SENSOR_SERVICE = "494e5445-4c4c-495f-524f-434b535f4857"
SENSOR_WRITE = "494e5445-4c4c-495f-524f-434b535f2012"
SENSOR_NOTIFY = "494e5445-4c4c-495f-524f-434b535f2011"
STATUS_COMMAND = 0xAA
# 1.25 ms units. 80/160 is 100/200 ms, which this Pi's controller can keep
# while Wi-Fi is up. The gateway's own preferred 10 ms is what it asks for.
CONN_MIN = 80
CONN_MAX = 160
CONN_TIMEOUT = 400
CONNECT_SECONDS = 50
SCAN_SECONDS = 8
# The Pi 3 shares one radio with Wi-Fi. A failed H5151 connect returns in
# under a second, and repeating it wedges wlan0. Wait before trying again.
FAIL_BACKOFF_SECONDS = 60


def frame(command: int, payload: bytes = b"") -> bytes:
    out = bytearray(20)
    out[0] = 0x33
    out[1] = command & 0xFF
    size = min(len(payload), 17)
    out[2 : 2 + size] = payload[:size]
    out[19] = checksum(out)
    return bytes(out)


def checksum(data: bytes | bytearray) -> int:
    value = 0
    for index in range(19):
        value ^= data[index]
    return value


def checksum_ok(data: bytes | bytearray) -> bool:
    return len(data) == 20 and checksum(data) == data[19]


def status_query() -> bytes:
    return frame(STATUS_COMMAND)


def gatt_targets(uuids: set[str]) -> tuple[str | None, str | None, tuple[str, ...]]:
    """Pick the service this gateway published.

    The H5151 in the house publishes the thermometer service. The light
    service is only used when that is what a gateway actually has.
    """

    found = {item.lower() for item in uuids}
    if SENSOR_SERVICE in found and SENSOR_WRITE in found and SENSOR_NOTIFY in found:
        return SENSOR_SERVICE, SENSOR_WRITE, (SENSOR_NOTIFY, SENSOR_WRITE)
    if LIGHT_SERVICE in found and LIGHT_WRITE in found and LIGHT_NOTIFY in found:
        return LIGHT_SERVICE, LIGHT_WRITE, (LIGHT_NOTIFY,)
    return None, None, ()


def connection_parameter_command(address: str, random_address: bool) -> bytes:
    parts = [int(part, 16) for part in address.split(":")]
    if len(parts) != 6 or any(part > 0xFF for part in parts):
        raise ValueError("address")
    addr = bytes(reversed(parts))
    addr_type = 2 if random_address else 1
    param = addr + struct.pack("<BHHHH", addr_type, CONN_MIN, CONN_MAX, 0, CONN_TIMEOUT)
    payload = struct.pack("<H", 1) + param
    return struct.pack("<HHH", 0x0035, 0, len(payload)) + payload


def parse_notify(data: bytes) -> list[Reading]:
    """A notify is not a sensor reading until its layout is known.

    Returning readings here is what allows an address to become bound.
    """

    if not checksum_ok(data):
        return []
    return []


@dataclass
class Heard:
    address: str
    name: str
    manufacturer_id: int
    payload: bytes


class Manager:
    def __init__(self, history: History) -> None:
        self.history = history

    def apply_advertisement(self, heard: Heard, now_ms: int) -> None:
        readings = decode(heard.name, heard.manufacturer_id, heard.payload)
        for reading in readings:
            if reading.kind != "SENSOR":
                continue
            if heard.address in self.history.bound_addresses():
                continue
            self.history.note_candidate(heard.address, reading.model, heard.name, now_ms)

    def apply_notify(self, data: bytes, now_ms: int) -> None:
        self.history.set_status("last_notify", data.hex())
        self.history.set_status("last_notify_ms", str(now_ms))
        # parse_notify returns nothing until a frame from this gateway names a
        # sub-device. That is the only path that may insert into bound.

    def commands_to_send(self) -> list[bytes]:
        return [status_query()]


def run(history: History, radio) -> None:
    manager = Manager(history)
    while True:
        history.set_status("gateway", "connecting")
        try:
            radio.connect_gateway()
            history.set_status("gateway", "up")
            history.set_status("gateway_detail", "")
            deadline = time.monotonic() + CONNECT_SECONDS
            while time.monotonic() < deadline:
                for command in manager.commands_to_send():
                    radio.write(command)
                notify = radio.read_notify(timeout=10)
                if notify:
                    manager.apply_notify(notify, int(time.time() * 1000))
        except Exception as error:  # adapter or link failure stays on /health
            history.set_status("gateway", "no-control-service" if "control" in str(error).lower() else "down")
            history.set_status("gateway_detail", str(error))
        finally:
            radio.disconnect()
        if history.status_map().get("gateway") == "up":
            history.set_status("gateway", "scanning")
        try:
            radio.scan(SCAN_SECONDS, lambda heard: manager.apply_advertisement(heard, int(time.time() * 1000)))
        except Exception as error:
            history.set_status("gateway_detail", str(error))
        if history.status_map().get("gateway") != "scanning":
            time.sleep(FAIL_BACKOFF_SECONDS)


class NullRadio:
    """Used when BlueZ is not importable. The HTTP service still serves graphs."""

    def connect_gateway(self) -> None:
        raise RuntimeError("bluetooth adapter is not available")

    def write(self, data: bytes) -> None:
        raise RuntimeError("bluetooth adapter is not available")

    def read_notify(self, timeout: float) -> bytes | None:
        return None

    def disconnect(self) -> None:
        return None

    def scan(self, seconds: float, on_heard) -> None:
        return None


def make_radio():
    try:
        from gateway_bluez import BluezRadio
    except ImportError as error:
        radio = NullRadio()
        radio.import_error = str(error)
        return radio
    return BluezRadio()


def main() -> None:
    parser = argparse.ArgumentParser(description="H5151 manager")
    parser.add_argument("--db", default="/var/lib/govee-history/history.db")
    args = parser.parse_args()
    history = History(args.db)
    radio = make_radio()
    if isinstance(radio, NullRadio):
        history.set_status("gateway", "down")
        history.set_status("gateway_detail", getattr(radio, "import_error", "no adapter"))
    run(history, radio)


if __name__ == "__main__":
    main()
