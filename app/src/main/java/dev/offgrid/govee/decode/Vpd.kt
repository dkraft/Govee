package dev.offgrid.govee.decode

import kotlin.math.exp

/**
 * Air vapor-pressure deficit in kPa.
 *
 * Saturation vapor pressure uses the Magnus-Tetens equation, then
 * VPD = SVP × (1 − RH/100). This is the air value at the sensor.
 * It does not assume a leaf temperature.
 */
object Vpd {
    fun kpa(celsius: Double, humidityPct: Double): Double {
        val svp = 0.6108 * exp(17.27 * celsius / (celsius + 237.3))
        val rh = humidityPct.coerceIn(0.0, 100.0)
        return svp * (1.0 - rh / 100.0)
    }
}
