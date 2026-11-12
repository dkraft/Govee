"""BlueZ central for the H5151. Imported only by the gateway process.

Requires python3-dbus and python3-gi, which Raspberry Pi OS ships. The
history HTTP process does not import this module.
"""

from __future__ import annotations

import time

import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

from gateway import SERVICE, WRITE_UUID, NOTIFY_UUID, Heard

DBUS_OM = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP = "org.freedesktop.DBus.Properties"
ADAPTER = "org.bluez.Adapter1"
DEVICE = "org.bluez.Device1"
GATT_CHRC = "org.bluez.GattCharacteristic1"


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
        self.notify_path = None
        self._notify = None

    def connect_gateway(self) -> None:
        self.scan(8, lambda _heard: None)
        path = self._find_gateway()
        if path is None:
            raise RuntimeError("H5151 was not heard")
        device = dbus.Interface(self.bus.get_object("org.bluez", path), DEVICE)
        device.Connect()
        self._wait_until(path, "ServicesResolved", True, 20)
        write_path, notify_path = self._characteristics(path)
        if write_path is None or notify_path is None:
            raise RuntimeError("control service is absent")
        self.device_path = path
        self.write_path = write_path
        self.notify_path = notify_path
        self._notify = None
        self.bus.add_signal_receiver(
            self._on_properties,
            signal_name="PropertiesChanged",
            dbus_interface=DBUS_PROP,
            path=notify_path,
        )
        chrc = dbus.Interface(self.bus.get_object("org.bluez", notify_path), GATT_CHRC)
        chrc.StartNotify()

    def write(self, data: bytes) -> None:
        if self.write_path is None:
            raise RuntimeError("control service is absent")
        chrc = dbus.Interface(self.bus.get_object("org.bluez", self.write_path), GATT_CHRC)
        chrc.WriteValue(dbus.Array(data, signature="y"), {})

    def read_notify(self, timeout: float) -> bytes | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._notify is not None:
                data = self._notify
                self._notify = None
                return data
            time.sleep(0.2)
        return None

    def disconnect(self) -> None:
        path = self.device_path
        self.device_path = None
        self.write_path = None
        self.notify_path = None
        if path is None:
            return
        try:
            device = dbus.Interface(self.bus.get_object("org.bluez", path), DEVICE)
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

    def _characteristics(self, device_path: str) -> tuple[str | None, str | None]:
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        write_path = None
        notify_path = None
        service_path = None
        for path, ifaces in objects.items():
            service = ifaces.get("org.bluez.GattService1")
            if service and str(path).startswith(device_path) and str(service.get("UUID", "")).lower() == SERVICE:
                service_path = str(path)
        if service_path is None:
            return None, None
        for path, ifaces in objects.items():
            chrc = ifaces.get(GATT_CHRC)
            if not chrc or not str(path).startswith(service_path):
                continue
            uuid = str(chrc.get("UUID", "")).lower()
            if uuid == WRITE_UUID:
                write_path = str(path)
            if uuid == NOTIFY_UUID:
                notify_path = str(path)
        return write_path, notify_path

    def _wait_until(self, path: str, prop: str, expected, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        props = dbus.Interface(self.bus.get_object("org.bluez", path), DBUS_PROP)
        while time.monotonic() < deadline:
            value = props.Get(DEVICE, prop)
            if bool(value) == bool(expected):
                return
            time.sleep(0.3)
        raise RuntimeError("control service is absent")

    def _on_properties(self, interface, changed, _invalidated) -> None:
        if interface != GATT_CHRC or "Value" not in changed:
            return
        self._notify = bytes(int(item) & 0xFF for item in changed["Value"])

    def _adapter(self) -> str:
        objects = dbus.Interface(self.bus.get_object("org.bluez", "/"), DBUS_OM).GetManagedObjects()
        for path, ifaces in objects.items():
            if ADAPTER in ifaces:
                return str(path)
        raise RuntimeError("bluetooth adapter is not available")
