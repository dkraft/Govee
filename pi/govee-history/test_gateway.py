"""Discovery records candidates and does not bind them. Only 0xAA is sent."""

import unittest

from gateway import (
    Heard,
    LIGHT_NOTIFY,
    LIGHT_SERVICE,
    LIGHT_WRITE,
    Manager,
    SENSOR_NOTIFY,
    SENSOR_SERVICE,
    SENSOR_WRITE,
    connection_parameter_command,
    gatt_targets,
    status_query,
)
from store import History


class GatewayPolicyTest(unittest.TestCase):
    def setUp(self):
        self.history = History(":memory:")
        self.manager = Manager(self.history)

    def tearDown(self):
        self.history.close()

    def test_a_seen_sensor_is_a_candidate_and_not_bound(self):
        self.manager.apply_advertisement(
            Heard("AA:BB:CC:DD:EE:01", "Govee_H5075_ABCD", 0xEC88, bytes([0x00, 0x03, 0x49, 0x9A, 0x50, 0x00])),
            1_700_000_000_000,
        )
        self.assertEqual(self.history.bound(), [])
        self.assertEqual(self.history.candidates()[0]["address"], "AA:BB:CC:DD:EE:01")
        self.assertEqual(self.history.candidates()[0]["model"], "H5075")

    def test_a_light_is_not_queued(self):
        self.manager.apply_advertisement(
            Heard("AA:BB:CC:DD:EE:02", "Govee_H7021_2C52", 0x8802, bytes([0xEC, 0x00, 0x01, 0x01, 0x01])),
            1_700_000_000_000,
        )
        self.assertEqual(self.history.candidates(), [])
        self.assertEqual(self.history.bound(), [])

    def test_the_gateway_advertisement_is_not_a_sensor(self):
        self.manager.apply_advertisement(
            Heard("AA:BB:CC:DD:EE:03", "Govee_H5151_2036", 0x8801, bytes([0xEC, 0x00, 0x01, 0x01])),
            1_700_000_000_000,
        )
        self.assertEqual(self.history.candidates(), [])

    def test_only_the_status_query_is_sent(self):
        commands = self.manager.commands_to_send()
        self.assertEqual(commands, [status_query()])
        self.assertEqual(commands[0][1], 0xAA)

    def test_an_unknown_notify_does_not_bind(self):
        self.manager.apply_notify(bytes(20), 1_700_000_000_000)
        self.assertEqual(self.history.bound(), [])
        self.assertIn("last_notify", self.history.status_map())

    def test_the_live_gateway_uses_its_thermometer_service(self):
        service, write, notify = gatt_targets({SENSOR_SERVICE, SENSOR_WRITE, SENSOR_NOTIFY, "00001800-0000-1000-8000-00805f9b34fb"})
        self.assertEqual(service, SENSOR_SERVICE)
        self.assertEqual(write, SENSOR_WRITE)
        self.assertEqual(notify, (SENSOR_NOTIFY, SENSOR_WRITE))

    def test_a_light_service_is_still_recognized(self):
        service, write, notify = gatt_targets({LIGHT_SERVICE, LIGHT_WRITE, LIGHT_NOTIFY})
        self.assertEqual((service, write, notify), (LIGHT_SERVICE, LIGHT_WRITE, (LIGHT_NOTIFY,)))

    def test_connection_parameters_ask_for_a_slow_interval(self):
        packet = connection_parameter_command("C8:6B:84:C6:20:36", True)
        self.assertEqual(packet.hex(), "35000000110001003620c6846bc8025000a00000009001")

    def test_a_partial_thermometer_service_is_not_used(self):
        service, write, notify = gatt_targets({SENSOR_SERVICE, SENSOR_NOTIFY})
        self.assertEqual((service, write, notify), (None, None, ()))

    def test_an_already_bound_sensor_is_not_rediscovered(self):
        self.history.bind("AA:BB:CC:DD:EE:01", "H5075", "kitchen", 1)
        self.manager.apply_advertisement(
            Heard("AA:BB:CC:DD:EE:01", "Govee_H5075_ABCD", 0xEC88, bytes([0x00, 0x03, 0x49, 0x9A, 0x50, 0x00])),
            1_700_000_000_000,
        )
        self.assertEqual(self.history.candidates(), [])


if __name__ == "__main__":
    unittest.main()
