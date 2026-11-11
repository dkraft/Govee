package dev.offgrid.govee.decode

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Readings taken from a Govee BLE advertisement. Temperatures are Celsius.
 * One advertisement can describe more than one logical device (a display
 * plus a remote probe, or two meat probes).
 */
data class Probe(
    val id: Int,
    val temperatureC: Double,
    val highAlarmC: Double? = null,
    val lowAlarmC: Double? = null,
)

enum class Kind { SENSOR, GATEWAY, OTHER }

data class Reading(
    val model: String,
    val kind: Kind,
    val label: String,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val batteryPct: Int? = null,
    val co2Ppm: Int? = null,
    val pm25: Int? = null,
    val probes: List<Probe> = emptyList(),
    val events: List<String> = emptyList(),
    val occupied: Boolean? = null,
    val motion: Boolean? = null,
    val windowOpen: Boolean? = null,
)

/**
 * Decodes Govee advertisement payloads.
 *
 * Android's scan record hands over manufacturer data with the 16-bit company
 * id already removed. The layouts follow the public packet formats used by
 * the H5074/H5075/H510x thermo-hygrometers, the H5178/H5179 variants, the
 * H518x meat probes, the H5106 air monitor, and the encrypted H512x contact
 * sensors. The H5151 Wi-Fi gateway is recognized by name; it does not put
 * sensor values in its own advertisement.
 */
object GoveeDecoder {
    private const val MIN_TEMP = -40.0
    private const val MAX_TEMP = 100.0

    fun decode(localName: String, manufacturerId: Int, data: ByteArray): List<Reading> {
        if (manufacturerId == 0x004C) return emptyList()
        val name = localName.trim()
        if (isGatewayName(name)) {
            return listOf(Reading("H5151", Kind.GATEWAY, "Wi-Fi gateway"))
        }

        var payload = data
        if (payload.size > 25 && String(payload, Charsets.ISO_8859_1).contains("INTELLI_ROCKS")) {
            payload = payload.copyOf(payload.size - 25)
        }
        val parsed = parse(name, manufacturerId, payload)
        if (parsed.isNotEmpty()) return parsed
        if (isGoveeName(name)) {
            val model = modelFromName(name).ifEmpty { "Govee" }
            return listOf(Reading(model, Kind.OTHER, "Seen, no reading in this packet"))
        }
        return emptyList()
    }

    private fun parse(name: String, id: Int, data: ByteArray): List<Reading> {
        val n = data.size

        if (n == 24) {
            return decryptContact(name, data)
        }

        if (n == 6 &&
            (data.hasPrefix(byteArrayOf(0xec.toByte(), 0x00, 0x01, 0x01)) ||
                data.hasPrefix(byteArrayOf(0xec.toByte(), 0x00, 0x02, 0x01))) &&
            ("H5127" in name || id == 0x8803 || id == 0x8843)
        ) {
            return listOf(
                Reading(
                    model = "H5127",
                    kind = Kind.SENSOR,
                    label = "Presence",
                    occupied = data[4].toInt() == 1,
                    motion = data[5].toInt() == 17,
                ),
            )
        }

        if (n == 6 && ("H5072" in name || "H5075" in name || "H5129" in name || id == 0xEC88)) {
            val model = when {
                "H5072" in name -> "H5072"
                "H5075" in name -> "H5075"
                "H5129" in name -> "H5129"
                else -> "H5072/H5075"
            }
            return packedThermo(model, data.copyOfRange(1, 5))
        }

        if (n == 8 && ("GV5112" in name || "H5112" in name)) {
            val probe = when (data[7].toInt() and 0xff) {
                0x41 -> 1
                0x82 -> 2
                else -> return emptyList()
            }
            val (temp, humi, batt, err) = unpackPacked(data.copyOfRange(2, 6))
            val probes = if (!err && temp in MIN_TEMP..MAX_TEMP) {
                listOf(Probe(probe, temp))
            } else {
                emptyList()
            }
            val humidity = if (probe == 1 && probes.isNotEmpty()) humi else null
            return listOf(
                Reading(
                    model = "H5112",
                    kind = Kind.SENSOR,
                    label = "Probe $probe",
                    humidityPct = humidity,
                    batteryPct = batt,
                    probes = probes,
                ),
            )
        }

        if (n == 8 && "5140" in name) {
            val (temp, humi) = unpackTempHumid(data.copyOfRange(2, 5))
            val co2 = u16be(data[5], data[6])
            val err = data[7].toInt() != 0
            return if (!err && temp in MIN_TEMP..MAX_TEMP) {
                listOf(Reading("H5140", Kind.SENSOR, "Air", temp, humi, co2Ppm = co2))
            } else {
                emptyList()
            }
        }

        if (n == 6 || n == 8) {
            val named = when {
                "H5108" in name -> "H5108"
                "H5100" in name -> "H5100"
                "H5101" in name -> "H5101"
                "H5102" in name -> "H5102"
                "H5103" in name -> "H5103"
                "H5104" in name -> "H5104"
                "H5105" in name -> "H5105"
                "H5174" in name -> "H5174"
                "H5177" in name -> "H5177"
                "H5110" in name -> "H5110"
                "GV5179" in name -> "GV5179"
                n == 8 && id == 0x0001 -> "H5108"
                else -> null
            }
            if (named != null) {
                return packedThermo(named, data.copyOfRange(2, 6))
            }
        }

        if (n == 7 && ("H5074" in name || id == 0xEC88)) {
            val temp = s16le(data[1], data[2]) / 100.0
            val humi = u16le(data[3], data[4]) / 100.0
            val batt = data[5].toInt() and 0xff
            return listOf(Reading("H5074", Kind.SENSOR, "Thermo-hygrometer", temp, humi, batt))
        }

        if (n == 9 && (id == 0xEC88 || "H5051" in name || "H5052" in name || "H5071" in name)) {
            val model = when {
                "H5071" in name -> "H5071"
                "H5052" in name -> "H5052"
                else -> "H5051"
            }
            val temp = s16le(data[1], data[2]) / 100.0
            val humi = u16le(data[3], data[4]) / 100.0
            val batt = data[5].toInt() and 0xff
            return listOf(Reading(model, Kind.SENSOR, "Thermo-hygrometer", temp, humi, batt))
        }

        if (n == 9 && ("H5178" in name || "B5178" in name || id == 0x0001)) {
            val (temp, humi, batt, err) = unpackPacked(data.copyOfRange(3, 7))
            val remote = data[2].toInt() == 1
            val model = if (remote) "H5178-REMOTE" else "H5178"
            val label = if (remote) "Remote probe" else "Primary"
            val ok = !err && temp in MIN_TEMP..MAX_TEMP
            return listOf(
                Reading(
                    model = model,
                    kind = Kind.SENSOR,
                    label = label,
                    temperatureC = if (ok) temp else null,
                    humidityPct = if (ok) humi else null,
                    batteryPct = batt,
                ),
            )
        }

        if (n == 9 && ("H5179" in name || id == 0x8801)) {
            val temp = s16le(data[4], data[5]) / 100.0
            val humi = u16le(data[6], data[7]) / 100.0
            val batt = data[8].toInt() and 0xff
            return listOf(Reading("H5179", Kind.SENSOR, "Thermo-hygrometer", temp, humi, batt))
        }

        if (n == 6 && "H5106" in name) {
            val word = ByteBuffer.wrap(data, 2, 4).order(ByteOrder.BIG_ENDIAN).int
            return listOf(
                Reading(
                    model = "H5106",
                    kind = Kind.SENSOR,
                    label = "Air",
                    temperatureC = decode4Temp(word),
                    humidityPct = decode4Humi(word),
                    pm25 = (word and 0x7fffffff) % 1000,
                ),
            )
        }

        probeReading(name, id, data)?.let { return listOf(it) }
        return emptyList()
    }

    private fun probeReading(name: String, id: Int, data: ByteArray): Reading? {
        val n = data.size
        if (n == 14 && ("H5181" in name || id in setOf(0xF861, 0x388A, 0xEA42, 0xAAA2, 0xD14B))) {
            return probes("H5181", listOf(probePair(data, 8, 1)))
        }
        if (n == 17 && ("H5182" in name || id == 0x2730)) {
            return probes("H5182", listOf(probePair(data, 8, 1), probePair(data, 13, 2)))
        }
        if (n == 14 && ("H5183" in name || id in setOf(0x67DD, 0xE02F, 0xF79F))) {
            return probes("H5183", listOf(probePair(data, 8, 1)))
        }
        if (n == 17 && ("H5184" in name || id == 0x1B36)) {
            val ids = fourProbeIds(data[6].toInt() and 0xff) ?: return null
            return probes("H5184", listOf(probePair(data, 8, ids[0]), probePair(data, 13, ids[1])))
        }
        if (n == 20 && ("H5185" in name || id == 0x4A32 || id == 0x332 || id == 0x4C32)) {
            return probes("H5185", listOf(probePair(data, 8, 1), probePair(data, 14, 2)))
        }
        if (n == 20 && ("H5198" in name || id == 0x3022)) {
            val ids = fourProbeIds(data[6].toInt() and 0xff) ?: return null
            return probes(
                "H5198",
                listOf(
                    probeTriple(data, 8, ids[0]),
                    probeTriple(data, 14, ids[1]),
                ),
            )
        }
        return null
    }

    private fun probes(model: String, probes: List<Probe>) =
        Reading(model, Kind.SENSOR, "Meat probe", probes = probes.filter { it.temperatureC >= 0 })

    private fun probePair(data: ByteArray, offset: Int, id: Int): Probe {
        val temp = s16be(data[offset], data[offset + 1]) / 100.0
        val alarm = s16be(data[offset + 2], data[offset + 3]) / 100.0
        return Probe(id, temp, highAlarmC = alarm.takeIf { it >= 0 })
    }

    private fun probeTriple(data: ByteArray, offset: Int, id: Int): Probe {
        val temp = s16be(data[offset], data[offset + 1]) / 100.0
        val high = s16be(data[offset + 2], data[offset + 3]) / 100.0
        val low = s16be(data[offset + 4], data[offset + 5]) / 100.0
        return Probe(id, temp, high.takeIf { it >= 0 }, low.takeIf { it >= 0 })
    }

    private fun fourProbeIds(sensorId: Int): IntArray? = when (sensorId) {
        1, 65, 129, 193 -> intArrayOf(1, 2)
        2, 66, 130, 194 -> intArrayOf(3, 4)
        else -> null
    }

    private fun packedThermo(model: String, four: ByteArray): List<Reading> {
        val (temp, humi, batt, err) = unpackPacked(four)
        val ok = !err && temp in MIN_TEMP..MAX_TEMP
        return listOf(
            Reading(
                model = model,
                kind = Kind.SENSOR,
                label = "Thermo-hygrometer",
                temperatureC = if (ok) temp else null,
                humidityPct = if (ok) humi else null,
                batteryPct = batt,
            ),
        )
    }

    private data class Packed(val temp: Double, val humidity: Double, val battery: Int, val error: Boolean)

    private fun unpackPacked(four: ByteArray): Packed {
        val (temp, humi) = unpackTempHumid(four.copyOf(3))
        val last = four[3].toInt() and 0xff
        return Packed(temp, humi, last and 0x7f, last and 0x80 != 0)
    }

    private fun unpackTempHumid(three: ByteArray): Pair<Double, Double> {
        val base = ((three[0].toInt() and 0xff) shl 16) or
            ((three[1].toInt() and 0xff) shl 8) or
            (three[2].toInt() and 0xff)
        val negative = base and 0x800000 != 0
        val magnitude = base and 0x7fffff
        var temp = (magnitude / 1000) / 10.0
        if (negative) temp = -temp
        val humidity = (magnitude % 1000) / 10.0
        return temp to humidity
    }

    private fun decode4Temp(packet: Int): Double {
        val negative = packet and 0x80000000.toInt() != 0
        val magnitude = packet and 0x7fffffff
        val value = (magnitude / 1_000_000) / 10.0
        return if (negative) -value else value
    }

    private fun decode4Humi(packet: Int): Double {
        val magnitude = packet and 0x7fffffff
        return ((magnitude % 1_000_000) / 1000) / 10.0
    }

    private fun decryptContact(name: String, data: ByteArray): List<Reading> {
        val time = data.copyOfRange(2, 6)
        val enc = data.copyOfRange(6, 22)
        val crc = u16be(data[22], data[23])
        if (crc16(enc) != crc) return emptyList()
        val key = time + ByteArray(12)
        val clear = try {
            decrypt(key, enc)
        } catch (_: Exception) {
            return emptyList()
        }
        val modelId = clear[2].toInt() and 0xff
        val model = when {
            "GV5121" in name || modelId == 3 -> "H5121"
            "GV5122" in name || modelId == 8 -> "H5122"
            "GV5123" in name || modelId == 2 -> "H5123"
            "GV5124" in name || modelId == 9 -> "H5124"
            "GV5125" in name || modelId == 10 -> "H5125"
            "GV5126" in name || modelId == 11 -> "H5126"
            "GV5130" in name || modelId == 13 -> "H5130"
            else -> return emptyList()
        }
        val battery = clear[4].toInt() and 0xff
        val state = clear[5].toInt() and 0xff
        val events = mutableListOf<String>()
        var window: Boolean? = null
        var occupied: Boolean? = null
        when (model) {
            "H5123" -> window = state == 2
            "H5124" -> if (state == 1) events += "vibration"
            "H5121" -> if (state == 1) events += "motion"
            "H5130" -> {
                if (state and 0x10 != 0) events += "button"
                if (state != 0x10) occupied = state and 0x01 != 0
            }
            else -> if (state != 0) events += "button $state"
        }
        return listOf(
            Reading(
                model = model,
                kind = Kind.SENSOR,
                label = "Contact",
                batteryPct = battery,
                events = events,
                occupied = occupied,
                windowOpen = window,
                motion = if ("motion" in events) true else null,
            ),
        )
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0x1D0F
        for (value in data) {
            val b = value.toInt() and 0xff
            for (s in 7 downTo 0) {
                val mask = if (((crc shr 15) xor (b shr s)) and 1 == 1) 0x1021 else 0
                crc = ((crc shl 1) xor mask) and 0xffff
            }
        }
        return crc
    }

    private fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.reversedArray(), "AES"))
        return cipher.doFinal(data.reversedArray()).reversedArray()
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun isGatewayName(name: String) = "H5151" in name

    private fun isGoveeName(name: String) =
        name.startsWith("Govee", ignoreCase = true) || name.startsWith("GV")

    private fun modelFromName(name: String): String {
        val match = Regex("(H|GVH?)[0-9A-Z]{4}").find(name)
        return match?.value ?: ""
    }

    private fun u16le(b0: Byte, b1: Byte) = (b0.toInt() and 0xff) or ((b1.toInt() and 0xff) shl 8)

    private fun s16le(b0: Byte, b1: Byte): Int {
        val v = u16le(b0, b1)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun s16be(b0: Byte, b1: Byte): Int {
        val v = ((b0.toInt() and 0xff) shl 8) or (b1.toInt() and 0xff)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun u16be(b0: Byte, b1: Byte) =
        ((b0.toInt() and 0xff) shl 8) or (b1.toInt() and 0xff)
}
