package com.tegenwind.app.ui.ride

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.RideService
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.TimeSeriesChart
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.HeartColor
import com.tegenwind.app.ui.theme.SpeedColor
import kotlinx.coroutines.delay

private val bigNumber = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", lineHeight = 64.sp)

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun RideScreen() {
    val context = LocalContext.current
    val recorder = remember { context.appContainer.recorder }
    val live by recorder.live.collectAsStateWithLifecycle()

    var locationOk by remember { mutableStateOf(hasLocation(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationOk = hasLocation(context)
    }
    LifecycleResumeEffect(Unit) {
        locationOk = hasLocation(context)
        onPauseOrDispose { }
    }

    // The screen stays on while riding; the ride keeps recording if it goes off anyway.
    val view = LocalView.current
    DisposableEffect(live != null) {
        view.keepScreenOn = live != null
        onDispose { view.keepScreenOn = false }
    }

    val ride = live
    if (ride == null) {
        IdleView(
            locationOk = locationOk,
            onAllow = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            },
            onStart = { simulated -> RideService.start(context, simulated) },
        )
    } else {
        LiveView(ride, onStop = { RideService.stop(context) })
    }
}

@Composable
private fun IdleView(locationOk: Boolean, onAllow: () -> Unit, onStart: (simulated: Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tegenwind", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (!locationOk) {
            Text(
                "Tegenwind needs your location to measure speed and distance. It stays on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAllow) { Text("Allow location") }
        } else {
            Button(
                onClick = { onStart(false) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) { Text("Start ride", style = MaterialTheme.typography.headlineSmall) }
            TextButton(onClick = { onStart(true) }) { Text("Try a simulated ride") }
            Text(
                "Start a workout on your watch too: its heart rate is added to the ride once the workout ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveView(ride: LiveRide, onStop: () -> Unit) {
    val s = ride.snapshot
    val now by produceState(System.currentTimeMillis(), ride.rideId) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    var fullRide by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3_000)
            confirmStop = false
        }
    }
    val gpsLost = s.lastFixMs == null || now - s.lastFixMs > 10_000

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    ride.simulated -> "● Simulated ride"
                    gpsLost -> "● Waiting for GPS"
                    s.paused -> "● Paused"
                    else -> "● Recording"
                },
                color = if (gpsLost || s.paused) MaterialTheme.colorScheme.onSurfaceVariant else GoodColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(formatElapsed(now - s.startedAtMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Label("Speed")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.speedKmh?.let { "%.1f".format(it) } ?: "--", style = bigNumber)
                    Text(" km/h", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Label("2-min avg")
                        Text(s.avg2MinKmh?.let { "%.1f".format(it) } ?: "--", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val avgMoving = if (s.movingMs > 0) s.distanceM / (s.movingMs / 1000.0) * 3.6 else null
            Stat("Distance", "%.2f km".format(s.distanceM / 1000), Modifier.weight(1f))
            Stat("Moving", formatElapsed(s.movingMs), Modifier.weight(1f))
            Stat("Avg", avgMoving?.let { "%.1f".format(it) } ?: "--", Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth().clickable { fullRide = !fullRide }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row {
                    Text("Speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "dots GPS · line 2-min avg · ${if (fullRide) "full ride" else "10 min"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TimeSeriesChart(
                    samples = s.speeds,
                    color = SpeedColor,
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp),
                    windowMs = if (fullRide) null else 10 * 60_000L,
                    endMs = if (fullRide) null else now,
                    yRange = 0.0..45.0,
                    emptyText = "Waiting for GPS…",
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Heart rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = HeartColor)
                Text(
                    "Added after the ride. Your Steel HR sends workout data when the workout ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { if (confirmStop) onStop() else confirmStop = true },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = if (confirmStop) ButtonDefaults.buttonColors(containerColor = Danger)
            else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text(if (confirmStop) "Tap again to stop" else "Stop ride", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Label(label)
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
    }
}
