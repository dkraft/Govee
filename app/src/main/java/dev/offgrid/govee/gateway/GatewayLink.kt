package dev.offgrid.govee.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * One local session with an H5151-class gateway.
 *
 * Reads the standard Device Information service, and, when the Govee
 * control service is present, writes a single status query and keeps the
 * notify that comes back. It does not send power, reset, or Wi-Fi frames.
 */
@SuppressLint("MissingPermission")
class GatewayLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onReport: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val steps = ArrayDeque<() -> Unit>()
    private val info = LinkedHashMap<String, String>()
    private val replies = mutableListOf<String>()
    private var gatt: BluetoothGatt? = null
    private var controlPresent = false
    private var finished = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (finished) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Link failed (status $status). Move the phone closer to the gateway.")
                    return
                }
                gatt.discoverServices()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val heard = info.isNotEmpty() || replies.isNotEmpty() || controlPresent
                if (!heard && status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Link failed (status $status). Move the phone closer to the gateway.")
                } else {
                    finish(reportText())
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("Service discovery failed (status $status).")
                return
            }
            val dis = gatt.getService(DIS)
            INFO.forEach { (uuid, label) ->
                val characteristic = dis?.getCharacteristic(uuid) ?: return@forEach
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return@forEach
                steps += { gatt.readCharacteristic(characteristic) }
            }
            val control = gatt.getService(UUID.fromString(GatewayFrames.SERVICE))
            val notify = control?.getCharacteristic(UUID.fromString(GatewayFrames.NOTIFY))
            val write = control?.getCharacteristic(UUID.fromString(GatewayFrames.WRITE))
            if (notify != null && write != null) {
                controlPresent = true
                steps += { enableNotify(gatt, notify) }
                steps += { writeStatus(gatt, write) }
            }
            if (steps.isEmpty()) {
                gatt.disconnect()
                return
            }
            next()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val label = INFO[characteristic.uuid]
                val text = characteristic.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
                if (label != null && text.isNotEmpty()) info[label] = text
            }
            next()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            next()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = Unit

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            replies += GatewayFrames.hex(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(deviceAddress: String) {
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (_: IllegalArgumentException) {
            finish("That address is not a Bluetooth device.")
            return
        }
        gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDeviceTransport)
        main.postDelayed({
            if (!finished) finish("The gateway did not answer. Move closer and try the link again.")
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        finished = true
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCC)
        if (descriptor == null) {
            next()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    @SuppressLint("MissingPermission")
    private fun writeStatus(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = GatewayFrames.statusQuery()
        val started = gatt.writeCharacteristic(characteristic)
        if (!started) {
            replies += "status query was not accepted"
            gatt.disconnect()
            return
        }
        main.postDelayed({
            if (!finished) gatt.disconnect()
        }, 3500)
    }

    private fun next() {
        val step = steps.pollFirst()
        if (step == null) {
            gatt?.disconnect()
            return
        }
        step()
    }

    private fun reportText(): String {
        val lines = mutableListOf<String>()
        if (info.isEmpty()) {
            lines += "No device-information values."
        } else {
            info.forEach { (label, value) -> lines += "$label: $value" }
        }
        lines += if (controlPresent) {
            "Local control service is present. A status query was sent."
        } else {
            "No local control service. This gateway uplinks over Wi-Fi. The phone is reading the sensors itself."
        }
        if (replies.isNotEmpty()) {
            lines += "Reply: " + replies.joinToString(" | ")
        }
        return lines.joinToString("\n")
    }

    private fun finish(text: String) {
        if (finished) return
        finished = true
        val held = gatt
        gatt = null
        main.post {
            onReport(text)
            held?.close()
        }
    }

    companion object {
        private val DIS: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val INFO = linkedMapOf(
            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb") to "Manufacturer",
            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb") to "Model",
            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb") to "Serial",
            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb") to "Hardware",
            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb") to "Firmware",
            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb") to "Software",
        )
        private const val BluetoothDeviceTransport = 2
    }
}
