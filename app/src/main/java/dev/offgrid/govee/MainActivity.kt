package dev.offgrid.govee

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import dev.offgrid.govee.board.LiveBoard
import dev.offgrid.govee.databinding.ActivityMainBinding
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.scan.ScanService
import dev.offgrid.govee.store.ReadingStore
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceAdapter
    private var unsubscribe: (() -> Unit)? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allowed = scanPermissions().all { granted[it] == true || permitted(it) }
        if (allowed && prefs.getBoolean(WANTED, true)) {
            startScan()
        } else if (!allowed) {
            binding.status.text = getString(R.string.permission_needed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = DeviceAdapter(
            alarmFor = { address, label -> ReadingStore.get(this).alarmFor(address, label) },
            onLink = { address -> send(ScanService.ACTION_LINK, address) },
            onAlarm = { device -> pickAlarm(device) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.scan.setOnClickListener {
            prefs.edit().putBoolean(WANTED, true).apply()
            ensureScan()
        }
        binding.stop.setOnClickListener {
            prefs.edit().putBoolean(WANTED, false).apply()
            send(ScanService.ACTION_STOP, null)
        }
        binding.exportCsv.setOnClickListener { exportCsv() }
        if (prefs.getBoolean(WANTED, true)) ensureScan()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = LiveBoard.listen { render(it) }
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    private fun render(state: LiveBoard.UiState) {
        val count = if (state.devices.size == 1) "1 device" else "${state.devices.size} devices"
        binding.status.text = "${state.status}   ·   $count"
        binding.empty.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(state.devices)
    }

    private fun ensureScan() {
        if (scanPermissions().all { permitted(it) }) {
            askToEnableBluetooth()
            startScan()
        } else {
            permissionLauncher.launch(neededPermissions())
        }
    }

    private fun startScan() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    private fun send(action: String, address: String?) {
        val intent = Intent(this, ScanService::class.java).setAction(action)
        if (address != null) intent.putExtra(ScanService.EXTRA_ADDRESS, address)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun askToEnableBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= 31 && !permitted(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            binding.status.text = "Bluetooth is off"
        }
    }

    private fun pickAlarm(device: LiveBoard.Device) {
        val readings = device.readings.filter { ReadingText.temperatures(it).isNotEmpty() }
        if (readings.isEmpty()) return
        if (readings.size == 1) {
            editAlarm(device.address, readings[0].label)
            return
        }
        val labels = readings.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(device.name.ifBlank { device.address })
            .setItems(labels) { _, which -> editAlarm(device.address, readings[which].label) }
            .show()
    }

    private fun editAlarm(address: String, label: String) {
        val existing = ReadingStore.get(this).alarmFor(address, label)
        val high = field(getString(R.string.high_hint), existing?.highC)
        val low = field(getString(R.string.low_hint), existing?.lowC)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(low)
            addView(high)
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(column)
            .setPositiveButton(R.string.save) { _, _ ->
                ReadingStore.get(this).setAlarm(address, label, low.number(), high.number())
                adapter.submit(LiveBoard.snapshot().devices)
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                ReadingStore.get(this).setAlarm(address, label, null, null)
                adapter.submit(LiveBoard.snapshot().devices)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportCsv() {
        val store = ReadingStore.get(this)
        if (!store.hasRows()) {
            binding.status.text = getString(R.string.export_empty)
            return
        }
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "govee-history.csv")
        file.writeText(store.csv())
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.export_csv)))
    }

    private fun field(hint: String, value: Double?): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(value?.let { String.format(Locale.US, "%.1f", it) }.orEmpty())
        }
    }

    private fun EditText.number(): Double? = text.toString().trim().toDoubleOrNull()

    private fun scanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun neededPermissions(): Array<String> {
        val all = scanPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        return all.toTypedArray()
    }

    private fun permitted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS = "govee"
        private const val WANTED = "scanning"
    }
}
