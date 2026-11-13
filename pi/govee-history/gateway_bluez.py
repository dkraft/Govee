"""BlueZ central for the H5151. Imported only by the gateway process.

Requires python3-dbus and python3-gi, which Raspberry Pi OS ships. The
history HTTP process does not import this module.
"""

from __future__ import annotations

import ctypes
import os
import socket
import time
from collections import deque

import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

from gateway import Heard, connection_parameter_command, gatt_targets

DBUS_OM = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP = "org.freedesktop.DBus.Properties"
ADAPTER = "org.bluez.Adapter1"
DEVICE = "org.bluez.Device1"
GATT_CHRC = "org.bluez.GattCharacteristic1"
GATT_SERVICE = "org.bluez.GattService1"
# The gateway drops a central that has not subscribed. The thermometer
# characteristics were on the bus about five seconds after connect, and
# the remote hung up at about ten. Stop waiting once they are present.
GATT_WAIT_SECONDS = 8


def load_connection_parameters(address: str, random_address: bool) -> None:
    """Ask the kernel for a 100–200 ms interval before Create Connection.

    After GATT, BlueZ reads the gateway's preferred 10 ms interval and
    stores it for the next connect. Loading our interval first keeps that
    from becoming the create-connection command. The gateway user may not
    be allowed to open the management socket; that is not a failed link.
    """

    packet = connection_parameter_command(address, random_address)
    try:
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
    except OSError:
        return
    try:
        class SockaddrHci(ctypes.Structure):
            _fields_ = (
                ("hci_family", ctypes.c_ushort),
                ("hci_dev", ctypes.c_ushort),
                ("hci_channel", ctypes.c_ushort),
            )

        # HCI_DEV_NONE is 0xffff. Channel 3 is the management socket.
        # Python's own bind only accepts a device index and leaves the
        # channel as raw, so the kernel never sees this as a control socket.
        addr = SockaddrHci(socket.AF_BLUETOOTH, 0xFFFF, 3)
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.bind(sock.fileno(), ctypes.byref(addr), ctypes.sizeof(addr)) != 0:
            return
        # A rejected command can report a zero-length write and still queue
        # an event. The event status is what says the interval was stored.
        os.write(sock.fileno(), packet)
        sock.settimeout(0.3)
        reply = sock.recv(64)
        if len(reply) < 9 or reply[8] != 0:
            return
    except OSError:
        return
    finally:
        sock.close()


class BluezRadio:
    def __init__(self) -> None:
        DBusGMainLoop(set_as_default=True)
        self.bus = dbus.SystemBus()
        self.loop = GLib.MainLoop()
        GLib.timeout_add(50, lambda: True)
        import threading
        self._thread = threading.Thread(target=self.loop.run, daemon=True)
        self._thread.start()
        self.adapter = self._adapter()
        self.device_path = None
        self.write_path = None
        self.notify_paths: list[str] = []
        self._notifies: deque[bytes] = deque()

    def connect_gateway(self) -> None:
        self.scan(8, lambda _heard: None)
        path = self._find_gateway()
        if path is None:
            raise RuntimeError("H5151 was not heard")
        self._prepare_connection(path)
        device = dbus.Interface(self.bus.get_object("org.bluez", path), DEVICE)
        device.Connect()
        found = self._wait_for_targets(path, GATT_WAIT_SECONDS)
        if found is None:
            raise RuntimeError("control service is absent")
        write_path, notify_paths = found
        self.device_path = path
        self.write_path = write_path
        self._notifies.clear()
        armed = self._start_notifies(notify_paths)
        if not armed:
            raise RuntimeError("control service is absent")
        self.notify_paths = armed

    def write(self, data: bytes) -> None:
        if self.write_path is None:
            raise RuntimeError("control service is absent")
        chrc = dbus.Interface(self.bus.get_object("org.bluez", self.write_path), GATT_CHRC)
        # Write without response, the same ATT op the phone frame uses.
        # This dbus-python has no dbus.Variant. variant_level wraps the string.
        options = dbus.Dictionary({"type": dbus.String("command", variant_level=1)}, signature="sv")
        chrc.WriteValue(dbus.Array(data, signature="y"), options)

    def read_notify(self, timeout: float) -> bytes | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._notifies:
                return self._notifies.popleft()
            time.sleep(0.2)
        return None

    def disconnect(self) -> None:
        paths = list(self.notify_paths)
        device_path = self.device_path
        self.device_path = None
        self.write_path = None
        self.notify_paths = []
        for path in paths:
            self._stop_notify(path)
        if device_path is None:
            return
        try:
            device = dbus.Interface(self.bus.get_object("org.bluez", device_path), DEVICE)
            device.Disconnect()
        except dbus.exceptions.DBusException:
            return

    def scan(self, seconds: float, on_heard) -> None:
        adapter = dbus.Interface(self.bus.get_object("org.bluez", self.adapter), ADAPTER)
        try:
            adapter.SetDiscoveryFilter({"Transport": "le"})
        except dbus.exceptions.DBusException:
            pass
        try:
            adapter.StartDiscovery()
        except dbus.exceptions.DBusException as error:
            if "InProgress" not in str(error):
                raise
        time.sleep(seconds)
        self._emit_heard(on_heard)
        try:
            adapter.StopDiscovery()
        except dbus.exceptions.DBusException:
            return

    def _emit_heard(self, on_heard) -> None:
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        for path, ifaces in objects.items():
            device = ifaces.get(DEVICE)
            if not device or not str(path).startswith(self.adapter):
                continue
            name = str(device.get("Name") or device.get("Alias") or "")
            address = str(device.get("Address") or "")
            manufacturer = device.get("ManufacturerData") or {}
            if not manufacturer:
                on_heard(Heard(address, name, 0, b""))
                continue
            for key, value in manufacturer.items():
                payload = bytes(int(item) & 0xFF for item in value)
                on_heard(Heard(address, name, int(key), payload))

    def _find_gateway(self) -> str | None:
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        for path, ifaces in objects.items():
            device = ifaces.get(DEVICE)
            if not device:
                continue
            name = str(device.get("Name") or device.get("Alias") or "")
            if "H5151" in name:
                return str(path)
        return None

    def _prepare_connection(self, path: str) -> None:
        try:
            props = dbus.Interface(self.bus.get_object("org.bluez", path), DBUS_PROP)
            address = str(props.Get(DEVICE, "Address"))
            kind = str(props.Get(DEVICE, "AddressType"))
        except (dbus.exceptions.DBusException, KeyError, TypeError, ValueError):
            return
        try:
            load_connection_parameters(address, kind.lower() == "random")
        except (OSError, ValueError):
            return

    def _wait_for_targets(self, device_path: str, seconds: float):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            found = self._match_targets(device_path)
            if found is not None:
                return found
            time.sleep(0.2)
        return None

    def _match_targets(self, device_path: str):
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        uuids: set[str] = set()
        chars: dict[str, str] = {}
        for path, ifaces in objects.items():
            text = str(path)
            if not text.startswith(device_path):
                continue
            service = ifaces.get(GATT_SERVICE)
            if service:
                uuids.add(str(service.get("UUID", "")).lower())
            chrc = ifaces.get(GATT_CHRC)
            if not chrc:
                continue
            uuid = str(chrc.get("UUID", "")).lower()
            uuids.add(uuid)
            chars[uuid] = text
        _service, write_uuid, notify_uuids = gatt_targets(uuids)
        if write_uuid is None or write_uuid not in chars or not notify_uuids:
            return None
        notify_paths = []
        for uuid in notify_uuids:
            found = chars.get(uuid)
            if found is None:
                return None
            notify_paths.append(found)
        return chars[write_uuid], notify_paths

    def _start_notifies(self, paths: list[str]) -> list[str]:
        armed: list[str] = []
        pending: list[str] = []
        for path in paths:
            if self._arm_notify(path):
                armed.append(path)
            else:
                pending.append(path)
        if pending:
            time.sleep(0.25)
            for path in pending:
                if self._arm_notify(path):
                    armed.append(path)
        return armed

    def _arm_notify(self, path: str) -> bool:
        try:
            chrc = dbus.Interface(self.bus.get_object("org.bluez", path), GATT_CHRC)
            chrc.StartNotify()
        except dbus.exceptions.DBusException:
            return False
        self.bus.add_signal_receiver(
            self._on_properties,
            signal_name="PropertiesChanged",
            dbus_interface=DBUS_PROP,
            path=path,
        )
        return True

    def _stop_notify(self, path: str) -> None:
        try:
            self.bus.remove_signal_receiver(
                self._on_properties,
                signal_name="PropertiesChanged",
                dbus_interface=DBUS_PROP,
                path=path,
            )
        except dbus.exceptions.DBusException:
            pass
        try:
            chrc = dbus.Interface(self.bus.get_object("org.bluez", path), GATT_CHRC)
            chrc.StopNotify()
        except dbus.exceptions.DBusException:
            return

    def _on_properties(self, interface, changed, _invalidated) -> None:
        if interface != GATT_CHRC or "Value" not in changed:
            return
        self._notifies.append(bytes(int(item) & 0xFF for item in changed["Value"]))

    def _adapter(self) -> str:
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        for path, ifaces in objects.items():
            if ADAPTER in ifaces:
                return str(path)
        raise RuntimeError("bluetooth adapter is not available")
