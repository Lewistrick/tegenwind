package com.tegenwind.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tegenwind.app.ride.FormDot
import com.tegenwind.app.ride.FormTrail
import com.tegenwind.app.ui.theme.SpeedColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val SPAN_MS = FormTrail.SPAN_MS.toFloat()

/** How long the axes take to follow a new dot, and how long a new dot takes to grow in. */
private const val AXES_MS = 2_500
private const val GROW_MS = 1_000L

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Today's form against the arrival time the ETA gives, one dot per 5 s over the last minute: the
 * oldest small and faint, the newest larger and nearly solid. The axes follow the dots, with the
 * newest in the middle (see [ScatterScale]). Arrival time runs across, form (as the change from
 * 1.0) upward.
 */
@Composable
fun FormArrivalScatter(dots: List<FormDot>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val fit = if (dots.size >= 2) ScatterScale.fit(dots) else null

    // The axes glide to where the newest dot puts them instead of jumping. Held relative to a base,
    // because a float can't tell apart the minutes of one ride when counted from 1970.
    val base = remember(fit == null) { fit?.first?.let { (it.lo + it.hi) / 2 } ?: 0.0 }
    val xLo = remember { Animatable(0f) }
    val xHi = remember { Animatable(0f) }
    val yLo = remember { Animatable(0f) }
    val yHi = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(fit) {
        val (x, y) = fit ?: run { placed = false; return@LaunchedEffect }
        val to = listOf(xLo to (x.lo - base), xHi to (x.hi - base), yLo to y.lo, yHi to y.hi)
        if (!placed) {
            to.forEach { (a, v) -> a.snapTo(v.toFloat()) }
            placed = true
        } else {
            coroutineScope { to.forEach { (a, v) -> launch { a.animateTo(v.toFloat(), tween(AXES_MS, easing = LinearOutSlowInEasing)) } } }
        }
    }
    // Dots fade and shrink with their age as time passes, not in a step when the next one arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (fit == null || !placed) {
                val layout = measurer.measure("Collecting dots…", labelStyle)
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
                return@Canvas
            }
            val xAxis = ScatterAxis(base + xLo.value, base + xHi.value, fit.first.step)
            val yAxis = ScatterAxis(yLo.value.toDouble(), yHi.value.toDouble(), fit.second.step)
            val padL = 34.dp.toPx()
            val padR = 12.dp.toPx()
            val padT = 6.dp.toPx()
            val padB = 16.dp.toPx()
            val plotW = size.width - padL - padR
            val plotH = size.height - padT - padB
            fun x(v: Double) = padL + ((v - xAxis.lo) / (xAxis.hi - xAxis.lo)).toFloat() * plotW
            fun y(v: Double) = padT + (1 - ((v - yAxis.lo) / (yAxis.hi - yAxis.lo))).toFloat() * plotH

            for (v in yAxis.ticks()) {
                val yy = y(v)
                drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
                val text = "%s%d%%".format(if (v < 0) "−" else if (v > 0) "+" else "", kotlin.math.abs(v.roundToInt()))
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            }
            for (v in xAxis.ticks()) {
                val xx = x(v)
                drawLine(gridColor, Offset(xx, padT), Offset(xx, padT + plotH), strokeWidth = 1f)
                val text = Instant.ofEpochMilli((v * 60_000).toLong()).atZone(ZoneId.systemDefault()).format(clock)
                val layout = measurer.measure(text, labelStyle)
                val lx = (xx - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
            }

            clipRect(left = padL, top = 0f, right = size.width, bottom = padT + plotH + 6.dp.toPx()) {
                for (d in dots) {
                    val age = (1 - (now - d.timeMs).toFloat() / SPAN_MS).coerceIn(0f, 1f)
                    val grown = ((now - d.timeMs).toFloat() / GROW_MS).coerceIn(0f, 1f)
                    val radius = (2 + 3 * age).dp.toPx() * grown
                    val alpha = (0.12f + 0.78f * age) * grown
                    drawCircle(SpeedColor.copy(alpha = alpha), radius, Offset(x(ScatterScale.minutes(d)), y(ScatterScale.percent(d))))
                }
            }
        }
    }
}
