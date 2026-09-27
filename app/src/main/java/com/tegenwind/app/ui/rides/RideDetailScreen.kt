package com.tegenwind.app.ui.rides

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.tegenwind.app.routes.GeoPoint
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.health.HealthConnectHr
import com.tegenwind.app.ui.theme.GoodColor
import kotlin.math.roundToInt
import com.tegenwind.app.ride.RideTracker
import com.tegenwind.app.ride.Sample
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.TimeSeriesChart
import com.tegenwind.app.ui.theme.HeartColor
import com.tegenwind.app.ui.theme.SpeedColor
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private sealed interface HrState {
    data object Loading : HrState
    data object NoPermission : HrState
    data object NotYet : HrState
    data class Loaded(val samples: List<Sample>) : HrState
    data class Failed(val message: String) : HrState
}

@Composable
fun RideDetailScreen(rideId: Long, onBack: () -> Unit, backLabel: String = "Rides") {
    val container = LocalContext.current.appContainer
    val dao = container.db.rides()
    val scope = rememberCoroutineScope()
    val routes by remember { container.db.routes().routes() }.collectAsStateWithLifecycle(emptyList())

    var ride by remember { mutableStateOf<RideEntity?>(null) }
    var speeds by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var hr by remember { mutableStateOf<HrState>(HrState.Loading) }
    var hrRefresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf<String?>(null) } // non-null while the "Save as route" dialog is open
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var rideRouteName by remember { mutableStateOf<String?>(null) }
    var editingRoute by remember { mutableStateOf(false) }
    var confirmReplaceRoute by remember { mutableStateOf(false) }
    var taught by remember { mutableStateOf<Taught?>(null) }
    val hrPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { hrRefresh++ }

    LaunchedEffect(rideId) {
        val loaded = dao.ride(rideId)
        ride = loaded
        rideRouteName = loaded?.routeId?.let { container.db.routes().route(it)?.name }
        speeds = dao.points(rideId)
            .filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M && it.speedMps != null }
            .map { Sample(it.timeMs, it.speedMps!! * 3.6) }
        // The passes belong to the route they were ridden on, which "Edit route" doesn't alter.
        val passes = dao.traversals(rideId)
        taught = if (loaded == null) null else passes.firstOrNull()?.routeId?.let { routeId ->
            taughtBy(loaded, passes, container.db.routes().segments(routeId))
        }
    }
    val r = ride
    LaunchedEffect(r, hrRefresh) {
        if (r != null) {
            hr = HrState.Loading
            hr = loadHeartRate(container.healthConnect, r)
        }
    }
    // Heart rate gives a better training load than pace does, so store it once it arrives.
    LaunchedEffect(hr, r) {
        val samples = (hr as? HrState.Loaded)?.samples ?: return@LaunchedEffect
        val current = r ?: return@LaunchedEffect
        if (current.simulated) return@LaunchedEffect
        val trimp = Banister.loadFromHeartRate(samples.map { it.value }, current.movingMs)
        if (trimp > 0 && trimp != current.loadTss) dao.updateRide(current.copy(loadTss = trimp))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ $backLabel") }
        if (r == null) {
            Text("Loading…")
            return@Column
        }
        Text(rideRouteName ?: "Free ride", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            formatRideStart(r.startedAtMs) + if (r.simulated) " · simulated ride" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Distance", "%.2f km".format(r.distanceM / 1000), Modifier.weight(1f))
            Stat("Moving", formatElapsed(r.movingMs), Modifier.weight(1f))
            Stat("Total", formatElapsed((r.endedAtMs ?: r.startedAtMs) - r.startedAtMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Avg speed", avgSpeedText(r), Modifier.weight(1f))
            Stat("Form", formPercent(r.formFactor) ?: "–", Modifier.weight(1f))
            val hrSamples = (hr as? HrState.Loaded)?.samples
            Stat("Avg HR", hrSamples?.let { "${it.map { s -> s.value }.average().toInt()} bpm" } ?: "--", Modifier.weight(1f))
        }

        ChartCard("Speed", "dots GPS · line 2-min median") {
            TimeSeriesChart(speeds, SpeedColor, Modifier.fillMaxWidth().height(150.dp), yRange = 0.0..45.0)
        }

        when (val state = hr) {
            HrState.Loading -> ChartCard("Heart rate", "loading…") { }
            is HrState.Loaded -> ChartCard(
                "Heart rate",
                "max ${state.samples.maxOf { it.value }.toInt()} bpm · line 2-min median",
            ) {
                TimeSeriesChart(state.samples, HeartColor, Modifier.fillMaxWidth().height(150.dp), gapMs = 120_000)
            }
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Heart rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = HeartColor)
                    Text(
                        when (state) {
                            HrState.NoPermission -> "Allow Tegenwind to read heart rate and workouts from Health Connect."
                            HrState.NotYet -> "No heart rate yet. End the workout on your watch and open the Withings app so it syncs, then refresh."
                            is HrState.Failed -> state.message
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state == HrState.NoPermission) {
                        OutlinedButton(onClick = { hrPermissionLauncher.launch(container.healthConnect.permissions) }) { Text("Allow access") }
                    } else {
                        OutlinedButton(onClick = { hrRefresh++ }) { Text("Refresh") }
                    }
                }
            }
        }

        taught?.let { WhatThisRideTaughtCard(it) }

        // What you can do with the ride: one row per action, the button left, what it does right.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val rows = ArrayList<@Composable () -> Unit>()
                if (!r.simulated) rows += {
                    ActionRow("Save as route", "Uses this ride's GPS track as a route, for example woon-werk. Ride it once, save it, done.") {
                        routeName = ""
                    }
                }
                if (routes.isNotEmpty()) rows += {
                    ActionRow("Edit route", "Retroactively change which route was ridden.") { editingRoute = true }
                }
                if (!r.simulated && rideRouteName != null) rows += {
                    ActionRow(
                        "Update route from this ride",
                        "For a road that changed for good: gives \"$rideRouteName\" the line you rode today, " +
                            "keeping its rides and its name.",
                    ) { confirmReplaceRoute = true }
                }
                rows += {
                    ActionRow(
                        if (confirmDelete) "Sure? Tap again" else "Delete ride",
                        "Removes the ride and its GPS track from this phone. Heart rate stays in Health Connect.",
                        danger = true,
                        armed = confirmDelete,
                    ) {
                        if (confirmDelete) {
                            confirmDelete = false
                            scope.launch {
                                dao.delete(rideId)
                                onBack()
                            }
                        } else confirmDelete = true
                    }
                }
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider()
                    row()
                }
            }
        }
        routeMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    // The second tap has to follow soon, or Delete goes back to asking.
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(3_000)
            confirmDelete = false
        }
    }

    routeName?.let { name ->
        AlertDialog(
            onDismissRequest = { routeName = null },
            title = { Text("Save as route") },
            text = {
                OutlinedTextField(value = name, onValueChange = { routeName = it }, label = { Text("Name, e.g. woon-werk") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    routeName = null
                    scope.launch {
                        routeMessage = try {
                            val track = dao.points(rideId)
                                .filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }
                                .map { GeoPoint(it.lat, it.lon) }
                            container.routes.createFromTrack(name, track)
                            "Saved \"${name.trim()}\". Find it under Routes, where you can also add the reverse route."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't save the route: ${e.message ?: e::class.simpleName}"
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { routeName = null }) { Text("Cancel") } },
        )
    }

    val replacedRouteId = r?.routeId
    if (confirmReplaceRoute && replacedRouteId != null) {
        AlertDialog(
            onDismissRequest = { confirmReplaceRoute = false },
            title = { Text("Update \"$rideRouteName\"?") },
            text = {
                Text(
                    "The route starts following the track you rode today. It keeps its name and all " +
                        "its rides, and elevation, buildings and traffic lights are looked up again.\n\n" +
                        "The segment times measured on the old line are dropped: they belong to roads " +
                        "this route no longer follows. Your rides themselves, and your form, stay as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplaceRoute = false
                    scope.launch {
                        routeMessage = try {
                            val track = dao.points(rideId)
                                .filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }
                                .map { GeoPoint(it.lat, it.lon) }
                            container.routes.replaceFromTrack(replacedRouteId, track)
                            "\"$rideRouteName\" now follows this ride. Looking up the map data again."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't update the route: ${e.message ?: e::class.simpleName}"
                        }
                    }
                }) { Text("Update route") }
            },
            dismissButton = { TextButton(onClick = { confirmReplaceRoute = false }) { Text("Cancel") } },
        )
    }

    if (editingRoute) {
        var routeDropdownOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingRoute = false },
            title = { Text("Which route was ridden?") },
            text = {
                Column {
                    OutlinedButton(onClick = { routeDropdownOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(r?.routeId?.let { id -> routes.find { it.id == id }?.name } ?: "Free ride")
                    }
                    DropdownMenu(expanded = routeDropdownOpen, onDismissRequest = { routeDropdownOpen = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                        DropdownMenuItem(text = { Text("Free ride") }, onClick = { r?.let { scope.launch { dao.updateRide(it.copy(routeId = null)); rideRouteName = null; editingRoute = false; routeDropdownOpen = false } } })
                        routes.forEach { route ->
                            DropdownMenuItem(text = { Text(route.name) }, onClick = { r?.let { scope.launch { dao.updateRide(it.copy(routeId = route.id)); rideRouteName = route.name; editingRoute = false; routeDropdownOpen = false } } })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingRoute = false }) { Text("Done") }
            },
        )
    }
}

private suspend fun loadHeartRate(hc: HealthConnectHr, ride: RideEntity): HrState = try {
    if (!hc.hasAllPermissions()) HrState.NoPermission
    else {
        val start = Instant.ofEpochMilli(ride.startedAtMs)
        val end = Instant.ofEpochMilli(ride.endedAtMs ?: ride.startedAtMs)
        val samples = hc.heartRateBetween(start, end)
            .flatMap { it.samples }
            .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
            .map { Sample(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) }
            .distinctBy { it.timeMs }
            .sortedBy { it.timeMs }
        if (samples.isEmpty()) HrState.NotYet else HrState.Loaded(samples)
    }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    HrState.Failed("Couldn't read Health Connect: ${e.message ?: e::class.simpleName}")
}

/** One segment where this ride moved what the model expects. */
private data class Lesson(
    val startM: Double,
    val endM: Double,
    val expectedMs: Long,
    val tookMs: Long,
    /** How much the segment's expected time changed, seconds: positive = slower from now on. */
    val shiftS: Double,
    val signals: Int,
    val passes: Int,
)

/** What the card says about a ride. */
private sealed interface Taught {
    /** Up to three nudges, biggest first; empty when nothing moved by a second or more. */
    data class Lessons(val lessons: List<Lesson>) : Taught
    /** A real ride from before the model learned from rides. */
    data object BeforeLearning : Taught
    data object Simulated : Taught
}

/**
 * The segments whose learned time this ride moved most. Null when there is nothing to judge by:
 * no passes, or passes not yet filled in by the backfill.
 *
 * Ranked by the nudge rather than by the surprise, so a segment the model already knows is slow
 * stops showing up once it has learned that.
 */
private fun taughtBy(
    ride: RideEntity,
    traversals: List<SegmentTraversalEntity>,
    segments: List<RouteSegmentEntity>,
): Taught? {
    val bySegIdx = segments.associateBy { it.idx }
    val known = traversals.filter { it.expectedMovingMs != null && bySegIdx.containsKey(it.segIdx) }
    if (known.isEmpty()) return null
    if (ride.simulated) return Taught.Simulated
    val learned = known.filter { it.learnedShiftMs != null }
    if (learned.isEmpty()) return Taught.BeforeLearning
    return Taught.Lessons(learned
        .filter { kotlin.math.abs(it.learnedShiftMs!!) >= 1_000 }
        .sortedByDescending { kotlin.math.abs(it.learnedShiftMs!!) }
        .take(3)
        .map { t ->
            val seg = bySegIdx.getValue(t.segIdx)
            Lesson(
                startM = seg.startM,
                endM = seg.endM,
                expectedMs = t.expectedMovingMs!!,
                tookMs = t.movingMs,
                shiftS = t.learnedShiftMs!! / 1000.0,
                signals = seg.signals ?: 0,
                passes = seg.learnedPasses,
            )
        })
}

@Composable
private fun WhatThisRideTaughtCard(taught: Taught) {
    val lessons = (taught as? Taught.Lessons)?.lessons.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What this ride taught", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    taught == Taught.Simulated -> "Simulated rides don't teach the model."
                    taught == Taught.BeforeLearning -> "This ride is from before the model learned from rides."
                    lessons.isEmpty() -> "Nothing new: every segment rode close to what the model expected."
                    else -> "Where the model changed its mind. These nudges go into your next ETA."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lessons.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("%.1f–%.1f km".format(l.startM / 1000, l.endM / 1000), fontWeight = FontWeight.SemiBold)
                        Text(
                            "expected ${formatElapsed(l.expectedMs)} · took ${formatElapsed(l.tookMs)} · " +
                                (if (l.signals > 0) "${l.signals} light${if (l.signals == 1) "" else "s"} · " else "") +
                                "${l.passes} pass${if (l.passes == 1) "" else "es"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "%+d s".format(l.shiftS.roundToInt()),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = if (l.shiftS > 0) MaterialTheme.colorScheme.error else GoodColor,
                    )
                }
            }
        }
    }
}

/**
 * One action in the table at the bottom: a button of the same size in every row, and what it does.
 * A [danger] action is red, and filled red while [armed] (waiting for the confirming tap).
 */
@Composable
private fun ActionRow(label: String, description: String, danger: Boolean = false, armed: Boolean = false, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val size = Modifier.width(128.dp).height(52.dp)
        val padding = PaddingValues(horizontal = 8.dp)
        val text: @Composable () -> Unit = {
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
        if (armed) {
            Button(
                onClick = onClick,
                modifier = size,
                contentPadding = padding,
                colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
            ) { text() }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = size,
                contentPadding = padding,
                colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = error) else ButtonDefaults.outlinedButtonColors(),
                border = if (danger) BorderStroke(1.dp, error) else ButtonDefaults.outlinedButtonBorder(),
            ) { text() }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            description,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            content()
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
