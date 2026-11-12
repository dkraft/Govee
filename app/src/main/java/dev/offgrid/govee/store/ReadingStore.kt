package dev.offgrid.govee.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.Reading
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * On-device history and local high/low alarms. Rows older than 14 days
 * are deleted as new ones are written.
 */
class ReadingStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "govee.db", null, 2) {

    data class Alarm(val highC: Double?, val lowC: Double?)

    data class Pending(
        val id: Long,
        val address: String,
        val name: String,
        val model: String,
        val label: String,
        val seenAt: Long,
        val temp: Double?,
        val humidity: Double?,
        val battery: Int?,
        val co2: Int?,
        val pm25: Int?,
        val extra: String,
    )

    private val lastWrite = HashMap<String, Write>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                name TEXT,
                model TEXT,
                label TEXT,
                temp REAL,
                humidity REAL,
                battery INTEGER,
                co2 INTEGER,
                pm25 INTEGER,
                extra TEXT,
                rssi INTEGER,
                seen_at INTEGER NOT NULL,
                upload_state INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX readings_seen ON readings(seen_at)")
        db.execSQL(
            """
            CREATE TABLE alarms (
                address TEXT NOT NULL,
                label TEXT NOT NULL,
                high_c REAL,
                low_c REAL,
                PRIMARY KEY(address, label)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE readings ADD COLUMN upload_state INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                UPDATE readings SET upload_state = -1
                WHERE temp IS NULL AND humidity IS NULL AND co2 IS NULL AND pm25 IS NULL
                  AND (extra IS NULL OR extra = '')
                """.trimIndent(),
            )
        }
    }

    fun record(
        address: String,
        name: String,
        rssi: Int,
        seenAt: Long,
        reading: Reading,
    ) {
        val key = "$address|${reading.model}|${reading.label}"
        val previous = synchronized(lastWrite) { lastWrite[key] }
        val changed = previous == null ||
            previous.temp != reading.temperatureC ||
            previous.humidity != reading.humidityPct ||
            seenAt - previous.at >= 30_000
        if (!changed) return
        synchronized(lastWrite) {
            lastWrite[key] = Write(reading.temperatureC, reading.humidityPct, seenAt)
        }
        val extra = buildList {
            reading.events.forEach { add(it) }
            reading.occupied?.let { add(if (it) "occupied" else "clear") }
            reading.motion?.let { if (it) add("motion") }
            reading.windowOpen?.let { add(if (it) "open" else "closed") }
            reading.probes.forEach { probe ->
                add("probe ${probe.id}=${probe.temperatureC}")
            }
        }.joinToString(";")
        val values = ContentValues().apply {
            put("address", address)
            put("name", name)
            put("model", reading.model)
            put("label", reading.label)
            put("temp", reading.temperatureC)
            put("humidity", reading.humidityPct)
            put("battery", reading.batteryPct)
            put("co2", reading.co2Ppm)
            put("pm25", reading.pm25)
            put("extra", extra)
            put("rssi", rssi)
            put("seen_at", seenAt)
            val upload = reading.kind == Kind.SENSOR && PhoneUpload.shouldKeep(address, BoundDevices.snapshot())
            put("upload_state", if (upload) 0 else -1)
        }
        writableDatabase.insert("readings", null, values)
        writableDatabase.delete("readings", "seen_at < ?", arrayOf((seenAt - RETENTION_MS).toString()))
    }

    fun alarmFor(address: String, label: String): Alarm? {
        readableDatabase.query(
            "alarms",
            arrayOf("high_c", "low_c"),
            "address = ? AND label = ?",
            arrayOf(address, label),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val high = if (cursor.isNull(0)) null else cursor.getDouble(0)
            val low = if (cursor.isNull(1)) null else cursor.getDouble(1)
            return Alarm(high, low)
        }
    }

    fun setAlarm(address: String, label: String, highC: Double?, lowC: Double?) {
        if (highC == null && lowC == null) {
            writableDatabase.delete(
                "alarms",
                "address = ? AND label = ?",
                arrayOf(address, label),
            )
            return
        }
        val values = ContentValues().apply {
            put("address", address)
            put("label", label)
            put("high_c", highC)
            put("low_c", lowC)
        }
        writableDatabase.insertWithOnConflict("alarms", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun alarms(): Map<String, Alarm> {
        val out = LinkedHashMap<String, Alarm>()
        readableDatabase.query("alarms", arrayOf("address", "label", "high_c", "low_c"), null, null, null, null, null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val high = if (cursor.isNull(2)) null else cursor.getDouble(2)
                    val low = if (cursor.isNull(3)) null else cursor.getDouble(3)
                    out["${cursor.getString(0)}|${cursor.getString(1)}"] = Alarm(high, low)
                }
            }
        return out
    }

    fun csv(): String {
        val builder = StringBuilder()
        builder.append("seen_at,address,name,model,label,temp_f,humidity_pct,battery_pct,co2_ppm,pm25,extra,rssi\n")
        readableDatabase.query(
            "readings",
            arrayOf(
                "seen_at", "address", "name", "model", "label", "temp", "humidity",
                "battery", "co2", "pm25", "extra", "rssi",
            ),
            null,
            null,
            null,
            null,
            "seen_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val stamp = Instant.ofEpochMilli(cursor.getLong(0)).atOffset(ZoneOffset.UTC).format(ISO)
                val cells = listOf(
                    stamp,
                    cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(),
                    cursor.getString(4).orEmpty(),
                    fahrenheit(cursor, 5),
                    number(cursor, 6),
                    integer(cursor, 7),
                    integer(cursor, 8),
                    integer(cursor, 9),
                    cursor.getString(10).orEmpty(),
                    integer(cursor, 11),
                )
                builder.append(cells.joinToString(",") { csvCell(it) })
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    fun pending(limit: Int): List<Pending> {
        val out = mutableListOf<Pending>()
        readableDatabase.query(
            "readings",
            arrayOf(
                "id", "address", "name", "model", "label", "seen_at",
                "temp", "humidity", "battery", "co2", "pm25", "extra",
            ),
            "upload_state = 0",
            null,
            null,
            null,
            "seen_at ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Pending(
                    id = cursor.getLong(0),
                    address = cursor.getString(1).orEmpty(),
                    name = cursor.getString(2).orEmpty(),
                    model = cursor.getString(3).orEmpty(),
                    label = cursor.getString(4).orEmpty(),
                    seenAt = cursor.getLong(5),
                    temp = if (cursor.isNull(6)) null else cursor.getDouble(6),
                    humidity = if (cursor.isNull(7)) null else cursor.getDouble(7),
                    battery = if (cursor.isNull(8)) null else cursor.getInt(8),
                    co2 = if (cursor.isNull(9)) null else cursor.getInt(9),
                    pm25 = if (cursor.isNull(10)) null else cursor.getInt(10),
                    extra = cursor.getString(11).orEmpty(),
                )
            }
        }
        return out
    }

    fun markUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        val marks = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE readings SET upload_state = 1 WHERE id IN ($marks)",
            ids.map { it.toString() }.toTypedArray(),
        )
    }

    fun hasRows(): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM readings LIMIT 1", null).use { return it.moveToFirst() }
    }

    private fun number(cursor: android.database.Cursor, index: Int): String =
        if (cursor.isNull(index)) "" else cursor.getDouble(index).toString()

    private fun fahrenheit(cursor: android.database.Cursor, index: Int): String =
        if (cursor.isNull(index)) "" else String.format(java.util.Locale.US, "%.1f", cursor.getDouble(index) * 9.0 / 5.0 + 32.0)

    private fun integer(cursor: android.database.Cursor, index: Int): String =
        if (cursor.isNull(index)) "" else cursor.getLong(index).toString()

    private fun csvCell(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private data class Write(val temp: Double?, val humidity: Double?, val at: Long)

    companion object {
        private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        @Volatile
        private var instance: ReadingStore? = null

        fun get(context: Context): ReadingStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ReadingStore(context).also { instance = it }
            }
        }
    }
}
