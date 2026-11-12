"""Discovery records candidates and does not bind them. Only 0xAA is sent."""

import unittest

from gateway import Heard, Manager, status_query
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

    def test_an_already_bound_sensor_is_not_rediscovered(self):
        self.history.bind("AA:BB:CC:DD:EE:01", "H5075", "kitchen", 1)
        self.manager.apply_advertisement(
            Heard("AA:BB:CC:DD:EE:01", "Govee_H5075_ABCD", 0xEC88, bytes([0x00, 0x03, 0x49, 0x9A, 0x50, 0x00])),
            1_700_000_000_000,
        )
        self.assertEqual(self.history.candidates(), [])


if __name__ == "__main__":
    unittest.main()
