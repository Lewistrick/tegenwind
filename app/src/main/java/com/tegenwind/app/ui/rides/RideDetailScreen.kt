package com.tegenwind.app.ui.rides

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.rememberConfirmTap
import com.tegenwind.app.ui.StatTile
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.Action
import com.tegenwind.app.ui.ActionCard
import com.tegenwind.app.ui.theme.HeartColor
import com.tegenwind.app.ui.theme.SpeedColor
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CancellationException
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
    // The second tap has to follow soon, or Delete goes back to asking.
    var confirmDelete by rememberConfirmTap()
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
        // A simulated ride has no watch behind it, so there is nothing to look for.
        if (r != null && !r.simulated) {
            hr = HrState.Loading
            hr = loadHeartRate(container.healthConnect, r)
            // Kept for two weeks, for estimating heart rate during rides.
            (hr as? HrState.Loaded)?.let { container.heartRate.keep(r, it.samples) }
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
            StatTile("Distance", "%.2f km".format(r.distanceM / 1000), Modifier.weight(1f))
            StatTile("Moving", formatElapsed(r.movingMs), Modifier.weight(1f))
            StatTile("Total", formatElapsed((r.endedAtMs ?: r.startedAtMs) - r.startedAtMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Avg speed", avgSpeedText(r), Modifier.weight(1f))
            StatTile("Form", formPercent(r.formFactor) ?: "–", Modifier.weight(1f))
            val hrSamples = (hr as? HrState.Loaded)?.samples
            StatTile("Avg HR", hrSamples?.let { "${it.map { s -> s.value }.average().toInt()} bpm" } ?: "--", Modifier.weight(1f))
        }

        ChartCard("Speed") {
            TimeSeriesChart(speeds, SpeedColor, Modifier.fillMaxWidth().height(150.dp), yRange = 0.0..45.0, legend = true)
        }

        // A simulated ride has no heart rate to show or wait for.
        if (!r.simulated) when (val state = hr) {
            HrState.Loading -> ChartCard("Heart rate", "loading…") { }
            is HrState.Loaded -> ChartCard("Heart rate", "max ${state.samples.maxOf { it.value }.toInt()} bpm") {
                TimeSeriesChart(state.samples, HeartColor, Modifier.fillMaxWidth().height(150.dp), gapMs = 120_000, legend = true)
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // What you can do with the ride, each with what it does.
        ActionCard(
            buildList {
                if (!r.simulated) add(
                    Action("Save as route", "Uses this ride's GPS track as a route, for example woon-werk. Ride it once, save it, done.") {
                        routeName = ""
                    }
                )
                if (routes.isNotEmpty()) add(
                    Action("Change route", "Pick which route this ride was on, if auto-select or you got it wrong.") {
                        editingRoute = true
                    }
                )
                if (!r.simulated && rideRouteName != null) add(
                    Action(
                        "Update route from this ride",
                        "For a road that changed for good: gives \"$rideRouteName\" the line you rode today, " +
                            "keeping its rides and its name.",
                    ) { confirmReplaceRoute = true }
                )
                add(
                    Action(
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
                )
            }
        )
        routeMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
        AlertDialog(
            onDismissRequest = { editingRoute = false },
            title = { Text("Which route was ridden?") },
            text = {
                Picker(
                    selected = r?.routeId,
                    options = listOf<Pair<Long?, String>>(null to "Free ride") + routes.map { it.id to it.name },
                    onSelect = { id ->
                        r?.let { current ->
                            scope.launch {
                                val changed = current.copy(routeId = id)
                                dao.updateRide(changed)
                                ride = changed
                                rideRouteName = routes.find { it.id == id }?.name
                                editingRoute = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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
        val samples = hc.heartRateSamples(
            Instant.ofEpochMilli(ride.startedAtMs),
            Instant.ofEpochMilli(ride.endedAtMs ?: ride.startedAtMs),
        )
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
