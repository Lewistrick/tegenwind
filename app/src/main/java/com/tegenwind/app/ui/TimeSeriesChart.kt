package com.tegenwind.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.tegenwind.app.ride.Sample
import com.tegenwind.app.ride.TWO_MINUTES_MS
import com.tegenwind.app.ride.rollingAverage
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Raw measurements as faint dots plus a 2-minute rolling average as a line.
 * The line breaks where there is no data for [gapMs], so gaps are never papered over.
 *
 * @param windowMs show only the last [windowMs] before [endMs]; null shows everything.
 * @param yRange fixed axis range, or null to fit the data.
 */
@Composable
fun TimeSeriesChart(
    samples: List<Sample>,
    color: Color,
    modifier: Modifier = Modifier,
    windowMs: Long? = null,
    endMs: Long? = null,
    yRange: ClosedFloatingPointRange<Double>? = null,
    gapMs: Long = 30_000,
    emptyText: String = "No data yet",
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainer
    val avg = remember(samples) { rollingAverage(samples, TWO_MINUTES_MS) }

    Canvas(modifier) {
        if (samples.isEmpty()) {
            val layout = measurer.measure(emptyText, labelStyle)
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
            return@Canvas
        }
        val right = endMs ?: samples.last().timeMs
        val left = if (windowMs != null) right - windowMs else samples.first().timeMs
        val span = (right - left).coerceAtLeast(60_000L).toFloat()
        val firstVisible = samples.indexOfFirst { it.timeMs >= left }.coerceAtLeast(0)

        val (lo, hi) = yRange?.let { it.start to it.endInclusive } ?: run {
            val visible = samples.subList(firstVisible, samples.size)
            val mn = visible.minOf { it.value }
            val mx = visible.maxOf { it.value }
            val l = floor((mn - 5) / 10) * 10
            val h = ceil((mx + 5) / 10) * 10
            l to maxOf(h, l + 40)
        }

        val padL = 30.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        fun x(t: Long) = padL + (t - left) / span * plotW
        fun y(v: Double) = padT + (1 - ((v - lo) / (hi - lo))).toFloat() * plotH

        // Horizontal grid with value labels
        val step = if (hi - lo > 60) 20.0 else 10.0
        var v = lo
        while (v <= hi + 0.001) {
            val yy = y(v)
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val layout = measurer.measure(v.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            v += step
        }

        // Time labels
        val minutes = ((right - left) / 60_000).toInt()
        val labels = if (windowMs != null) listOf("−$minutes min", "−${minutes / 2}", "now")
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

        clipRect(left = padL, top = 0f, right = size.width, bottom = size.height - padB + 2.dp.toPx()) {
            val dot = color.copy(alpha = 0.42f)
            val r = if (samples.size - firstVisible > 900) 1.2.dp.toPx() else 1.7.dp.toPx()
            for (i in firstVisible until samples.size) {
                drawCircle(dot, r, Offset(x(samples[i].timeMs), y(samples[i].value)))
            }

            val path = Path()
            var prevT = Long.MIN_VALUE
            // Start a little before the window so the line enters from the left edge.
            for (i in (firstVisible - 1).coerceAtLeast(0) until samples.size) {
                val s = samples[i]
                val px = x(s.timeMs)
                val py = y(avg[i])
                if (prevT == Long.MIN_VALUE || s.timeMs - prevT > gapMs) path.moveTo(px, py) else path.lineTo(px, py)
                prevT = s.timeMs
            }
            drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val end = Offset(x(samples.last().timeMs), y(avg.last()))
            drawCircle(ringColor, 6.dp.toPx(), end)
            drawCircle(color, 4.dp.toPx(), end)
        }
    }
}
