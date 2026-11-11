package dev.offgrid.govee.board

import android.os.Handler
import android.os.Looper
import dev.offgrid.govee.decode.Reading
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
    )

    data class UiState(
        val scanning: Boolean,
        val status: String,
        val devices: List<Device>,
    )

    private val devices = LinkedHashMap<String, Device>()
    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false
    private var status = "Idle"

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
            devices = copy.sortedByDescending { it.rssi },
        )
    }

    private fun publish() {
        val state = snapshot()
        main.post { listeners.forEach { it(state) } }
    }
}
