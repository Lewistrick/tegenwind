package com.tegenwind.app.ui.ride

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.eta.Eta
import com.tegenwind.app.eta.FormEstimate
import com.tegenwind.app.eta.LiveEta
import com.tegenwind.app.eta.WindNow
import com.tegenwind.app.eta.etaModel
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.startingForm
import com.tegenwind.app.ride.AutoFinish
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.RideRoute
import com.tegenwind.app.ride.RideService
import com.tegenwind.app.ride.TWO_MINUTES_MS
import com.tegenwind.app.ride.formatClock
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.ui.TimeSeriesChart
import com.tegenwind.app.ui.theme.AmberInk
import com.tegenwind.app.ui.theme.Asphalt
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.HeartColor
import com.tegenwind.app.ui.theme.SpeedColor
import com.tegenwind.app.weather.WindSample
import com.tegenwind.app.weather.beaufort
import com.tegenwind.app.weather.compassPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PREF_ROUTE = "lastRouteId"

private val etaNumber = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", lineHeight = 50.sp)

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

    val routes by remember { context.appContainer.routes.routes() }.collectAsStateWithLifecycle(emptyList())
    val prefs = remember { context.getSharedPreferences("tegenwind", Context.MODE_PRIVATE) }
    var routeId by remember { mutableStateOf(prefs.getLong(PREF_ROUTE, -1L).takeIf { it >= 0 }) }

    val ride = live
    if (ride == null) {
        IdleView(
            routes = routes,
            selectedRouteId = routeId?.takeIf { id -> routes.any { it.id == id } },
            onSelectRoute = { id ->
                routeId = id
                prefs.edit().putLong(PREF_ROUTE, id ?: -1L).apply()
            },
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
            onStart = { simulated ->
                RideService.start(context, simulated, routeId?.takeIf { id -> routes.any { it.id == id } })
            },
        )
    } else {
        LiveView(ride, onStop = { RideService.stop(context) })
    }
}

@Composable
private fun IdleView(
    routes: List<RouteEntity>,
    selectedRouteId: Long?,
    onSelectRoute: (Long?) -> Unit,
    locationOk: Boolean,
    onAllow: () -> Unit,
    onStart: (simulated: Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tegenwind", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (routes.isNotEmpty()) {
            var routeDropdownOpen by remember { mutableStateOf(false) }
            val selectedName = selectedRouteId?.let { id -> routes.find { it.id == id }?.name } ?: "Free ride"
            Column {
                OutlinedButton(onClick = { routeDropdownOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedName)
                }
                DropdownMenu(expanded = routeDropdownOpen, onDismissRequest = { routeDropdownOpen = false }, modifier = Modifier.fillMaxWidth(0.9f)) {
                    DropdownMenuItem(text = { Text("Free ride") }, onClick = { onSelectRoute(null); routeDropdownOpen = false })
                    routes.forEach { r ->
                        DropdownMenuItem(text = { Text(r.name) }, onClick = { onSelectRoute(r.id); routeDropdownOpen = false })
                    }
                }
            }
        }
        selectedRouteId?.let { LeaveNowPreview(it) }
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
    val recorder = LocalContext.current.appContainer.recorder
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
            val state = when {
                gpsLost -> "Waiting for GPS"
                s.paused -> "Paused"
                else -> "Recording"
            }
            Text(
                "● " + (if (ride.simulated) "Simulated · " else "") + state,
                color = if (gpsLost || s.paused) MaterialTheme.colorScheme.onSurfaceVariant else GoodColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(formatElapsed(now - s.startedAtMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }

        s.pausedSinceMs?.let { since -> if (ride.arrivedAtMs == null) PausedBar(stoppedMs = now - since) }

        ride.arrivedAtMs?.let { at ->
            ArrivedBar(
                secondsLeft = AutoFinish.secondsLeft(at, now),
                onKeepRiding = { recorder.cancelAutoFinish() },
            )
        }

        val context = LocalContext.current
        val eta = ride.eta
        val route = ride.route
        if (ride.offerShare && ride.arrivedAtMs == null && eta != null && route != null) {
            AlmostThereBar(
                onYes = {
                    recorder.answerSharePrompt()
                    shareViaWhatsApp(context, etaMessage("I'm almost there!", eta, route.progress.remainingM, s.lastLat, s.lastLon))
                },
                onNo = { recorder.answerSharePrompt() },
            )
        }

        ride.route?.let { EtaCard(it, ride.eta, s.lastLat, s.lastLon) }
        if (ride.route == null) ride.freeWind?.let { FreeWindCard(it) }

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
                        "dots GPS · line 2-min avg · ${if (fullRide) "full ride" else "last 2 min"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TimeSeriesChart(
                    samples = s.speeds,
                    color = SpeedColor,
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp),
                    windowMs = if (fullRide) null else TWO_MINUTES_MS,
                    endMs = if (fullRide) null else now,
                    yRange = if (fullRide) 0.0..45.0 else null,
                    niceY = true,
                    logXWhenFull = true,
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

private data class Preview(val eta: Eta, val wind: WindSample?)

/** "Leave now, arrive at 08:43": the ETA for the whole route with the current forecast and your usual form. */
@Composable
private fun LeaveNowPreview(routeId: Long) {
    val container = LocalContext.current.appContainer
    val preview by produceState<Preview?>(null, routeId) {
        val route = container.routes.load(routeId) ?: return@produceState
        val model = route.etaModel()
        val rides = container.db.rides()
        val since = System.currentTimeMillis() - Banister.WINDOW_DAYS * 86_400_000L
        val form = FormEstimate(
            startingForm(rides.recentForms(), rides.loadsSince(since), System.currentTimeMillis()),
            0.01,
        )
        while (true) {
            val forecast = container.weather.forecast(route.line.pointAt(route.line.lengthM / 2))
            val now = System.currentTimeMillis()
            value = Preview(model.predict(0.0, now, forecast, form), forecast?.at(now))
            delay(60_000)
        }
    }
    val p = preview ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Leave now, arrive at",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(formatClock(p.eta.arrivalMs), style = etaNumber, textAlign = TextAlign.Center)
            Text(
                "%s ride · ± %s".format(formatElapsed((p.eta.remainingS * 1000).toLong()), formatMargin(p.eta.bandS)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                p.wind?.let { w ->
                    "Wind %s %d Bft\n%s".format(compassPoint(w.fromDeg), beaufort(w.speedMps), windEffect(p.eta.windCostS))
                } ?: "No wind forecast (offline?)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shown on reaching the end of the route: the ride finishes itself unless you keep riding. */
@Composable
private fun ArrivedBar(secondsLeft: Int, onKeepRiding: () -> Unit) {
    Surface(
        color = GoodColor,
        contentColor = Asphalt,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Arrived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Finishing the ride in ${secondsLeft} s", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onKeepRiding, colors = ButtonDefaults.textButtonColors(contentColor = Asphalt)) {
                Text("Keep riding", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Shown once per ride when the ETA turns firm: tell someone you're almost there, in three taps. */
@Composable
private fun AlmostThereBar(onYes: () -> Unit, onNo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share ETA via WhatsApp?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onYes,
                    modifier = Modifier.weight(1f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoodColor, contentColor = Asphalt),
                ) { Text("YES", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onNo,
                    modifier = Modifier.weight(1f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Asphalt),
                ) { Text("NO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Shown while auto-paused (standing still for 5+ s): moving time and distance stop counting
 * until you ride again. The clock at the top keeps running.
 */
@Composable
private fun PausedBar(stoppedMs: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                val w = size.width * 0.3f
                drawRect(AmberInk, Offset(0f, 0f), Size(w, size.height))
                drawRect(AmberInk, Offset(size.width - w, 0f), Size(w, size.height))
            }
            Text(
                "  Paused · ${formatElapsed(stoppedMs.coerceAtLeast(0))}",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("moving time stopped", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Arrival time, route progress and the wind as you feel it: the heart of the ride screen. */
@Composable
private fun EtaCard(r: RideRoute, live: LiveEta?, lat: Double?, lon: Double?) {
    val context = LocalContext.current
    val p = r.progress
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Arrival · ${r.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(live?.let { formatClock(it.eta.arrivalMs) } ?: "--:--", style = etaNumber)
                        live?.let {
                            Text(
                                "  ± " + formatMargin(it.eta.bandS),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                live?.wind?.let { WindBadge(it) }
            }
            ProgressTrack(r, Modifier.fillMaxWidth().height(14.dp))
            Row {
                Text(
                    when {
                        !p.onRouteYet -> "Head to the route to start tracking"
                        p.offRoute -> "Off route · progress paused"
                        else -> "%.1f km to go".format(p.remainingM / 1000)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (p.offRoute) Danger else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                live?.let {
                    val formPct = ((it.form - 1) * 100).roundToInt()
                    Text(
                        "form %+d%% · %s".format(formPct, windEffect(it.eta.windCostS)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            live?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent.createChooser(etaIntent(etaMessage("On my way!", it, p.remainingM, lat, lon)), "Share ETA"))
                    }) { Text("Share ETA") }
                }
            }
        }
    }
}

/** "On my way! Arriving around 19:34 (± 6 min), 3.2 km to go." plus a maps link, if we know where we are. */
private fun etaMessage(opening: String, live: LiveEta, remainingM: Double, lat: Double?, lon: Double?): String =
    buildString {
        append("$opening Arriving around ${formatClock(live.eta.arrivalMs)} (± ${formatMargin(live.eta.bandS)}), ")
        append("%.1f km to go.".format(remainingM / 1000))
        if (lat != null && lon != null) append("\nhttps://maps.google.com/?q=%.5f,%.5f".format(lat, lon))
    }

private fun etaIntent(text: String): Intent =
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

/**
 * Straight into WhatsApp's own contact picker with the text filled in, skipping Android's app
 * chooser: YES, the contact, send. Falls back to the chooser only if WhatsApp isn't installed.
 */
private fun shareViaWhatsApp(context: Context, text: String) {
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(etaIntent(text).setPackage(pkg))
            return
        } catch (_: ActivityNotFoundException) {
            // Not this edition of WhatsApp; try the next.
        }
    }
    context.startActivity(Intent.createChooser(etaIntent(text), "Share ETA"))
}

/** A free ride has no arrival time, but the wind along your heading still matters. */
@Composable
private fun FreeWindCard(w: WindNow) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Wind")
                Text(
                    "%s %d Bft".format(compassPoint(w.fromDeg), beaufort(w.speed10Mps)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Direction from your last stretch of road",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WindBadge(w, exposureKnown = false)
        }
    }
}

/**
 * Arrow showing where the wind pushes you (up = from behind), with head/tail/cross label.
 * Without map data ([exposureKnown] false) it doesn't claim to know how sheltered you are.
 */
@Composable
private fun WindBadge(w: WindNow, exposureKnown: Boolean = true) {
    val arrowColor = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outline
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Canvas(Modifier.size(44.dp)) {
            drawCircle(ring, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
            rotate(w.relativeDeg.toFloat()) {
                val cx = size.width / 2
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                val head = 7.dp.toPx()
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawLine(arrowColor, Offset(cx, bottom), Offset(cx, top), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(cx - head, top + head)
                    lineTo(cx, top)
                    lineTo(cx + head, top + head)
                }, arrowColor, style = stroke)
            }
        }
        Text(w.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        val kmh = (kotlin.math.abs(w.headwindMps) * 3.6).roundToInt()
        val shelter = if (w.exposure >= 0.7) "open" else if (w.exposure >= 0.4) "partly open" else "sheltered"
        Text(
            if (exposureKnown) "%d km/h · %s".format(kmh, shelter) else "~%d km/h".format(kmh),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 42 s -> "40 s", 95 s -> "2 min" */
private fun formatMargin(seconds: Double): String =
    if (seconds < Eta.FIRM_BAND_S) "${maxOf(5, (seconds / 5).roundToInt() * 5)} s" else "${(seconds / 60).roundToInt()} min"

/** Positive = wind slows you down: "wind costs 1:40"; negative: "wind gains 0:55". */
fun windEffect(windCostS: Double): String {
    val s = kotlin.math.abs(windCostS).roundToInt()
    val time = "%d:%02d".format(s / 60, s % 60)
    return when {
        s < 15 -> "wind barely matters"
        windCostS > 0 -> "wind costs $time"
        else -> "wind gains $time"
    }
}

/** Route progress bar with segment boundaries, traffic lights (red dots) and your position. */
@Composable
private fun ProgressTrack(r: RideRoute, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.surfaceContainer
    val me = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val len = r.progress.lengthM.toFloat()
        val barTop = 4.dp.toPx()
        val barH = size.height - 2 * barTop
        val done = (r.progress.progressM.toFloat() / len).coerceIn(0f, 1f) * size.width
        drawRoundRect(track, Offset(0f, barTop), Size(size.width, barH), CornerRadius(barH / 2))
        drawRoundRect(fill, Offset(0f, barTop), Size(done, barH), CornerRadius(barH / 2))
        r.segmentStartsM.drop(1).forEach { m ->
            val x = m.toFloat() / len * size.width
            drawLine(tick, Offset(x, barTop), Offset(x, barTop + barH), strokeWidth = 1.dp.toPx())
        }
        r.signalsAtM.forEach { m -> drawCircle(Danger, 2.5.dp.toPx(), Offset(m.toFloat() / len * size.width, 2.5.dp.toPx())) }
        drawCircle(me, 6.dp.toPx(), Offset(done, size.height / 2))
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
