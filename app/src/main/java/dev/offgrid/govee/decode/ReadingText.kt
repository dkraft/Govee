package dev.offgrid.govee.decode

object ReadingText {
    fun block(reading: Reading): String {
        val bits = mutableListOf<String>()
        reading.temperatureC?.let { bits += temp(it) }
        reading.humidityPct?.let { bits += "${one(it)}%" }
        reading.batteryPct?.let { bits += "bat $it%" }
        reading.co2Ppm?.let { bits += "$it ppm CO2" }
        reading.pm25?.let { bits += "PM2.5 $it" }
        reading.occupied?.let { bits += if (it) "occupied" else "clear" }
        if (reading.motion == true) bits += "motion"
        reading.windowOpen?.let { bits += if (it) "open" else "closed" }
        if (reading.events.isNotEmpty()) bits += reading.events.joinToString(" ")
        val probes = reading.probes.joinToString("\n") { probe ->
            val alarms = listOfNotNull(
                probe.highAlarmC?.let { "high ${temp(it)}" },
                probe.lowAlarmC?.let { "low ${temp(it)}" },
            )
            val tail = if (alarms.isEmpty()) "" else "  " + alarms.joinToString(" ")
            "probe ${probe.id}  ${temp(probe.temperatureC)}$tail"
        }
        val head = if (reading.kind == Kind.GATEWAY) {
            reading.label
        } else {
            "${reading.model}  ${reading.label}"
        }
        return listOf(head, bits.joinToString("  "), probes)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    fun temperatures(reading: Reading): List<Double> {
        val values = mutableListOf<Double>()
        reading.temperatureC?.let { values += it }
        reading.probes.forEach { values += it.temperatureC }
        return values
    }

    fun age(seenAt: Long, now: Long): String {
        val seconds = ((now - seenAt) / 1000).coerceAtLeast(0)
        return when {
            seconds < 5 -> "now"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }

    fun fahrenheit(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0

    fun celsius(fahrenheit: Double): Double = (fahrenheit - 32.0) * 5.0 / 9.0

    fun temp(celsius: Double): String = "${one(fahrenheit(celsius))}F"

    private fun one(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
