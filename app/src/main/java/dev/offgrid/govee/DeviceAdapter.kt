package dev.offgrid.govee

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.offgrid.govee.board.LiveBoard
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.databinding.ItemDeviceBinding
import dev.offgrid.govee.store.ReadingStore

class DeviceAdapter(
    private val alarmFor: (address: String, label: String) -> ReadingStore.Alarm?,
    private val onOpen: (LiveBoard.Device) -> Unit,
    private val onAlarm: (LiveBoard.Device) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {
    private val devices = mutableListOf<LiveBoard.Device>()

    fun submit(next: List<LiveBoard.Device>) {
        devices.clear()
        devices.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = devices.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(devices[position])
    }

    inner class Holder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: LiveBoard.Device) {
            val now = System.currentTimeMillis()
            val stale = now - device.seenAt > LiveBoard.STALE_MS
            binding.root.alpha = if (stale) 0.45f else 1f
            binding.name.text = device.name.ifBlank { device.address }
            val age = ReadingText.age(device.seenAt, now)
            val staleLabel = if (stale) "  stale" else ""
            val where = if (device.fromBound) "history" else "${device.rssi} dBm"
            binding.meta.text = "${device.address}   $where   $age$staleLabel"
            binding.values.text = device.readings.joinToString("\n\n") { ReadingText.block(it) }
            val alarmLines = device.readings.mapNotNull { reading ->
                val alarm = alarmFor(device.address, reading.label) ?: return@mapNotNull null
                val parts = listOfNotNull(
                    alarm.lowC?.let { "low ${ReadingText.temp(it)}" },
                    alarm.highC?.let { "high ${ReadingText.temp(it)}" },
                )
                if (parts.isEmpty()) null else "Local alarm  ${reading.label}  ${parts.joinToString("  ")}"
            }
            val extra = (alarmLines + listOfNotNull(device.gatewayNote)).joinToString("\n")
            binding.extra.visibility = if (extra.isBlank()) View.GONE else View.VISIBLE
            binding.extra.text = extra
            val sensor = device.readings.any { it.kind == Kind.SENSOR }
            binding.root.setOnClickListener { if (sensor) onOpen(device) }
            val hasTemp = device.readings.any { ReadingText.temperatures(it).isNotEmpty() }
            binding.alarm.visibility = if (hasTemp) View.VISIBLE else View.GONE
            binding.alarm.setOnClickListener { onAlarm(device) }
        }
    }
}
