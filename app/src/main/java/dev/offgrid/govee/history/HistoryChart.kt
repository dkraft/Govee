package dev.offgrid.govee.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.offgrid.govee.R

/**
 * Min-max band with the average drawn through it. One series, no library.
 */
class HistoryChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var points: List<Point> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 70
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.accent)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val series = points
        if (series.isEmpty()) return
        val pad = 12f * density
        val left = pad
        val right = (width - pad).coerceAtLeast(left + 1f)
        val top = pad
        val bottom = (height - pad).coerceAtLeast(top + 1f)
        val minT = series.first().t.toDouble()
        val maxT = series.last().t.toDouble().coerceAtLeast(minT + 1.0)
        var minY = series.minOf { it.min }
        var maxY = series.maxOf { it.max }
        if (maxY <= minY) maxY = minY + 1.0
        fun x(t: Long) = left + ((t - minT) / (maxT - minT) * (right - left)).toFloat()
        fun y(value: Double) = bottom - ((value - minY) / (maxY - minY) * (bottom - top)).toFloat()

        val fill = Path()
        series.forEachIndexed { index, point ->
            val px = x(point.t)
            val py = y(point.max)
            if (index == 0) fill.moveTo(px, py) else fill.lineTo(px, py)
        }
        for (point in series.asReversed()) fill.lineTo(x(point.t), y(point.min))
        fill.close()
        canvas.drawPath(fill, band)

        if (series.size == 1) {
            line.style = Paint.Style.FILL
            canvas.drawCircle(x(series[0].t), y(series[0].avg), 4f * density, line)
            line.style = Paint.Style.STROKE
            return
        }
        val stroke = Path()
        series.forEachIndexed { index, point ->
            val px = x(point.t)
            val py = y(point.avg)
            if (index == 0) stroke.moveTo(px, py) else stroke.lineTo(px, py)
        }
        canvas.drawPath(stroke, line)
    }
}
