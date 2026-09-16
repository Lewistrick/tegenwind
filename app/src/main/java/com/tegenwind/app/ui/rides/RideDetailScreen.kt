package com.tegenwind.app.ui.rides

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.health.HealthConnectHr
import com.tegenwind.app.ride.RideTracker
import com.tegenwind.app.ride.Sample
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.TimeSeriesChart
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
fun RideDetailScreen(rideId: Long, onBack: () -> Unit) {
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
    }
    val r = ride
    LaunchedEffect(r, hrRefresh) {
        if (r != null) {
            hr = HrState.Loading
            hr = loadHeartRate(container.healthConnect, r)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Rides") }
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
            Stat("Avg heart rate", hrSamples?.let { "${it.map { s -> s.value }.average().toInt()} bpm" } ?: "--", Modifier.weight(1f))
        }

        ChartCard("Speed", "dots GPS · line 2-min avg") {
            TimeSeriesChart(speeds, SpeedColor, Modifier.fillMaxWidth().height(150.dp), yRange = 0.0..45.0)
        }

        when (val state = hr) {
            HrState.Loading -> ChartCard("Heart rate", "loading…") { }
            is HrState.Loaded -> ChartCard(
                "Heart rate",
                "max ${state.samples.maxOf { it.value }.toInt()} bpm · line 2-min avg",
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

        if (!r.simulated) {
            OutlinedButton(onClick = { routeName = "" }) { Text("Save as route") }
            Text(
                "Uses this ride's GPS track as a route, for example woon-werk. Ride it once, save it, done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        routeMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (routes.isNotEmpty()) {
            OutlinedButton(onClick = { editingRoute = true }) { Text("Edit route") }
            Text(
                "Retroactively change which route was ridden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!r.simulated && rideRouteName != null) {
            OutlinedButton(onClick = { confirmReplaceRoute = true }) { Text("Update route from this ride") }
            Text(
                "For a road that changed for good: gives \"$rideRouteName\" the line you rode today, " +
                    "keeping its rides and its name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { confirmDelete = true }) {
            Text("Delete ride", color = MaterialTheme.colorScheme.error)
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ride?") },
            text = { Text("The ride and its GPS track are removed from this phone. Heart rate stays in Health Connect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        dao.delete(rideId)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
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
