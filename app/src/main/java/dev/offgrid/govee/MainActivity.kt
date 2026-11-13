package dev.offgrid.govee

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dev.offgrid.govee.board.LiveBoard
import dev.offgrid.govee.databinding.ActivityMainBinding
import dev.offgrid.govee.decode.Kind
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.history.HistoryActivity
import dev.offgrid.govee.history.HistoryClient
import dev.offgrid.govee.history.HistoryException
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
            initialOrder = loadOrder(),
            alarmFor = { address, label -> ReadingStore.get(this).alarmFor(address, label) },
            isWide = { address -> address in wideAddresses() },
            onOpen = { device -> openHistory(device) },
            onAlarm = { device -> pickAlarm(device) },
            onRename = { device -> editName(device) },
            onToggleWide = { address -> toggleWide(address) },
            onDrag = { holder -> touchHelper.startDrag(holder) },
        )
        val grid = GridLayoutManager(this, 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.isWide(position)) 2 else 1
        }
        binding.list.layoutManager = grid
        binding.list.adapter = adapter
        (binding.list.itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
        touchHelper.attachToRecyclerView(binding.list)
        binding.scan.setOnClickListener {
            prefs.edit().putBoolean(WANTED, true).apply()
            ensureScan()
        }
        binding.stop.setOnClickListener {
            prefs.edit().putBoolean(WANTED, false).apply()
            send(ScanService.ACTION_STOP, null)
        }
        binding.exportCsv.setOnClickListener { exportCsv() }
        binding.server.setOnClickListener { editToken() }
        if (!prefs.getBoolean(TOKEN_ASKED, false)) {
            prefs.edit().putBoolean(TOKEN_ASKED, true).apply()
            editToken()
        }
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
        binding.status.text = if (state.history.isBlank()) {
            "${state.status}   ·   $count"
        } else {
            "${state.status}   ·   $count\n${state.history}"
        }
        binding.empty.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(state.devices)
        if (!adapter.dragging) {
            val arranged = adapter.addresses()
            if (arranged.isNotEmpty() && arranged != loadOrder()) saveOrder(arranged)
        }
    }

    private fun toggleWide(address: String) {
        val next = wideAddresses().toMutableSet()
        if (!next.add(address)) next.remove(address)
        prefs.edit().putStringSet(TILE_WIDE, next).apply()
        spanLookup()?.invalidateSpanIndexCache()
        adapter.notifyDataSetChanged()
    }

    private fun spanLookup(): GridLayoutManager.SpanSizeLookup? =
        (binding.list.layoutManager as? GridLayoutManager)?.spanSizeLookup

    private fun loadOrder(): List<String> =
        prefs.getString(TILE_ORDER, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun saveOrder(addresses: List<String>) {
        prefs.edit().putString(TILE_ORDER, addresses.joinToString("\n")).apply()
    }

    private fun wideAddresses(): Set<String> = prefs.getStringSet(TILE_WIDE, emptySet()).orEmpty()

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            spanLookup()?.invalidateSpanIndexCache()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled(): Boolean = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                adapter.dragging = true
                viewHolder?.itemView?.translationZ = 8f * resources.displayMetrics.density
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.translationZ = 0f
            adapter.dragging = false
            adapter.flush()
            saveOrder(adapter.addresses())
            spanLookup()?.invalidateSpanIndexCache()
        }
    })

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

    private fun openHistory(device: LiveBoard.Device) {
        val readings = device.readings.filter { it.kind == Kind.SENSOR }
        if (readings.isEmpty()) return
        if (readings.size == 1) {
            startHistory(device, readings[0].label)
            return
        }
        val labels = readings.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(device.shownName())
            .setItems(labels) { _, which -> startHistory(device, readings[which].label) }
            .show()
    }

    private fun startHistory(device: LiveBoard.Device, label: String) {
        startActivity(
            Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_ADDRESS, device.address)
                .putExtra(HistoryActivity.EXTRA_LABEL, label)
                .putExtra(HistoryActivity.EXTRA_NAME, device.shownName()),
        )
    }

    private fun editToken() {
        val input = EditText(this).apply {
            hint = getString(R.string.server_hint)
            setText(HistoryClient.token(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_title)
            .setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                HistoryClient.saveToken(this, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            .setTitle(device.shownName())
            .setItems(labels) { _, which -> editAlarm(device.address, readings[which].label) }
            .show()
    }

    private fun editName(device: LiveBoard.Device) {
        val identity = TextView(this).apply {
            text = listOf(
                device.name.ifBlank { device.address },
                device.address,
                device.readings.joinToString("\n\n") { ReadingText.block(it) },
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            typeface = Typeface.MONOSPACE
            textSize = 13f
        }
        val input = EditText(this).apply {
            hint = getString(R.string.name_hint)
            setText(device.alias)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(identity)
            addView(input)
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * resources.displayMetrics.density).toInt(),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_title)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                saveName(device.address, input.text.toString())
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                saveName(device.address, "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveName(address: String, raw: String) {
        val token = HistoryClient.token(this)
        if (token.isBlank()) {
            binding.status.text = getString(R.string.name_needs_token)
            return
        }
        val name = raw.trim().replace(Regex("\\s+"), " ").take(48)
        Thread {
            try {
                HistoryClient().saveName(token, address, name)
                LiveBoard.rememberAlias(address, name)
            } catch (error: HistoryException) {
                val text = if (error.code == 401) {
                    getString(R.string.history_unauthorized)
                } else {
                    getString(R.string.history_down)
                }
                LiveBoard.setHistory(text)
            } catch (_: Exception) {
                LiveBoard.setHistory(getString(R.string.history_down))
            }
        }.start()
    }

    private fun editAlarm(address: String, label: String) {
        val existing = ReadingStore.get(this).alarmFor(address, label)
        val high = field(getString(R.string.high_hint), existing?.highC?.let(ReadingText::fahrenheit))
        val low = field(getString(R.string.low_hint), existing?.lowC?.let(ReadingText::fahrenheit))
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
                ReadingStore.get(this).setAlarm(
                    address,
                    label,
                    high.number()?.let(ReadingText::celsius),
                    low.number()?.let(ReadingText::celsius),
                )
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
        private const val TOKEN_ASKED = "token_asked"
        private const val TILE_ORDER = "tile_order"
        private const val TILE_WIDE = "tile_wide"
    }
}
