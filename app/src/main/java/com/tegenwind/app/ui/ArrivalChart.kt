package com.tegenwind.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tegenwind.app.ride.ArrivalPoint
import com.tegenwind.app.ui.theme.SpeedColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The arrival axis: whole minutes since 1970 from [lo] to [hi], a tick every [step]. */
data class ArrivalAxis(val lo: Double, val hi: Double, val step: Double)

/** An axis in whole minutes that holds every point's band, with ticks on round minutes. */
fun arrivalAxis(points: List<ArrivalPoint>): ArrivalAxis? {
    if (points.isEmpty()) return null
    val mn = points.minOf { it.arrivalMs - it.bandMs } / 60_000.0
    val mx = points.maxOf { it.arrivalMs + it.bandMs } / 60_000.0
    val step = niceIntStep(mn, mx)
    val lo = floor(mn / step) * step
    val hi = maxOf(ceil(mx / step) * step, lo + 2 * step)
    return ArrivalAxis(lo, hi, step)
}

/**
 * The predicted arrival time over the ride so far: a thick line, with the band the ETA gave around
 * it ("±") as thin lines and a see-through fill between them. Time runs across on the same [axis] as
 * the speed chart above it, so a moment lines up on both; the arrival's clock time goes upward,
 * fitted to what the axis shows.
 */
@Composable
fun ArrivalChart(points: List<ArrivalPoint>, axis: TimeAxis?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        // Only what the time axis shows, from a point before it so the line enters from the left edge.
        val first = (points.indexOfFirst { axis != null && it.timeMs >= axis.left } - 1).coerceAtLeast(0)
        val shown = if (axis == null || points.none { it.timeMs >= axis.left }) emptyList() else points.subList(first, points.size)
        val yAxis = arrivalAxis(shown)
        if (axis == null || yAxis == null || shown.size < 2) {
            val layout = measurer.measure("Waiting for an arrival time…", labelStyle)
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
            return@Canvas
        }
        val padL = CHART_PAD_LEFT.toPx()
        val padR = 6.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        fun x(t: Long) = padL + axis.fraction(t) * plotW
        fun y(ms: Long) = padT + (1 - ((ms / 60_000.0 - yAxis.lo) / (yAxis.hi - yAxis.lo))).toFloat() * plotH

        var v = yAxis.lo
        while (v <= yAxis.hi + 1e-9) {
            val yy = y((v * 60_000).toLong())
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val text = Instant.ofEpochMilli((v * 60_000).toLong()).atZone(ZoneId.systemDefault()).format(clock)
            val layout = measurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            v += yAxis.step
        }
        with(axis) { drawLabels(measurer, labelStyle, padL, plotW) }

        clipRect(left = padL, top = 0f, right = size.width, bottom = padT + plotH + 2.dp.toPx()) {
            fun line(value: (ArrivalPoint) -> Long) = Path().apply {
                shown.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.timeMs), y(value(p))) else lineTo(x(p.timeMs), y(value(p))) }
            }
            val upper = line { it.arrivalMs + it.bandMs }
            val lower = line { it.arrivalMs - it.bandMs }
            val between = Path().apply {
                addPath(upper)
                for (p in shown.asReversed()) lineTo(x(p.timeMs), y(p.arrivalMs - p.bandMs))
                close()
            }
            drawPath(between, SpeedColor.copy(alpha = 0.18f))
            val thin = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(upper, SpeedColor, style = thin)
            drawPath(lower, SpeedColor, style = thin)
            drawPath(line { it.arrivalMs }, SpeedColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
