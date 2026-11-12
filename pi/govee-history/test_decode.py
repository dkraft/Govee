"""The four advertisement fixtures from GoveeDecoderTest, plus the status frame."""

import unittest

from decode import decode
from gateway import checksum_ok, status_query


def b(*values: int) -> bytes:
    return bytes(values)


class DecodeTest(unittest.TestCase):
    def test_h5075(self):
        reading = decode("Govee_H5075_ABCD", 0xEC88, b(0x00, 0x03, 0x49, 0x9A, 0x50, 0x00))[0]
        self.assertEqual(reading.model, "H5075")
        self.assertEqual(reading.kind, "SENSOR")
        self.assertAlmostEqual(reading.temperature_c, 21.5, delta=0.05)
        self.assertAlmostEqual(reading.humidity_pct, 45.0, delta=0.05)
        self.assertEqual(reading.battery_pct, 80)

    def test_negative_temperature(self):
        reading = decode("Govee_H5075_ABCD", 0xEC88, b(0x00, 0x80, 0xC4, 0x7C, 0x64, 0x00))[0]
        self.assertAlmostEqual(reading.temperature_c, -5.0, delta=0.05)
        self.assertAlmostEqual(reading.humidity_pct, 30.0, delta=0.05)
        self.assertEqual(reading.battery_pct, 100)

    def test_h5074(self):
        reading = decode("Govee_H5074_ABCD", 0xEC88, b(0x00, 0x66, 0x08, 0x94, 0x11, 0x5A, 0x00))[0]
        self.assertEqual(reading.model, "H5074")
        self.assertAlmostEqual(reading.temperature_c, 21.5, delta=0.05)
        self.assertAlmostEqual(reading.humidity_pct, 45.0, delta=0.05)
        self.assertEqual(reading.battery_pct, 90)

    def test_h5151_is_a_gateway(self):
        reading = decode("Govee_H5151_2036", 0x8801, b(0xEC, 0x00, 0x01, 0x01))[0]
        self.assertEqual(reading.model, "H5151")
        self.assertEqual(reading.kind, "GATEWAY")
        self.assertIsNone(reading.temperature_c)
        self.assertIsNone(reading.humidity_pct)

    def test_light_has_no_reading(self):
        reading = decode("Govee_H7021_2C52", 0x8802, b(0xEC, 0x00, 0x01, 0x01, 0x01))[0]
        self.assertEqual(reading.kind, "OTHER")
        self.assertEqual(reading.model, "H7021")

    def test_other_companies_are_ignored(self):
        self.assertEqual(decode("iPhone", 0x004C, b(0x02, 0x15)), [])
        self.assertEqual(decode("Tile", 0x1234, b(1, 2, 3, 4)), [])


class FrameTest(unittest.TestCase):
    def test_status_query_is_a_checksummed_read(self):
        frame = bytearray(status_query())
        self.assertEqual(len(frame), 20)
        self.assertEqual(frame[0], 0x33)
        self.assertEqual(frame[1], 0xAA)
        self.assertTrue(checksum_ok(frame))
        frame[3] = 1
        self.assertFalse(checksum_ok(frame))


if __name__ == "__main__":
    unittest.main()
