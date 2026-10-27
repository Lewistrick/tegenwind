package com.tegenwind.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.SpeedColor
import kotlin.math.ceil
import kotlin.math.floor
import com.tegenwind.app.ui.theme.AxisInk

// The charts of the Stats page that more than one number uses.

/** A value with a real minus, for axes: "−30", not "-30". */
internal fun signed(v: Double): String = v.toInt().toString().replace('-', '−')

/**
 * One value per ride over time: a line through dots, with [goodIdx] in green and [badIdx] in red
 * when given. [yLabel] says the axis numbers.
 */
@Composable
internal fun TrendChart(
    points: List<Pair<Long, Double>>,
    goodIdx: Int? = null,
    badIdx: Int? = null,
    color: Color = SpeedColor,
    yLabel: (Double) -> String = { it.toInt().toString() },
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisInk, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainer

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = points.first().first
        val right = points.last().first
        val span = (right - left).coerceAtLeast(1L).toFloat()
        val mn = points.minOf { it.second }
        val mx = points.maxOf { it.second }
        val step = niceStep((mx - mn).coerceAtLeast(2.0)).coerceAtLeast(1.0)
        val lo = floor(mn / step) * step
        val hi = (ceil(mx / step) * step).coerceAtLeast(lo + step)

        val padL = 34.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        fun x(t: Long) = padL + (t - left) / span * plotW
        fun y(v: Double) = padT + (1 - (v - lo) / (hi - lo)).toFloat() * plotH

        var v = lo
        while (v <= hi + 0.001) {
            val yy = y(v)
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val layout = measurer.measure(yLabel(v), labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            v += step
        }

        listOf(shortDate(left) to 0f, shortDate(right) to 1f).forEach { (text, frac) ->
            val layout = measurer.measure(text, labelStyle)
            val cx = padL + plotW * frac
            val lx = if (frac == 0f) cx else cx - layout.size.width
            drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
        }

        val path = Path()
        points.forEachIndexed { i, (t, value) ->
            if (i == 0) path.moveTo(x(t), y(value)) else path.lineTo(x(t), y(value))
        }
        drawPath(path, color.copy(alpha = 0.55f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        points.forEachIndexed { i, (t, value) ->
            val center = Offset(x(t), y(value))
            val dot = when (i) {
                goodIdx -> GoodColor
                badIdx -> Danger
                else -> color
            }
            val r = if (i == goodIdx || i == badIdx) 5.dp.toPx() else 3.dp.toPx()
            drawCircle(ringColor, r + 1.5.dp.toPx(), center)
            drawCircle(dot, r, center)
        }
    }
}

/**
 * Two numbers per ride, one dot each. [xTick] says the numbers along the bottom; ticks are at least
 * [minXStep] apart so no two carry the same label.
 */
@Composable
internal fun ScatterChart(
    points: List<Pair<Double, Double>>,
    xTick: (Double) -> String,
    minXStep: Double = 1.0,
    color: Color = SpeedColor,
    /** The axis runs just past the first and last dot, with ticks inside it, rather than out to round numbers. */
    tightX: Boolean = false,
    /** The axis runs exactly from the first to the last dot (ticks inside it). */
    exactX: Boolean = false,
    /** Steps, ascending, to space the ticks by: the smallest with at most seven spaces across the axis. */
    xSteps: List<Double>? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisInk, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val xMn = points.minOf { it.first }
        val xMx = points.maxOf { it.first }
        val yMn = points.minOf { it.second }
        val yMx = points.maxOf { it.second }
        val xStep = xSteps?.let { tickStep((xMx - xMn).coerceAtLeast(minXStep), it) }
            ?: niceStep((xMx - xMn).coerceAtLeast(minXStep)).coerceAtLeast(minXStep)
        val yStep = niceStep((yMx - yMn).coerceAtLeast(2.0)).coerceAtLeast(1.0)
        val xTight = tightX || exactX
        // One value on its own still needs an axis with some width.
        val xPad = if (exactX) 0.0 else if (tightX) maxOf((xMx - xMn) * 0.06, minXStep * 0.5) else 0.0
        val xLo = if (xTight) xMn - xPad else floor(xMn / xStep) * xStep
        val xHi = if (xTight) maxOf(xMx + xPad, xLo + minXStep) else (ceil(xMx / xStep) * xStep).coerceAtLeast(xLo + xStep)
        val yLo = floor(yMn / yStep) * yStep
        val yHi = (ceil(yMx / yStep) * yStep).coerceAtLeast(yLo + yStep)

        val padL = 30.dp.toPx()
        val padR = 18.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        fun x(v: Double) = padL + ((v - xLo) / (xHi - xLo)).toFloat() * plotW
        fun y(v: Double) = padT + (1 - (v - yLo) / (yHi - yLo)).toFloat() * plotH

        var yv = yLo
        while (yv <= yHi + 0.001) {
            val yy = y(yv)
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val layout = measurer.measure(yv.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            yv += yStep
        }
        var xv = if (xTight) ceil(xLo / xStep - 1e-9) * xStep else xLo
        while (xv <= xHi + 0.001) {
            val xx = x(xv)
            drawLine(gridColor, Offset(xx, padT), Offset(xx, padT + plotH), strokeWidth = 1f)
            val layout = measurer.measure(xTick(xv), labelStyle)
            drawText(layout, topLeft = Offset(xx - layout.size.width / 2, size.height - layout.size.height))
            xv += xStep
        }

        points.forEach { (px, py) -> drawCircle(color.copy(alpha = 0.75f), 4.dp.toPx(), Offset(x(px), y(py))) }
    }
}

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Average minutes for each weekday, Monday first ([weekdayAverages]); a day without rides has no bar. */
@Composable
internal fun WeekdayBars(days: List<Pair<Double, Int>?>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisInk, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val averages = days.filterNotNull().map { it.first }
        if (averages.isEmpty()) return@Canvas
        // The minutes axis fits the bars rather than starting at 0: the weekdays differ by a few minutes.
        val step = niceStep((averages.max() - averages.min()).coerceAtLeast(2.0), targetTicks = 4.0).coerceAtLeast(1.0)
        val lo = floor((averages.min() - step * 0.3) / step) * step
        val hi = ceil((averages.max() + step * 0.15) / step) * step

        val padL = 30.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        var v = lo
        while (v <= hi + 0.001) {
            val yy = padT + (1 - ((v - lo) / (hi - lo))).toFloat() * plotH
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val layout = measurer.measure(v.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            v += step
        }

        val slot = plotW / 7
        val gap = 6.dp.toPx()
        days.forEachIndexed { i, day ->
            val cx = padL + slot * (i + 0.5f)
            if (day != null) {
                val h = ((day.first - lo) / (hi - lo)).toFloat() * plotH
                drawRoundRect(
                    SpeedColor.copy(alpha = 0.75f),
                    topLeft = Offset(cx - (slot - gap) / 2, padT + plotH - h),
                    size = Size((slot - gap).coerceAtLeast(1f), h),
                )
            }
            val layout = measurer.measure(WEEKDAY_LABELS[i], labelStyle)
            drawText(layout, topLeft = Offset(cx - layout.size.width / 2, size.height - layout.size.height))
        }
    }
}
