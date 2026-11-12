package dev.offgrid.govee.scan

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.offgrid.govee.MainActivity
import dev.offgrid.govee.R
import dev.offgrid.govee.board.LiveBoard
import dev.offgrid.govee.decode.GoveeDecoder
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.Reading
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.history.HistoryClient
import dev.offgrid.govee.history.HistoryException
import dev.offgrid.govee.store.BoundDevices
import dev.offgrid.govee.store.PhoneUpload
import dev.offgrid.govee.store.ReadingStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground BLE scan. Bound sensors are left to the Pi. Unbound sensor
 * readings are stored on the phone and posted to 10.0.0.69.
 */
class ScanService : Service() {
    private var scanning = false
    private val alarming = HashSet<String>()
    private val store by lazy { ReadingStore.get(this) }
    private val client = HistoryClient()
    private val sync = Executors.newSingleThreadScheduledExecutor()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            LiveBoard.setScanning(false, "Scan failed ($errorCode)")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScan()
                BluetoothAdapter.STATE_OFF -> {
                    stopScanQuiet()
                    LiveBoard.setScanning(false, "Bluetooth is off")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        sync.scheduleWithFixedDelay({ syncOnce() }, 3, 30, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopScanQuiet()
                LiveBoard.setScanning(false, "Stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startScan()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        sync.shutdownNow()
        stopScanQuiet()
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = adapter()
        if (adapter == null) {
            LiveBoard.setScanning(false, "This phone has no Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            LiveBoard.setScanning(false, "Bluetooth is off")
            return
        }
        if (!scanPermitted()) {
            LiveBoard.setScanning(false, "Bluetooth permission is missing")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            LiveBoard.setScanning(false, "BLE scan is unavailable")
            return
        }
        if (scanning) {
            LiveBoard.setScanning(true, "Scanning")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            LiveBoard.setScanning(true, "Scanning")
        } catch (error: SecurityException) {
            LiveBoard.setScanning(false, error.message ?: "Scan was blocked")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanQuiet() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val name = record.deviceName ?: result.device.name.orEmpty()
        val found = mutableListOf<Reading>()
        val manufacturer = record.manufacturerSpecificData
        if (manufacturer != null) {
            for (index in 0 until manufacturer.size()) {
                val payload = manufacturer.valueAt(index) ?: continue
                found += GoveeDecoder.decode(name, manufacturer.keyAt(index), payload)
            }
        }
        if (found.isEmpty()) {
            found += GoveeDecoder.decode(name, 0, ByteArray(0))
        }
        if (found.isEmpty()) return
        val seenAt = System.currentTimeMillis()
        val address = result.device.address
        val sensors = found.filter { it.kind == Kind.SENSOR }
        if (sensors.isNotEmpty() && !PhoneUpload.shouldKeep(address, BoundDevices.snapshot())) {
            return
        }
        LiveBoard.upsert(address, name, result.rssi, seenAt, found)
        found.forEach { reading ->
            if (reading.kind == Kind.SENSOR && !PhoneUpload.shouldKeep(address, BoundDevices.snapshot())) return@forEach
            store.record(address, name, result.rssi, seenAt, reading)
            considerAlarm(address, name, reading)
        }
    }

    private fun syncOnce() {
        val token = HistoryClient.token(this)
        if (token.isBlank()) {
            LiveBoard.setHistory("history needs a token")
            return
        }
        try {
            val gateway = client.gatewayState(token)
            val bound = client.bound(token)
            BoundDevices.replace(bound)
            val latest = client.latest(token).filter { it.address in bound }
            LiveBoard.applyBound(latest)
            LiveBoard.noteGateways("Pi gateway: $gateway")
            latest.forEach { sample -> considerAlarm(sample.address, sample.name, sample.toReading()) }
            val pending = store.pending(200)
            if (pending.isNotEmpty() && client.post(token, pending)) {
                store.markUploaded(pending.map { it.id })
            }
            val noun = if (bound.size == 1) "1 bound" else "${bound.size} bound"
            LiveBoard.setHistory("history up · $noun · gateway $gateway")
        } catch (error: HistoryException) {
            LiveBoard.setHistory(if (error.code == 401) "history unauthorized" else "history unreachable")
        } catch (_: Exception) {
            LiveBoard.setHistory("history unreachable")
        }
    }

    private fun considerAlarm(address: String, name: String, reading: Reading) {
        if (reading.kind != Kind.SENSOR) return
        val alarm = store.alarmFor(address, reading.label) ?: return
        val values = ReadingText.temperatures(reading)
        if (values.isEmpty()) return
        val hot = alarm.highC != null && values.any { it > alarm.highC }
        val cold = alarm.lowC != null && values.any { it < alarm.lowC }
        val key = "$address|${reading.label}"
        if (hot || cold) {
            if (alarming.add(key)) {
                val value = values.first()
                notifyAlarm(address, name, reading, value, alarm)
            }
        } else {
            alarming.remove(key)
        }
    }

    private fun notifyAlarm(
        address: String,
        name: String,
        reading: Reading,
        value: Double,
        alarm: ReadingStore.Alarm,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val bounds = listOfNotNull(
            alarm.lowC?.let { "low %.1fC".format(it) },
            alarm.highC?.let { "high %.1fC".format(it) },
        ).joinToString(" ")
        val text = String.format(java.util.Locale.US, "%s %.1fC is outside %s", reading.label, value, bounds)
        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name.ifBlank { reading.model })
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val id = (address + reading.label).hashCode()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun startInForeground() {
        val notification = scanningNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SCAN_NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(SCAN_NOTIFICATION, notification)
        }
    }

    private fun scanningNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SCAN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.scanning_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val scan = NotificationChannel(
            SCAN_CHANNEL,
            getString(R.string.scan_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val alarm = NotificationChannel(
            ALARM_CHANNEL,
            getString(R.string.alarm_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(scan)
        manager.createNotificationChannel(alarm)
    }

    private fun adapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun scanPermitted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 31) {
            android.Manifest.permission.BLUETOOTH_SCAN
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_STOP = "dev.offgrid.govee.STOP"
        const val EXTRA_ADDRESS = "address"
        private const val SCAN_CHANNEL = "scan"
        private const val ALARM_CHANNEL = "alarms"
        private const val SCAN_NOTIFICATION = 1
    }
}
