package com.tegenwind.app.ui.rides

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.theme.AxisInk
import com.tegenwind.app.ui.theme.SpeedColor

/** The other rides' bars: the same blue, faded, so this ride's bar stands out. */
private const val OTHER_ALPHA = 0.35f

/**
 * How this ride's time falls among the route's rides: one bar per bin with the number of rides
 * above it, this ride's bar in full blue, and every bar labelled under it with the middle of its
 * range. The labels take two rows, every other one lower, so ten of them fit side by side.
 */
@Composable
fun TimeHistogram(bins: TimeBins, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisInk, fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val counts = bins.counts
        val maxCount = counts.max().coerceAtLeast(1)
        val labelH = measurer.measure("0:00", labelStyle).size.height.toFloat()
        val padX = 4.dp.toPx()
        val padT = labelH + 2.dp.toPx() // the count above the tallest bar
        val padB = 2 * labelH + 4.dp.toPx()
        val plotW = size.width - 2 * padX
        val plotH = size.height - padT - padB
        val slot = plotW / counts.size
        val gap = 3.dp.toPx()
        for (i in counts.indices) {
            val left = padX + i * slot
            val centre = left + slot / 2
            if (counts[i] > 0) {
                val h = counts[i].toFloat() / maxCount * plotH
                drawRoundRect(
                    SpeedColor.copy(alpha = if (i == bins.mine) 1f else OTHER_ALPHA),
                    topLeft = Offset(left + gap / 2, padT + plotH - h),
                    size = Size((slot - gap).coerceAtLeast(1f), h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                val count = measurer.measure(counts[i].toString(), labelStyle)
                drawText(count, topLeft = Offset(centre - count.size.width / 2, padT + plotH - h - count.size.height))
            }
            val label = measurer.measure(formatElapsed(bins.middleMs(i)), labelStyle)
            val row = if (i % 2 == 0) 0f else labelH
            drawText(label, topLeft = Offset(centre - label.size.width / 2, padT + plotH + 2.dp.toPx() + row))
        }
    }
}
