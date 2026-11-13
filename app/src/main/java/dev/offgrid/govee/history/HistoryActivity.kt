package dev.offgrid.govee.history

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.offgrid.govee.R
import dev.offgrid.govee.databinding.ActivityHistoryBinding
import dev.offgrid.govee.decode.ReadingText
import dev.offgrid.govee.decode.Vpd
import java.util.Locale
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val io = Executors.newSingleThreadExecutor()
    private val client = HistoryClient()
    private var range = "3d"
    private var generation = 0
    private val rangeButtons = LinkedHashMap<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.text = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "History" }
        binding.subtitle.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        HistoryClient.RANGES.forEach { (id, label) ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    range = id
                    paintRanges()
                    load()
                }
            }
            val pad = (8 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = pad
            binding.ranges.addView(button, params)
            rangeButtons[id] = button
        }
        paintRanges()
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun paintRanges() {
        rangeButtons.forEach { (id, button) ->
            val selected = id == range
            button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.onAccent else R.color.text))
            button.setBackgroundColor(ContextCompat.getColor(this, if (selected) R.color.accent else R.color.card))
        }
    }

    private fun load() {
        val token = HistoryClient.token(this)
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val ticket = ++generation
        val selected = range
        binding.status.text = getString(R.string.history_loading)
        io.execute {
            val loaded: Pair<List<Pair<String, List<Point>>>?, HistoryException?> = try {
                val series = HistoryClient.METRICS.map { metric ->
                    metric to client.series(token, address, label, metric, selected)
                }.filter { it.second.isNotEmpty() }
                series to null
            } catch (error: HistoryException) {
                null to error
            } catch (_: Exception) {
                null to null
            }
            runOnUiThread {
                if (ticket != generation || isDestroyed) return@runOnUiThread
                show(loaded)
            }
        }
    }

    private fun show(loaded: Pair<List<Pair<String, List<Point>>>?, HistoryException?>) {
        val series = loaded.first
        binding.charts.removeAllViews()
        if (series == null) {
            binding.status.text = if (loaded.second?.code == 401) {
                getString(R.string.history_unauthorized)
            } else {
                getString(R.string.history_down)
            }
            return
        }
        if (series.isEmpty()) {
            binding.status.text = getString(R.string.history_empty)
            return
        }
        binding.status.text = ""
        val height = (180 * resources.displayMetrics.density).toInt()
        val shown = series.toMutableList()
        vpdSeries(series)?.let { points ->
            val afterHumidity = shown.indexOfFirst { it.first == "humidity_pct" }
            val at = if (afterHumidity >= 0) afterHumidity + 1 else shown.size
            shown.add(at, "vpd_kpa" to points)
        }
        shown.forEach { (metric, raw) ->
            val points = display(metric, raw)
            val caption = TextView(this).apply {
                val last = points.last()
                val label = if (metric == "vpd_kpa") "VPD kPa" else HistoryClient.metricLabel(metric)
                val pattern = if (metric == "vpd_kpa") "%s    %.2f" else "%s    %.1f"
                text = String.format(Locale.US, pattern, label, last.avg)
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text))
                textSize = 14f
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            val chart = HistoryChart(this).apply {
                this.points = points
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            }
            binding.charts.addView(caption)
            binding.charts.addView(chart)
        }
    }

    private fun vpdSeries(series: List<Pair<String, List<Point>>>): List<Point>? {
        val temp = series.firstOrNull { it.first == "temp_c" }?.second ?: return null
        val humidity = series.firstOrNull { it.first == "humidity_pct" }?.second ?: return null
        val byTime = humidity.associateBy { it.t }
        return temp.mapNotNull { sample ->
            val wet = byTime[sample.t] ?: return@mapNotNull null
            val avg = Vpd.kpa(sample.avg, wet.avg)
            Point(sample.t, avg, avg, avg)
        }.takeIf { it.isNotEmpty() }
    }

    private fun display(metric: String, points: List<Point>): List<Point> {
        if (metric != "temp_c" && !metric.startsWith("probe_")) return points
        return points.map { point ->
            point.copy(
                min = ReadingText.fahrenheit(point.min),
                max = ReadingText.fahrenheit(point.max),
                avg = ReadingText.fahrenheit(point.avg),
            )
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NAME = "name"
    }
}
