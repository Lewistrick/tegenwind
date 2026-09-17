package com.tegenwind.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.RiderState
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.SpeedColor
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun StatsScreen() {
    val container = LocalContext.current.appContainer
    val routes by remember { container.db.routes().routes() }.collectAsStateWithLifecycle(emptyList())
    var selectedRouteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var routeDropdownOpen by remember { mutableStateOf(false) }

    LaunchedEffect(routes) {
        if (routes.isNotEmpty() && routes.none { it.id == selectedRouteId }) {
            selectedRouteId = routes.first().id
        }
    }

    val selectedRoute = routes.find { it.id == selectedRouteId }
    val rides by remember(selectedRouteId) {
        selectedRouteId?.let { container.db.rides().ridesForRoute(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val traversals by remember(selectedRouteId) {
        selectedRouteId?.let { container.db.routes().traversalsFlow(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val segments by remember(selectedRouteId) {
        selectedRouteId?.let { container.db.routes().segmentsFlow(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val headwindPoints = remember(rides, traversals) { headwindDurationPoints(rides, traversals) }
    val slowRows = remember(traversals, segments) { slowestSegments(traversals, segments) }
    var riderState by remember { mutableStateOf<RiderState?>(null) }
    LaunchedEffect(rides) {
        val now = System.currentTimeMillis()
        riderState = Banister.evaluate(
            container.db.rides().loadsSince(now - Banister.WINDOW_DAYS * 86_400_000L),
            now,
        )
    }

    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Stats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }

        riderState?.takeIf { it.rideDays > 0 }?.let { item { FitnessCard(it) } }

        if (routes.isEmpty()) {
            item {
                Text(
                    "Save a route from a ride to see stats here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        item {
            Box {
                OutlinedButton(onClick = { routeDropdownOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedRoute?.name ?: "Choose a route")
                }
                DropdownMenu(expanded = routeDropdownOpen, onDismissRequest = { routeDropdownOpen = false }, modifier = Modifier.fillMaxWidth(0.85f)) {
                    routes.forEach { r ->
                        DropdownMenuItem(text = { Text(r.name) }, onClick = { selectedRouteId = r.id; routeDropdownOpen = false })
                    }
                }
            }
        }

        if (rides.size < 2) {
            item {
                Text(
                    if (rides.isEmpty()) "No rides on this route yet." else "Ride this route once more to see stats.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        item { KpiRow(rides) }

        item {
            ChartCard("Duration", "trend over time · fastest & slowest") {
                TrendChart(rides)
            }
        }

        item {
            ChartCard("Duration", "how often each time range happens") {
                DurationHistogram(rides)
            }
        }

        if (headwindPoints.size >= 2) {
            item {
                ChartCard("Headwind vs duration", "each dot is one ride") {
                    HeadwindScatter(headwindPoints)
                }
            }
        }

        if (slowRows.isNotEmpty()) {
            item { Text("Slowest segments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(slowRows) { SegmentOverrunRow(it) }
        }
    }
}

private fun durationMs(ride: RideEntity): Long = (ride.endedAtMs ?: ride.startedAtMs) - ride.startedAtMs

/** Fitness and fatigue across every route, and what they currently do to the ETA. */
@Composable
private fun FitnessCard(state: RiderState) {
    val effectPct = (Banister.freshnessFactor(state) - 1) * 100
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fitness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Fitness · 6 wk", "%.0f".format(state.fitness), Modifier.weight(1f))
                Stat("Fatigue · 1 wk", "%.0f".format(state.fatigue), Modifier.weight(1f))
                Stat("Today", "%+.1f%%".format(effectPct), Modifier.weight(1f))
            }
            Text(
                when {
                    state.rideDays < 10 -> "Ride on ${10 - state.rideDays} more days before this starts nudging the ETA."
                    effectPct > 0.3 -> "Fresher than your recent average, so the ETA expects a little more of you."
                    effectPct < -0.3 -> "Still carrying the last few rides, so the ETA gives you a little more time."
                    else -> "Training and recovery are in balance; the ETA takes you at your usual pace."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KpiRow(rides: List<RideEntity>) {
    val durations = rides.map { durationMs(it) }
    val sorted = durations.sorted()
    val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
    val average = durations.average().toLong()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Fastest", formatElapsed(sorted.first()), Modifier.weight(1f))
            Stat("Slowest", formatElapsed(sorted.last()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Average", formatElapsed(average), Modifier.weight(1f))
            Stat("Median", formatElapsed(median), Modifier.weight(1f))
        }
    }
}

/** A "nice" grid step (1/2/5 × a power of ten) so axis ticks land on round numbers. */
private fun niceStep(range: Double, targetTicks: Double = 5.0): Double {
    val raw = range.coerceAtLeast(0.001) / targetTicks
    val mag = 10.0.pow(floor(log10(raw)))
    for (m in intArrayOf(1, 2, 5, 10)) {
        val s = m * mag
        if (raw <= s) return s
    }
    return 10 * mag
}

private val dateLabelFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private fun shortDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateLabelFormat)

@Composable
private fun TrendChart(rides: List<RideEntity>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainer

    val points = remember(rides) { rides.map { it.startedAtMs to durationMs(it) / 60_000.0 } }
    val fastestIdx = remember(points) { points.indices.minByOrNull { points[it].second } ?: 0 }
    val slowestIdx = remember(points) { points.indices.maxByOrNull { points[it].second } ?: 0 }

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = points.first().first
        val right = points.last().first
        val span = (right - left).coerceAtLeast(1L).toFloat()
        val mn = points.minOf { it.second }
        val mx = points.maxOf { it.second }
        val step = niceStep((mx - mn).coerceAtLeast(2.0)).coerceAtLeast(1.0)
        val lo = floor(mn / step) * step
        val hi = (ceil(mx / step) * step).coerceAtLeast(lo + step)

        val padL = 30.dp.toPx()
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
            val layout = measurer.measure(v.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(padL - 5.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            v += step
        }

        val dateLabels = listOf(shortDate(left) to 0f, shortDate(right) to 1f)
        dateLabels.forEach { (text, frac) ->
            val layout = measurer.measure(text, labelStyle)
            val cx = padL + plotW * frac
            val lx = if (frac == 0f) cx else cx - layout.size.width
            drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
        }

        val path = Path()
        points.forEachIndexed { i, (t, dur) ->
            val px = x(t)
            val py = y(dur)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, SpeedColor.copy(alpha = 0.55f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        points.forEachIndexed { i, (t, dur) ->
            val center = Offset(x(t), y(dur))
            val color = when (i) {
                fastestIdx -> GoodColor
                slowestIdx -> Danger
                else -> SpeedColor
            }
            val r = if (i == fastestIdx || i == slowestIdx) 5.dp.toPx() else 3.dp.toPx()
            drawCircle(ringColor, r + 1.5.dp.toPx(), center)
            drawCircle(color, r, center)
        }
    }
}

@Composable
private fun DurationHistogram(rides: List<RideEntity>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val minutes = remember(rides) { rides.map { durationMs(it) / 60_000.0 } }

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val mn = minutes.min()
        val mx = minutes.max()
        val binWidth = niceStep((mx - mn).coerceAtLeast(2.0), targetTicks = 6.0).coerceAtLeast(1.0)
        val firstBin = floor(mn / binWidth) * binWidth
        val lastBin = ceil(mx / binWidth) * binWidth
        val binCount = (((lastBin - firstBin) / binWidth).toInt()).coerceAtLeast(1)
        val counts = IntArray(binCount)
        minutes.forEach { m ->
            val idx = (((m - firstBin) / binWidth).toInt()).coerceIn(0, binCount - 1)
            counts[idx]++
        }
        val maxCount = counts.max()

        val padL = 22.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 6.dp.toPx()
        val padB = 16.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        val countStep = niceStep(maxCount.toDouble(), targetTicks = 4.0).coerceAtLeast(1.0)
        var cv = 0.0
        while (cv <= maxCount + 0.001) {
            val yy = padT + (1 - (cv / maxCount)).toFloat() * plotH
            drawLine(gridColor, Offset(padL, yy), Offset(size.width - padR, yy), strokeWidth = 1f)
            val layout = measurer.measure(cv.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(padL - 4.dp.toPx() - layout.size.width, yy - layout.size.height / 2))
            cv += countStep
        }

        val gap = 3.dp.toPx()
        val barW = (plotW / binCount) - gap
        for (i in 0 until binCount) {
            val h = if (maxCount > 0) (counts[i].toFloat() / maxCount) * plotH else 0f
            val bx = padL + i * (plotW / binCount) + gap / 2
            drawRoundRect(
                SpeedColor.copy(alpha = 0.75f),
                topLeft = Offset(bx, padT + plotH - h),
                size = androidx.compose.ui.geometry.Size(barW.coerceAtLeast(1f), h),
            )
        }

        listOf(firstBin to 0f, ((firstBin + lastBin) / 2) to 0.5f, lastBin to 1f).forEach { (mins, frac) ->
            val layout = measurer.measure("${mins.toInt()}m", labelStyle)
            val cx = padL + plotW * frac
            val lx = when (frac) {
                0f -> cx
                1f -> cx - layout.size.width
                else -> cx - layout.size.width / 2
            }
            drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
        }
    }
}

/** (headwind m/s at rider height, positive = against you; ride duration in minutes) for each ride that has segment data. */
private fun headwindDurationPoints(rides: List<RideEntity>, traversals: List<SegmentTraversalEntity>): List<Pair<Double, Double>> {
    val byRide = traversals.groupBy { it.rideId }
    return rides.mapNotNull { ride ->
        val hs = byRide[ride.id]?.mapNotNull { it.headwindMps }
        if (hs.isNullOrEmpty()) null else hs.average() to durationMs(ride) / 60_000.0
    }
}

@Composable
private fun HeadwindScatter(points: List<Pair<Double, Double>>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val xMn = points.minOf { it.first }
        val xMx = points.maxOf { it.first }
        val yMn = points.minOf { it.second }
        val yMx = points.maxOf { it.second }
        val xStep = niceStep((xMx - xMn).coerceAtLeast(1.0))
        val yStep = niceStep((yMx - yMn).coerceAtLeast(2.0)).coerceAtLeast(1.0)
        val xLo = floor(xMn / xStep) * xStep
        val xHi = (ceil(xMx / xStep) * xStep).coerceAtLeast(xLo + xStep)
        val yLo = floor(yMn / yStep) * yStep
        val yHi = (ceil(yMx / yStep) * yStep).coerceAtLeast(yLo + yStep)

        val padL = 30.dp.toPx()
        val padR = 10.dp.toPx()
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
        var xv = xLo
        while (xv <= xHi + 0.001) {
            val xx = x(xv)
            drawLine(gridColor, Offset(xx, padT), Offset(xx, padT + plotH), strokeWidth = 1f)
            val layout = measurer.measure("%+.0f".format(xv), labelStyle)
            drawText(layout, topLeft = Offset(xx - layout.size.width / 2, size.height - layout.size.height))
            xv += xStep
        }

        points.forEach { (hw, dur) ->
            drawCircle(SpeedColor.copy(alpha = 0.75f), 4.dp.toPx(), Offset(x(hw), y(dur)))
        }
    }
}

private data class SegOverrun(val idx: Int, val startM: Double, val endM: Double, val actualMs: Double, val predictedMs: Double, val rides: Int) {
    val overrun: Double get() = actualMs / predictedMs
}

private fun slowestSegments(traversals: List<SegmentTraversalEntity>, segments: List<RouteSegmentEntity>): List<SegOverrun> {
    val byIdx = segments.associateBy { it.idx }
    return traversals.groupBy { it.segIdx }
        .mapNotNull { (idx, ts) ->
            val seg = byIdx[idx] ?: return@mapNotNull null
            val predicted = ts.map { it.predictedMovingMs }.average()
            if (predicted <= 0.0) return@mapNotNull null
            SegOverrun(idx, seg.startM, seg.endM, ts.map { it.movingMs }.average(), predicted, ts.size)
        }
        .sortedByDescending { it.overrun }
        .take(6)
}

@Composable
private fun SegmentOverrunRow(s: SegOverrun) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("%.1f–%.1f km".format(s.startM / 1000, s.endM / 1000), fontWeight = FontWeight.SemiBold)
                Text(
                    "usual ${formatElapsed(s.actualMs.toLong())} vs modeled ${formatElapsed(s.predictedMs.toLong())} · ${s.rides} ride${if (s.rides == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pct = ((s.overrun - 1) * 100).toInt()
            Text(
                "%+d%%".format(pct),
                fontWeight = FontWeight.SemiBold,
                color = if (pct > 0) Danger else GoodColor,
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
    }
}
