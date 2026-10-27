package com.tegenwind.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.roundToInt

/** The room left of a time chart's plot for its value labels: the same on every chart, so their time axes line up. */
val CHART_PAD_LEFT = 36.dp

/**
 * The time axis of a chart: what [com.tegenwind.app.ui.TimeSeriesChart] and [ArrivalChart] share, so
 * that a moment sits above the same spot on both when they are stacked. Either the last [windowMs]
 * before [endMs] (default: the last data), or everything from [startMs] (default: the first data)
 * to [endMs]; the whole ride
 * can be laid out on a log scale ([logXWhenFull]), which gives the most recent stretch the most room.
 */
class TimeAxis(
    firstMs: Long,
    lastMs: Long,
    windowMs: Long? = null,
    endMs: Long? = null,
    logXWhenFull: Boolean = false,
    startMs: Long? = null,
) {
    val right: Long = endMs ?: lastMs
    val left: Long = if (windowMs != null) right - windowMs else startMs ?: firstMs
    val windowed: Boolean = windowMs != null
    private val span: Float = (right - left).coerceAtLeast(60_000L).toFloat()
    private val useLogX = logXWhenFull && windowMs == null
    private val logSpanMs = maxOf(right - left, LOG_MIN_MS * 3)

    /** Where [t] lies across the plot: 0 at its left edge, 1 at its right. */
    fun fraction(t: Long): Float =
        if (useLogX) {
            // ln(1 + ago / 10 s): about linear over the last seconds and logarithmic beyond, so
            // the latest fixes spread out instead of all landing on "now" as a plain log would put them.
            val ago = maxOf(right - t, 0L).toDouble()
            (1 - ln(1 + ago / LOG_MIN_MS) / ln(1 + logSpanMs.toDouble() / LOG_MIN_MS)).toFloat()
        } else (t - left) / span

    /** The time labels under the plot, from its left edge [padL] across [plotW]. */
    fun DrawScope.drawLabels(measurer: TextMeasurer, labelStyle: TextStyle, padL: Float, plotW: Float) {
        fun x(t: Long) = padL + fraction(t) * plotW
        if (useLogX) {
            val niceMinutes = intArrayOf(1, 2, 5, 10, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 600)
            val maxMin = logSpanMs / 60_000.0
            var candidates = niceMinutes.filter { it <= maxMin + 0.01 }
                .sortedDescending()
                .map { m -> maxOf(right - m * 60_000L, left) to "−$m min" }
            if (candidates.isEmpty()) candidates = listOf(left to "−${maxOf(1, maxMin.roundToInt())} min")
            candidates = candidates + (right to "now")

            val minGapPx = 48f
            val kept = ArrayList<Pair<Long, String>>()
            var lastX = Float.POSITIVE_INFINITY
            for (i in candidates.indices.reversed()) {
                val xx = x(candidates[i].first)
                if (lastX - xx >= minGapPx) {
                    kept.add(0, candidates[i])
                    lastX = xx
                }
            }
            kept.forEachIndexed { i, (t, text) ->
                val layout = measurer.measure(text, labelStyle)
                val cx = x(t)
                val lx = when (i) {
                    0 -> cx
                    kept.size - 1 -> cx - layout.size.width
                    else -> cx - layout.size.width / 2
                }
                drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
            }
        } else {
            val minutes = ((right - left) / 60_000).toInt()
            val labels = if (windowed) listOf("−$minutes min", "−${minutes / 2} min", "now")
            else listOf("0", "${minutes / 2} min", "$minutes min")
            labels.forEachIndexed { i, text ->
                val layout = measurer.measure(text, labelStyle)
                val cx = padL + plotW * i / 2f
                val lx = when (i) {
                    0 -> cx
                    2 -> cx - layout.size.width
                    else -> cx - layout.size.width / 2
                }
                drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
            }
        }
    }

    private companion object {
        const val LOG_MIN_MS = 10_000L
    }
}
