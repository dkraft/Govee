package dev.offgrid.govee.history

import android.content.Context
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.Reading
import dev.offgrid.govee.store.ReadingStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The only HTTP caller. It opens 10.0.0.69 and no other host.
 * Android's network security config does not pin cleartext to an IP
 * literal, so this class is the allowlist.
 */
class HistoryClient {
    fun gatewayState(token: String): String = get("/health", token).optString("gateway", "down")

    fun bound(token: String): Set<String> {
        val array = get("/v1/bound", token).getJSONArray("bound")
        val out = HashSet<String>()
        for (index in 0 until array.length()) {
            out += array.getJSONObject(index).getString("address")
        }
        return out
    }

    fun latest(token: String): List<RemoteSample> {
        val array = get("/v1/latest", token).getJSONArray("devices")
        val out = ArrayList<RemoteSample>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            out += RemoteSample(
                address = item.getString("address"),
                name = item.optString("name"),
                model = item.optString("model"),
                label = item.optString("label"),
                seenAt = item.optLong("seen_at"),
                temperatureC = item.optionalDouble("temp_c"),
                humidityPct = item.optionalDouble("humidity_pct"),
                batteryPct = item.optionalInt("battery_pct"),
                co2Ppm = item.optionalInt("co2_ppm"),
                pm25 = item.optionalInt("pm25"),
                source = item.optString("source"),
            )
        }
        return out
    }

    fun post(token: String, rows: List<ReadingStore.Pending>): Boolean {
        if (rows.isEmpty()) return true
        val readings = JSONArray()
        rows.forEach { row ->
            val item = JSONObject()
                .put("address", row.address)
                .put("name", row.name)
                .put("model", row.model)
                .put("label", row.label)
                .put("source", "phone")
                .put("seen_at", row.seenAt)
                .put("extra", row.extra)
            row.temp?.let { item.put("temp_c", it) }
            row.humidity?.let { item.put("humidity_pct", it) }
            row.battery?.let { item.put("battery_pct", it) }
            row.co2?.let { item.put("co2_ppm", it) }
            row.pm25?.let { item.put("pm25", it) }
            readings.put(item)
        }
        val body = JSONObject().put("readings", readings)
        val code = send("POST", "/v1/readings", token, body.toString())
        return code in 200..299
    }

    fun series(token: String, address: String, label: String, metric: String, range: String): List<Point> {
        val path = "/v1/series?address=${encode(address)}&label=${encode(label)}&metric=${encode(metric)}&range=${encode(range)}"
        val array = get(path, token).getJSONArray("points")
        val out = ArrayList<Point>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            out += Point(
                t = item.getLong("t"),
                min = item.getDouble("min"),
                max = item.getDouble("max"),
                avg = item.getDouble("avg"),
            )
        }
        return out
    }

    private fun get(path: String, token: String): JSONObject {
        val connection = open(path, token, "GET")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HistoryException(code, text.ifBlank { "HTTP $code" })
            JSONObject(text.ifBlank { "{}" })
        } finally {
            connection.disconnect()
        }
    }

    private fun send(method: String, path: String, token: String, body: String): Int {
        val connection = open(path, token, method)
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HistoryException(code, text.ifBlank { "HTTP $code" })
            }
            code
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, token: String, method: String): HttpURLConnection {
        check(HOST == "10.0.0.69") { "history host is fixed" }
        val connection = URL("http", HOST, PORT, path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 4_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    companion object {
        const val HOST = "10.0.0.69"
        const val PORT = 8787
        const val PREFS = "govee"
        const val TOKEN = "history_token"

        val RANGES = listOf(
            "3m" to "3 min",
            "30m" to "30 min",
            "3d" to "3 day",
            "3mo" to "3 month",
            "1y" to "1 year",
            "3y" to "3 year",
        )

        val METRICS = listOf("temp_c", "humidity_pct", "co2_ppm", "pm25", "probe_1", "probe_2", "probe_3", "probe_4")

        fun metricLabel(metric: String): String = when (metric) {
            "temp_c" -> "Temperature °F"
            "humidity_pct" -> "Humidity %"
            "co2_ppm" -> "CO2 ppm"
            "pm25" -> "PM2.5"
            else -> if (metric.startsWith("probe_")) "Probe ${metric.removePrefix("probe_")} °F" else metric
        }

        fun token(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, "").orEmpty()

        fun saveToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

data class RemoteSample(
    val address: String,
    val name: String,
    val model: String,
    val label: String,
    val seenAt: Long,
    val temperatureC: Double?,
    val humidityPct: Double?,
    val batteryPct: Int?,
    val co2Ppm: Int?,
    val pm25: Int?,
    val source: String,
) {
    fun toReading(): Reading = Reading(
        model = model.ifBlank { "Govee" },
        kind = Kind.SENSOR,
        label = label.ifBlank { "Sensor" },
        temperatureC = temperatureC,
        humidityPct = humidityPct,
        batteryPct = batteryPct,
        co2Ppm = co2Ppm,
        pm25 = pm25,
    )
}

data class Point(val t: Long, val min: Double, val max: Double, val avg: Double)

class HistoryException(val code: Int, message: String) : Exception(message)

private fun JSONObject.optionalDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else getDouble(name)

private fun JSONObject.optionalInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getDouble(name).toInt()
