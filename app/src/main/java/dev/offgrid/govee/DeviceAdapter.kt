package dev.offgrid.govee

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.offgrid.govee.board.LiveBoard
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.Reading
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.decode.Vpd
import dev.offgrid.govee.databinding.ItemDeviceBinding
import dev.offgrid.govee.store.ReadingStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DeviceAdapter(
    initialOrder: List<String>,
    private val alarmFor: (address: String, label: String) -> ReadingStore.Alarm?,
    private val isWide: (address: String) -> Boolean,
    private val onOpen: (LiveBoard.Device) -> Unit,
    private val onAlarm: (LiveBoard.Device) -> Unit,
    private val onRename: (LiveBoard.Device) -> Unit,
    private val onToggleWide: (address: String) -> Unit,
    private val onDrag: (Holder) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {
    private val devices = mutableListOf<LiveBoard.Device>()
    private var order = initialOrder.toMutableList()
    private var pending: List<LiveBoard.Device>? = null
    var dragging = false

    init {
        setHasStableIds(true)
    }

    fun submit(next: List<LiveBoard.Device>) {
        if (dragging) {
            val fresh = next.associateBy { it.address }
            for (index in devices.indices) {
                fresh[devices[index].address]?.let { devices[index] = it }
            }
            pending = next
            return
        }
        apply(next)
    }

    fun flush() {
        val next = pending ?: return
        pending = null
        apply(next)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in devices.indices || to !in devices.indices) return
        val item = devices.removeAt(from)
        devices.add(to, item)
        order = devices.map { it.address }.toMutableList()
        notifyItemMoved(from, to)
    }

    fun addresses(): List<String> = devices.map { it.address }

    fun isWide(position: Int): Boolean {
        val address = devices.getOrNull(position)?.address ?: return false
        return isWide(address)
    }

    override fun getItemId(position: Int): Long = devices[position].address.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = devices.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(devices[position])
    }

    private fun apply(next: List<LiveBoard.Device>) {
        val arranged = TileOrder.arrange(next.map { it.address }, order)
        val byAddress = next.associateBy { it.address }
        val ordered = arranged.mapNotNull { byAddress[it] }
        val same = devices.map { it.address } == ordered.map { it.address }
        order = ordered.map { it.address }.toMutableList()
        devices.clear()
        devices.addAll(ordered)
        if (same) {
            notifyItemRangeChanged(0, devices.size)
        } else {
            notifyDataSetChanged()
        }
    }

    inner class Holder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: LiveBoard.Device) {
            val now = System.currentTimeMillis()
            val stale = now - device.seenAt > LiveBoard.STALE_MS
            binding.root.alpha = if (stale) 0.45f else 1f
            binding.name.text = device.shownName()
            binding.meta.text = if (stale) {
                binding.root.context.getString(R.string.offline)
            } else {
                clock(device.seenAt)
            }
            val battery = device.readings.firstNotNullOfOrNull { it.batteryPct }
            binding.battery.visibility = if (battery == null) View.GONE else View.VISIBLE
            binding.battery.text = battery?.let { "$it%" }.orEmpty()

            val primary = device.readings.firstOrNull { it.temperatureC != null || it.humidityPct != null }
            val temp = primary?.temperatureC
            val humidity = primary?.humidityPct
            binding.metrics.visibility = if (primary == null) View.GONE else View.VISIBLE
            binding.temp.visibility = if (temp == null) View.GONE else View.VISIBLE
            binding.temp.text = temp?.let {
                String.format(Locale.US, "%.1f°F", ReadingText.fahrenheit(it))
            }.orEmpty()
            binding.humidity.visibility = if (humidity == null) View.GONE else View.VISIBLE
            binding.humidity.text = humidity?.let { "${one(it)}%" }.orEmpty()
            if (temp != null && humidity != null) {
                binding.vpd.visibility = View.VISIBLE
                binding.vpd.text = String.format(Locale.US, "VPD %.2f kPa", Vpd.kpa(temp, humidity))
            } else {
                binding.vpd.visibility = View.GONE
            }

            val extra = detail(device)
            binding.extra.visibility = if (extra.isBlank()) View.GONE else View.VISIBLE
            binding.extra.text = extra

            val sensor = device.readings.any { it.kind == Kind.SENSOR }
            binding.root.setOnClickListener { if (sensor) onOpen(device) }
            binding.root.setOnLongClickListener {
                onRename(device)
                true
            }
            binding.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onDrag(this)
                true
            }
            val hasTemp = device.readings.any { ReadingText.temperatures(it).isNotEmpty() }
            binding.alarm.visibility = if (hasTemp) View.VISIBLE else View.GONE
            binding.alarm.setOnClickListener { onAlarm(device) }
            val wide = isWide(device.address)
            binding.width.text = binding.root.context.getString(if (wide) R.string.tile_half else R.string.tile_wide)
            binding.width.setOnClickListener { onToggleWide(device.address) }
        }
    }

    private fun detail(device: LiveBoard.Device): String {
        val lines = mutableListOf<String>()
        device.readings.forEach { reading -> lines += aside(reading, device.readings.size > 1) }
        device.readings.forEach { reading ->
            val alarm = alarmFor(device.address, reading.label) ?: return@forEach
            val parts = listOfNotNull(
                alarm.lowC?.let { "low ${ReadingText.temp(it)}" },
                alarm.highC?.let { "high ${ReadingText.temp(it)}" },
            )
            if (parts.isNotEmpty()) lines += "Alarm  ${reading.label}  ${parts.joinToString("  ")}"
        }
        if (device.fromBound) lines += "From the Pi"
        device.gatewayNote?.let { lines += it }
        return lines.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun aside(reading: Reading, named: Boolean): String {
        val bits = mutableListOf<String>()
        if (named && (reading.temperatureC != null || reading.humidityPct != null || reading.probes.isNotEmpty())) {
            bits += reading.label
        }
        reading.co2Ppm?.let { bits += "$it ppm CO2" }
        reading.pm25?.let { bits += "PM2.5 $it" }
        reading.occupied?.let { bits += if (it) "occupied" else "clear" }
        if (reading.motion == true) bits += "motion"
        reading.windowOpen?.let { bits += if (it) "open" else "closed" }
        if (reading.events.isNotEmpty()) bits += reading.events.joinToString(" ")
        reading.probes.forEach { probe ->
            val alarms = listOfNotNull(
                probe.highAlarmC?.let { "high ${ReadingText.temp(it)}" },
                probe.lowAlarmC?.let { "low ${ReadingText.temp(it)}" },
            )
            val tail = if (alarms.isEmpty()) "" else "  " + alarms.joinToString(" ")
            bits += "probe ${probe.id}  ${ReadingText.temp(probe.temperatureC)}$tail"
        }
        if (reading.temperatureC == null && reading.humidityPct == null && reading.probes.isEmpty()) {
            val head = if (reading.kind == Kind.GATEWAY) reading.label else "${reading.model}  ${reading.label}"
            bits += head
        }
        return bits.joinToString("  ")
    }

    private fun clock(seenAt: Long): String =
        Instant.ofEpochMilli(seenAt).atZone(ZoneId.systemDefault()).format(CLOCK)

    private fun one(value: Double): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
    }
}
