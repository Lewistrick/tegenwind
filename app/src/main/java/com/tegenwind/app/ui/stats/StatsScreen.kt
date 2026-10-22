package com.tegenwind.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.LearningPass
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.RideTotals
import com.tegenwind.app.data.RiderSetup
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.RiderState
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.StatTile
import com.tegenwind.app.ui.rides.powerText
import com.tegenwind.app.ui.rides.rideWatts
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.HeartColor
import com.tegenwind.app.ui.theme.Ink
import com.tegenwind.app.ui.theme.SpeedColor
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun StatsScreen() {
    val container = LocalContext.current.appContainer
    val routes by remember { container.db.routes().routes() }.collectAsStateWithLifecycle(emptyList())
    var selectedRouteId by rememberSaveable { mutableStateOf<Long?>(null) }

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
    val totals by remember { container.db.rides().totals() }.collectAsStateWithLifecycle(null)
    // Across every route: the all-rides numbers above the route picker.
    val allRides by remember { container.db.rides().realRides() }.collectAsStateWithLifecycle(emptyList())
    val allPasses by produceState(emptyList<LearningPass>(), allRides) { value = container.db.rides().learningPasses() }
    val setup by container.riderSetup.setup.collectAsStateWithLifecycle()
    val overall = remember(allRides, allPasses, setup) { overallStats(allRides, allPasses, setup) }
    val gaps = remember(rides, traversals, segments) { arrivalGaps(rides, traversals, segments) }
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

        totals?.takeIf { it.rides > 0 }?.let { t ->
            item { TotalsCard(t) }
            item { BurnedSavedCard(t, overall.kcal) }
            if (overall.headwind.total > 0) item { WindCard(overall.wind, overall.headwind) }
        }

        riderState?.takeIf { it.rideDays > 0 }?.let { item { FitnessCard(it) } }

        if (overall.hr.size >= 2) {
            item {
                ChartCard("Heart rate vs power", "each dot is one ride · up: average bpm\nacross: effort in ≈ W, wind and slope taken out") {
                    ScatterChart(overall.hr, xTick = { it.toInt().toString() }, color = HeartColor)
                }
            }
        }

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
            Picker(
                selected = selectedRoute?.id,
                options = routes.map { it.id to it.name },
                onSelect = { selectedRouteId = it },
                modifier = Modifier.fillMaxWidth(),
            )
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
            ChartCard("Duration", "minutes, start to finish, over time · fastest & slowest") {
                DurationTrend(rides)
            }
        }

        item {
            ChartCard("Duration", "how often each time range happens, in minutes") {
                DurationHistogram(rides)
            }
        }

        val powered = rides.filter { rideWatts(it) != null }
        if (powered.size >= 2) item { PowerSection(powered) }

        item { SpeedSection(rides) }

        item { ArrivalSection(gaps) }

        item {
            ChartCard("Time of day vs duration", "each dot is one ride · up: minutes\nacross: the hour you set off") {
                ScatterChart(rides.map { startHour(it) to rideDurationMin(it) }, xTick = { "%02d:00".format(it.toInt()) })
            }
        }
        item {
            ChartCard("Weekday vs duration", "average minutes, by the day you set off") {
                WeekdayBars(weekdayAverages(rides))
            }
        }

        val temps = rides.mapNotNull { r -> r.avgTempC?.let { it to rideDurationMin(r) } }
        if (temps.size >= 2) {
            item {
                ChartCard("Temperature vs duration", "each dot is one ride · up: minutes\nacross: average temperature in °C") {
                    ScatterChart(temps, xTick = { signed(it) })
                }
            }
        }
        val rains = rides.mapNotNull { r -> r.rainMm?.let { it to rideDurationMin(r) } }
        if (rains.size >= 2) {
            item {
                ChartCard("Rain vs duration", "each dot is one ride · up: minutes\nacross: rain that fell during the ride, in mm") {
                    ScatterChart(rains, xTick = { "%.1f".format(it) }, minXStep = 0.5)
                }
            }
        }

        if (headwindPoints.size >= 2) {
            item {
                // Two deliberate lines: as one, it broke inside "km/h".
                ChartCard("Headwind vs duration", "each dot is one ride · up: minutes\nacross: headwind in km/h, below 0 is tailwind") {
                    ScatterChart(
                        headwindPoints,
                        xTick = { v -> "%+.0f".format(v).replace('-', '−').let { if (it == "+0" || it == "−0") "0" else it } },
                    )
                }
            }
        }

        if (slowRows.isNotEmpty()) {
            item {
                Column {
                    Text("Slowest segments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "your usual speed on each segment, standing still included: where the road itself costs time",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(slowRows) { SlowSegmentRow(it) }
        }
    }
}

private fun durationMs(ride: RideEntity): Long = (ride.endedAtMs ?: ride.startedAtMs) - ride.startedAtMs

/** How far and how long you've ridden in all, across every route and off them. */
@Composable
private fun TotalsCard(totals: RideTotals) {
    val km = totals.distanceM / 1000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All rides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Whole km once it's long enough that a tenth says nothing.
                FlatStat("Distance", (if (km < 100) "%.1f km" else "%.0f km").format(km), Modifier.weight(1f))
                FlatStat("Rides", totals.rides.toString(), Modifier.weight(1f))
                FlatStat("Riding time", hoursMinutes(totals.movingMs), Modifier.weight(1f))
            }
        }
    }
}

/** What the riding burned, and what driving the same distance would have burned instead. */
@Composable
private fun BurnedSavedCard(totals: RideTotals, kcal: Double) {
    val saved = savedVersusCar(totals.distanceM)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text("Burned and saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "estimates: your effort over the time you moved, at 24% muscle efficiency · against a petrol car at 6.5 L/100 km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatStat("Burned", "≈${kcal.roundToInt()}", Modifier.weight(1f), unit = "kcal")
                FlatStat("Petrol saved", "≈${saved.petrolL.roundToInt()}", Modifier.weight(1f), unit = "L")
                FlatStat("CO₂ saved", "≈${saved.co2Kg.roundToInt()}", Modifier.weight(1f), unit = "kg")
            }
        }
    }
}

/** What the wind did to your time over all rides, and how often it was against you. */
@Composable
private fun WindCard(wind: WindEffect, share: HeadwindShare) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text("Wind", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "time the wind cost and gave back, worked out from your rides and the forecast",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatStat("Headwind cost", "≈" + hoursMinutes((wind.lostS * 1000).toLong()), Modifier.weight(1f))
                FlatStat("Tailwind gain", "≈" + hoursMinutes((wind.gainedS * 1000).toLong()), Modifier.weight(1f))
                FlatStat("Headwind rides", "%d%%".format(share.pct.roundToInt()), Modifier.weight(1f))
            }
        }
    }
}

/** Numbers across all routes that need more than one ride list to work out. */
private data class Overall(
    val kcal: Double,
    val wind: WindEffect,
    val headwind: HeadwindShare,
    /** (effort in W, average heart rate in bpm) for each ride that has both. */
    val hr: List<Pair<Double, Double>>,
)

private fun overallStats(rides: List<RideEntity>, passes: List<LearningPass>, setup: RiderSetup): Overall {
    val byId = rides.associateBy { it.id }
    // Each ride on the bike it was ridden on, as when its effort was worked out.
    fun paramsOf(id: Long) = setup.params(setup.bikes.firstOrNull { it.name == byId[id]?.bikeName } ?: setup.bikes.first())
    return Overall(
        kcal = kcalBurned(rides),
        wind = windEffect(passes, { byId[it]?.pedalPowerW }, ::paramsOf),
        headwind = headwindShare(passes),
        hr = rides.mapNotNull { r ->
            val watts = r.pedalPowerW
            val bpm = r.avgHrBpm
            if (watts != null && bpm != null) watts to bpm else null
        },
    )
}

/** Fitness and fatigue across every route, and what they currently do to the ETA. */
@Composable
private fun FitnessCard(state: RiderState) {
    val effectPct = (Banister.freshnessFactor(state) - 1) * 100
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text("Fitness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "training load per day: an hour of solid riding is about 100",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatStat("Fitness · 6 wk", "%.0f".format(state.fitness), Modifier.weight(1f), unit = "load/day")
                FlatStat("Fatigue · 1 wk", "%.0f".format(state.fatigue), Modifier.weight(1f), unit = "load/day")
                FlatStat("Today", "%+.1f%%".format(effectPct), Modifier.weight(1f))
            }
            Text(
                when {
                    state.rideDays < Banister.MIN_RIDE_DAYS -> "Ride on ${Banister.MIN_RIDE_DAYS - state.rideDays} more days before this starts nudging your arrival time."
                    effectPct > 0.3 -> "Fresher than your recent average, so Tegenwind expects a little more of you."
                    effectPct < -0.3 -> "Still carrying the last few rides, so Tegenwind gives you a little more time."
                    else -> "Training and recovery are in balance; Tegenwind takes you at your usual pace."
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
            StatTile("Fastest", formatElapsed(sorted.first()), Modifier.weight(1f))
            StatTile("Slowest", formatElapsed(sorted.last()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Average", formatElapsed(average), Modifier.weight(1f))
            StatTile("Median", formatElapsed(median), Modifier.weight(1f))
        }
    }
}

/** The duration of each ride over time, the fastest in green and the slowest in red. */
@Composable
private fun DurationTrend(rides: List<RideEntity>) {
    val points = remember(rides) { rides.map { it.startedAtMs to durationMs(it) / 60_000.0 } }
    TrendChart(
        points,
        goodIdx = points.indices.minByOrNull { points[it].second },
        badIdx = points.indices.maxByOrNull { points[it].second },
    )
}

/** The strongest and weakest ride of a route, and the effort of each ride over time. Rides are [powered]. */
@Composable
private fun PowerSection(powered: List<RideEntity>) {
    val watts = powered.map { rideWatts(it)!! }
    val best = watts.indices.maxByOrNull { watts[it] } ?: 0
    val worst = watts.indices.minByOrNull { watts[it] } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Strongest ride", powerText(watts[best]) ?: "–", Modifier.weight(1f).fillMaxHeight(),
                note = shortDate(powered[best].startedAtMs), noteColor = GoodColor,
            )
            StatTile(
                "Weakest ride", powerText(watts[worst]) ?: "–", Modifier.weight(1f).fillMaxHeight(),
                note = shortDate(powered[worst].startedAtMs), noteColor = Danger,
            )
        }
        ChartCard("Power", "≈ W of effort, wind and slope taken out, over time · strongest & weakest") {
            TrendChart(powered.mapIndexed { i, r -> r.startedAtMs to watts[i] }, goodIdx = best, badIdx = worst, color = Ink)
        }
    }
}

/** Average moving speed of a route's rides, and how it moves over time. */
@Composable
private fun SpeedSection(rides: List<RideEntity>) {
    val speeds = rides.mapNotNull { r -> movingSpeedKmh(r)?.let { r.startedAtMs to it } }
    if (speeds.size < 2) return
    val trend = trendPerMonth(speeds)?.let { "%+.1f km/h per month".format(it).replace('-', '−') }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Average moving speed", "%.1f km/h".format(speeds.map { it.second }.average()), Modifier.fillMaxWidth(), note = trend)
        ChartCard("Moving speed", "km/h, standing still not counted, over time") { TrendChart(speeds) }
    }
}

/** How a ride's arrival compared with the arrival predicted at its start and halfway ([startS], [halfS]: + is later). */
private data class RideGap(val timeMs: Long, val startS: Double?, val halfS: Double?)

private fun arrivalGaps(rides: List<RideEntity>, traversals: List<SegmentTraversalEntity>, segments: List<RouteSegmentEntity>): List<RideGap> {
    val last = segments.maxOfOrNull { it.idx } ?: return emptyList()
    val byRide = traversals.groupBy { it.rideId }
    return rides.mapNotNull { r ->
        val actual = byRide[r.id]?.let { actualArrivalMs(it, last) }
        val start = arrivalGapS(r.etaStartArrivalMs, actual)
        val half = arrivalGapS(r.etaHalfArrivalMs, actual)
        if (start == null && half == null) null else RideGap(r.startedAtMs, start, half)
    }
}

/** How close the predicted arrival came: the average gap at the start and halfway, and whether it shrinks. */
@Composable
private fun ArrivalSection(gaps: List<RideGap>) {
    val starts = gaps.mapNotNull { g -> g.startS?.let { g.timeMs to it } }
    val halves = gaps.mapNotNull { it.halfS }
    if (starts.size < 3) {
        Column {
            Text("Arrival time gap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Ride this route a few more times to see how close the predicted arrival gets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    fun mean(values: List<Double>) = formatElapsed((values.map { kotlin.math.abs(it) }.average() * 1000).toLong())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Gap at start", mean(starts.map { it.second }), Modifier.weight(1f).fillMaxHeight(), note = "average of ${starts.size} rides")
            StatTile(
                "Gap halfway", if (halves.isEmpty()) "–" else mean(halves), Modifier.weight(1f).fillMaxHeight(),
                note = if (halves.isEmpty()) null else "average of ${halves.size} rides",
            )
        }
        ChartCard("Arrival time gap", "seconds later (+) or earlier (−) than the arrival predicted at the start") {
            TrendChart(starts, yLabel = { signed(it) })
        }
        gapTrend(starts.map { it.second })?.let { trend ->
            Text(
                when (trend) {
                    Trend.Better -> "Closer lately: the newer half of these rides arrived nearer the prediction."
                    Trend.Worse -> "Further off lately: the newer half of these rides arrived further from the prediction."
                    Trend.Steady -> "About as close as before."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A "nice" grid step (1/2/5 × a power of ten) so axis ticks land on round numbers. */
internal fun niceStep(range: Double, targetTicks: Double = 5.0): Double {
    val raw = range.coerceAtLeast(0.001) / targetTicks
    val mag = 10.0.pow(floor(log10(raw)))
    for (m in intArrayOf(1, 2, 5, 10)) {
        val s = m * mag
        if (raw <= s) return s
    }
    return 10 * mag
}

private val dateLabelFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
internal fun shortDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateLabelFormat)

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
            val layout = measurer.measure("${mins.toInt()} min", labelStyle)
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

/** (headwind km/h at rider height, positive = against you; ride duration in minutes) for each ride that has segment data. */
private fun headwindDurationPoints(rides: List<RideEntity>, traversals: List<SegmentTraversalEntity>): List<Pair<Double, Double>> {
    val byRide = traversals.groupBy { it.rideId }
    return rides.mapNotNull { ride ->
        val hs = byRide[ride.id]?.mapNotNull { it.headwindMps }
        if (hs.isNullOrEmpty()) null else hs.average() * 3.6 to durationMs(ride) / 60_000.0
    }
}

/** A segment as you usually ride it: the median time over it, start to finish, across your real rides. */
private data class SlowSegment(val startM: Double, val endM: Double, val usualMs: Long, val rides: Int) {
    val kmh: Double get() = (endM - startM) / (usualMs / 1000.0) * 3.6
}

/**
 * The segments you usually ride slowest, from your own real rides only (no physics, no model):
 * median time over each, standing still included, as the rest of Stats counts time.
 */
private fun slowestSegments(traversals: List<SegmentTraversalEntity>, segments: List<RouteSegmentEntity>): List<SlowSegment> {
    val byIdx = segments.associateBy { it.idx }
    return traversals.groupBy { it.segIdx }
        .mapNotNull { (idx, ts) ->
            val seg = byIdx[idx] ?: return@mapNotNull null
            val times = ts.map { it.exitMs - it.enterMs }.sorted()
            val usual = times[times.size / 2]
            if (usual <= 0 || seg.endM <= seg.startM) return@mapNotNull null
            SlowSegment(seg.startM, seg.endM, usual, ts.map { it.rideId }.distinct().size)
        }
        .sortedBy { it.kmh }
        .take(6)
}

@Composable
private fun SlowSegmentRow(s: SlowSegment) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("%.1f–%.1f km".format(s.startM / 1000, s.endM / 1000), fontWeight = FontWeight.SemiBold)
                Text(
                    "usually ${formatElapsed(s.usualMs)} · ${s.rides} ride${if (s.rides == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Neutral: a slow segment is the road, not a bad ride.
            Text("%.0f km/h".format(s.kmh), style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A label over a value, straight on the card: for numbers grouped inside a card of their own. */
@Composable
internal fun FlatStat(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unitStyle = MaterialTheme.typography.labelMedium
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
        Text(
            // A unit that is a word goes in the label's size, so the value stays on one line.
            buildAnnotatedString {
                append(value)
                if (unit != null) {
                    withStyle(SpanStyle(fontSize = unitStyle.fontSize, fontWeight = FontWeight.Normal, color = muted)) {
                        append(" $unit")
                    }
                }
            },
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
