package dev.offgrid.govee.board

import android.os.Handler
import android.os.Looper
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.Reading
import dev.offgrid.govee.history.RemoteSample
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process view of the newest advertisement from each device.
 * The scan service and the screen share this. Nothing here leaves the phone.
 */
object LiveBoard {
    const val STALE_MS = 120_000L

    data class Device(
        val address: String,
        val name: String,
        val rssi: Int,
        val seenAt: Long,
        val readings: List<Reading>,
        val gatewayNote: String? = null,
        val fromBound: Boolean = false,
        val alias: String = "",
    ) {
        fun shownName(): String = alias.ifBlank { name }.ifBlank { address }
    }

    data class UiState(
        val scanning: Boolean,
        val status: String,
        val history: String,
        val devices: List<Device>,
    )

    private val devices = LinkedHashMap<String, Device>()
    private val aliases = HashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false
    private var status = "Idle"
    @Volatile private var historyNote = ""

    fun upsert(address: String, name: String, rssi: Int, seenAt: Long, readings: List<Reading>) {
        synchronized(devices) {
            val prev = devices[address]
            val keptName = name.ifBlank { prev?.name.orEmpty() }
            devices[address] = Device(
                address = address,
                name = keptName,
                rssi = rssi,
                seenAt = seenAt,
                readings = readings,
                gatewayNote = prev?.gatewayNote,
                fromBound = false,
                alias = aliases[address.uppercase()].orEmpty(),
            )
        }
        publish()
    }

    fun note(address: String, text: String) {
        synchronized(devices) {
            val prev = devices[address] ?: return
            devices[address] = prev.copy(gatewayNote = text)
        }
        publish()
    }

    fun setScanning(active: Boolean, text: String) {
        scanning = active
        status = text
        publish()
    }

    fun noteGateways(text: String) {
        synchronized(devices) {
            devices.entries.forEach { (address, device) ->
                if (device.readings.any { it.kind == Kind.GATEWAY }) {
                    devices[address] = device.copy(gatewayNote = text)
                }
            }
        }
        publish()
    }

    fun setHistory(text: String) {
        historyNote = text
        publish()
    }

    fun applyBound(samples: List<RemoteSample>) {
        synchronized(devices) {
            val addresses = samples.map { it.address }.toSet()
            val iterator = devices.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.fromBound && entry.key !in addresses) iterator.remove()
            }
            samples.groupBy { it.address }.forEach { (address, rows) ->
                val first = rows.first()
                val previous = devices[address]
                // A sensor the phone is hearing stays on the live advertisement.
                // Stored rows fill in the sensors this phone is not hearing.
                if (previous != null && !previous.fromBound) return@forEach
                devices[address] = Device(
                    address = address,
                    name = first.name.ifBlank { first.model.ifBlank { previous?.name.orEmpty() } },
                    rssi = previous?.rssi ?: 0,
                    seenAt = rows.maxOf { it.seenAt },
                    readings = rows.map { it.toReading() },
                    gatewayNote = previous?.gatewayNote,
                    fromBound = true,
                    alias = aliases[address.uppercase()].orEmpty(),
                )
            }
        }
        publish()
    }

    fun applyAliases(names: Map<String, String>) {
        synchronized(devices) {
            aliases.clear()
            names.forEach { (address, alias) ->
                if (alias.isNotBlank()) aliases[address.uppercase()] = alias
            }
            devices.entries.forEach { (address, device) ->
                devices[address] = device.copy(alias = aliases[address.uppercase()].orEmpty())
            }
        }
        publish()
    }

    fun rememberAlias(address: String, alias: String) {
        synchronized(devices) {
            val key = address.uppercase()
            if (alias.isBlank()) aliases.remove(key) else aliases[key] = alias
            devices[address]?.let { devices[address] = it.copy(alias = alias) }
        }
        publish()
    }

    fun displayName(address: String, fallback: String): String {
        val alias = synchronized(devices) { aliases[address.uppercase()].orEmpty() }
        return alias.ifBlank { fallback }.ifBlank { address }
    }

    fun listen(listener: (UiState) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot())
        return {
            listeners -= listener
        }
    }

    fun snapshot(): UiState {
        val copy = synchronized(devices) { devices.values.toList() }
        return UiState(
            scanning = scanning,
            status = status,
            history = historyNote,
            devices = copy.sortedWith(
                compareBy<Device> { it.fromBound }
                    .thenByDescending { it.rssi }
                    .thenByDescending { it.seenAt },
            ),
        )
    }

    private fun publish() {
        val state = snapshot()
        main.post { listeners.forEach { it(state) } }
    }
}
