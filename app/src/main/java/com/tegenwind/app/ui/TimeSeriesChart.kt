package com.tegenwind.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.tegenwind.app.ride.rollingMedian
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The expected line: the app's amber, nearly opaque so it stays amber over the dark card (at half
 * strength it turns olive), and wider than the median so it still shows around it.
 */
private const val EXPECTED_ALPHA = 0.85f
private val EXPECTED_WIDTH = 4.dp

/** A "nice" grid step (1/2/5 × a power of ten, at least 1) so ticks land on round numbers. */
private fun niceIntStep(mn: Double, mx: Double): Double {
    val rawStep = maxOf(mx - mn, 2.0) / 4.0
    val mag = 10.0.pow(floor(log10(rawStep)))
    var step = 10 * mag
    for (m in intArrayOf(1, 2, 5, 10)) {
        if (rawStep <= m * mag) {
            step = m * mag
            break
        }
    }
    return maxOf(1.0, step.roundToInt().toDouble())
}

/**
 * Raw measurements as faint dots and a 2-minute rolling median as a line.
 * The lines break where there is no data for [gapMs], so gaps are never papered over.
 *
 * @param windowMs show only the last [windowMs] before [endMs]; null shows everything.
 * @param yRange fixed axis range, or null to fit the data.
 * @param niceY when fitting the data (yRange null), round to a step that keeps ticks legible on a
 *   narrow range (e.g. speed over a 2-minute window) instead of the coarser default used for HR.
 * @param logXWhenFull when showing the whole ride (windowMs null), lay out time on a log scale so
 *   the most recent stretch gets more room than the start, with "−N min" ticks instead of evenly
 *   spaced ones.
 * @param expected a second series drawn as a soft band *under* the measurements (e.g. the speed the
 *   ETA expects), so the measurements always stay on top.
 * @param legend show a small legend in the plot's top-left corner; tapping it folds it away to "?".
 */
@Composable
fun TimeSeriesChart(
    samples: List<Sample>,
    color: Color,
    modifier: Modifier = Modifier,
    windowMs: Long? = null,
    endMs: Long? = null,
    yRange: ClosedFloatingPointRange<Double>? = null,
    niceY: Boolean = false,
    logXWhenFull: Boolean = false,
    gapMs: Long = 30_000,
    emptyText: String = "No data yet",
    expected: List<Sample> = emptyList(),
    expectedColor: Color = color,
    legend: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainer
    val median = remember(samples) { rollingMedian(samples, TWO_MINUTES_MS) }

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (samples.isEmpty()) {
                val layout = measurer.measure(emptyText, labelStyle)
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
                return@Canvas
            }
            val right = endMs ?: samples.last().timeMs
            val left = if (windowMs != null) right - windowMs else samples.first().timeMs
            val span = (right - left).coerceAtLeast(60_000L).toFloat()
            val firstVisible = samples.indexOfFirst { it.timeMs >= left }.coerceAtLeast(0)

            val (lo, hi, yStep) = yRange?.let {
                val s = if (it.endInclusive - it.start > 60) 20.0 else 10.0
                Triple(it.start, it.endInclusive, s)
            } ?: run {
                // The expected line counts too, so it's never cut off at the top or bottom.
                val visible = samples.subList(firstVisible, samples.size).map { it.value } +
                    expected.filter { it.timeMs >= left }.map { it.value }
                val mn = visible.min()
                val mx = visible.max()
                if (niceY) {
                    val s = niceIntStep(mn, mx)
                    val l = floor(mn / s) * s
                    val h = ceil(mx / s) * s
                    Triple(l, maxOf(h, l + s * 3), s)
                } else {
                    val l = floor((mn - 5) / 10) * 10
                    val h = ceil((mx + 5) / 10) * 10
                    val hh = maxOf(h, l + 40)
                    Triple(l, hh, if (hh - l > 60) 20.0 else 10.0)
                }
            }

            val padL = 30.dp.toPx()
            val padR = 6.dp.toPx()
            val padT = 6.dp.toPx()
            val padB = 16.dp.toPx()
            val plotW = size.width - padL - padR
            val plotH = size.height - padT - padB

            val useLogX = logXWhenFull && windowMs == null
            val logMinMs = 10_000L
            val logSpanMs = maxOf(right - left, logMinMs * 3)
            fun logX(t: Long): Float {
                // ln(1 + ago / 10 s): about linear over the last seconds and logarithmic beyond, so
                // the latest fixes spread out instead of all landing on "now" as a plain log would put them.
                val ago = maxOf(right - t, 0L).toDouble()
                val frac = ln(1 + ago / logMinMs) / ln(1 + logSpanMs.toDouble() / logMinMs)
                return padL + (1 - frac).toFloat() * plotW
            }
            fun x(t: Long) = if (useLogX) logX(t) else padL + (t - left) / span * plotW
            fun y(v: Double) = padT + (1 - ((v - lo) / (hi - lo))).toFloat() * plotH

            // Horizontal grid with value labels
            var v = lo
            while (v <= hi + 0.001) {
                val yy = y(v)
                drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
                val layout = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
                v += yStep
            }

            // Time labels
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
                    val xx = logX(candidates[i].first)
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
                val labels = if (windowMs != null) listOf("−$minutes min", "−${minutes / 2} min", "now")
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

            clipRect(left = padL, top = 0f, right = size.width, bottom = size.height - padB + 2.dp.toPx()) {
                // First, so everything measured is drawn over it.
                if (expected.isNotEmpty()) {
                    val path = Path()
                    var prevT = Long.MIN_VALUE
                    val from = (expected.indexOfFirst { it.timeMs >= left } - 1).coerceAtLeast(0)
                    for (i in from until expected.size) {
                        val e = expected[i]
                        val px = x(e.timeMs)
                        val py = y(e.value)
                        if (prevT == Long.MIN_VALUE || e.timeMs - prevT > gapMs) path.moveTo(px, py) else path.lineTo(px, py)
                        prevT = e.timeMs
                    }
                    drawPath(
                        path,
                        expectedColor.copy(alpha = EXPECTED_ALPHA),
                        style = Stroke(width = EXPECTED_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                val dot = color.copy(alpha = 0.42f)
                val r = if (samples.size - firstVisible > 900) 1.2.dp.toPx() else 1.7.dp.toPx()
                for (i in firstVisible until samples.size) {
                    drawCircle(dot, r, Offset(x(samples[i].timeMs), y(samples[i].value)))
                }

                fun pathOf(values: DoubleArray): Path {
                    val path = Path()
                    var prevT = Long.MIN_VALUE
                    // Start a little before the window so the line enters from the left edge.
                    for (i in (firstVisible - 1).coerceAtLeast(0) until samples.size) {
                        val s = samples[i]
                        val px = x(s.timeMs)
                        val py = y(values[i])
                        if (prevT == Long.MIN_VALUE || s.timeMs - prevT > gapMs) path.moveTo(px, py) else path.lineTo(px, py)
                        prevT = s.timeMs
                    }
                    return path
                }

                drawPath(
                    pathOf(median),
                    color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                val end = Offset(x(samples.last().timeMs), y(median.last()))
                drawCircle(ringColor, 6.dp.toPx(), end)
                drawCircle(color, 4.dp.toPx(), end)
            }
        }
        if (legend && samples.isNotEmpty()) {
            ChartLegend(
                color = color,
                expectedColor = expectedColor.takeIf { expected.isNotEmpty() },
                // Just inside the plot area, clear of the axis labels.
                modifier = Modifier.align(Alignment.TopStart).padding(start = 34.dp, top = 8.dp),
            )
        }
    }
}

/** What each line means, in the chart's own styles. Tapping folds it away to a "?" and back. */
@Composable
private fun ChartLegend(color: Color, expectedColor: Color?, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(true) }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val text = TextStyle(color = ink, fontSize = 10.sp, lineHeight = 12.sp)
    // Its own click, so tapping the legend doesn't also toggle the chart underneath.
    val toggle = Modifier.clickable { open = !open }
    if (!open) {
        Box(
            modifier.then(toggle).size(16.dp).border(1.dp, ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("?", style = text) }
        return
    }
    Column(
        modifier
            .then(toggle)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        LegendRow("actual", text) {
            val r = 1.7.dp.toPx()
            for (i in 0..2) drawCircle(color.copy(alpha = 0.7f), r, Offset(r + i * (size.width - 2 * r) / 2, size.height / 2))
        }
        LegendRow("median", text) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(), StrokeCap.Round)
        }
        if (expectedColor != null) LegendRow("expected", text) {
            drawLine(expectedColor.copy(alpha = EXPECTED_ALPHA), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), EXPECTED_WIDTH.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun LegendRow(label: String, style: TextStyle, swatch: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 8.dp), onDraw = swatch)
        Text("  $label", style = style)
    }
}
