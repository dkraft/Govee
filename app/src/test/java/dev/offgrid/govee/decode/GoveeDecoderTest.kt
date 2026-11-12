package dev.offgrid.govee.decode

import dev.offgrid.govee.gateway.GatewayFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoveeDecoderTest {
    @Test
    fun h5075ShowsTemperatureHumidityAndBattery() {
        val reading = GoveeDecoder.decode(
            "Govee_H5075_ABCD",
            0xEC88,
            bytes(0x00, 0x03, 0x49, 0x9A, 0x50, 0x00),
        ).single()
        assertEquals("H5075", reading.model)
        assertEquals(Kind.SENSOR, reading.kind)
        assertEquals(21.5, reading.temperatureC!!, 0.05)
        assertEquals(45.0, reading.humidityPct!!, 0.05)
        assertEquals(80, reading.batteryPct)
    }

    @Test
    fun packedNegativeTemperature() {
        val reading = GoveeDecoder.decode(
            "Govee_H5075_ABCD",
            0xEC88,
            bytes(0x00, 0x80, 0xC4, 0x7C, 0x64, 0x00),
        ).single()
        assertEquals(-5.0, reading.temperatureC!!, 0.05)
        assertEquals(30.0, reading.humidityPct!!, 0.05)
        assertEquals(100, reading.batteryPct)
    }

    @Test
    fun h5074LittleEndian() {
        val reading = GoveeDecoder.decode(
            "Govee_H5074_ABCD",
            0xEC88,
            bytes(0x00, 0x66, 0x08, 0x94, 0x11, 0x5A, 0x00),
        ).single()
        assertEquals("H5074", reading.model)
        assertEquals(21.5, reading.temperatureC!!, 0.05)
        assertEquals(45.0, reading.humidityPct!!, 0.05)
        assertEquals(90, reading.batteryPct)
    }

    @Test
    fun h5151IsAGatewayAndNotAThermometer() {
        val reading = GoveeDecoder.decode(
            "Govee_H5151_2036",
            0x8801,
            bytes(0xEC, 0x00, 0x01, 0x01),
        ).single()
        assertEquals("H5151", reading.model)
        assertEquals(Kind.GATEWAY, reading.kind)
        assertNull(reading.temperatureC)
        assertNull(reading.humidityPct)
    }

    @Test
    fun lightIsListedWithoutAReading() {
        val reading = GoveeDecoder.decode(
            "Govee_H7021_2C52",
            0x8802,
            bytes(0xEC, 0x00, 0x01, 0x01, 0x01),
        ).single()
        assertEquals(Kind.OTHER, reading.kind)
        assertEquals("H7021", reading.model)
    }

    @Test
    fun otherCompaniesAreIgnored() {
        assertTrue(GoveeDecoder.decode("iPhone", 0x004C, bytes(0x02, 0x15)).isEmpty())
        assertTrue(GoveeDecoder.decode("Tile", 0x1234, bytes(1, 2, 3, 4)).isEmpty())
    }

    @Test
    fun statusQueryIsAChecksummedRead() {
        val frame = GatewayFrames.statusQuery()
        assertEquals(20, frame.size)
        assertEquals(0x33, frame[0].toInt() and 0xff)
        assertEquals(GatewayFrames.STATUS_COMMAND, frame[1].toInt() and 0xff)
        assertTrue(GatewayFrames.checksumOk(frame))
        frame[3] = 1
        assertFalse(GatewayFrames.checksumOk(frame))
    }

    @Test
    fun readingTextUsesFahrenheit() {
        val reading = GoveeDecoder.decode(
            "Govee_H5075_ABCD",
            0xEC88,
            bytes(0x00, 0x03, 0x49, 0x9A, 0x50, 0x00),
        ).single()
        val text = ReadingText.block(reading)
        assertFalse(text.contains("21.5C"))
        assertTrue(text.contains("70.7F"))
        assertTrue(text.contains("45.0%"))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
