package com.tegenwind.app.ui.ride

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
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
import com.tegenwind.app.ui.rides.powerText
import com.tegenwind.app.ui.rides.usualPowerText
import com.tegenwind.app.ui.rides.usualWatts
import com.tegenwind.app.ui.settings.RiderBikeScreen
import com.tegenwind.app.data.RiderSetup
import com.tegenwind.app.ride.AutoFinish
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.RideRoute
import com.tegenwind.app.ride.RideService
import com.tegenwind.app.ride.SegmentWeather
import com.tegenwind.app.ride.TWO_MINUTES_MS
import com.tegenwind.app.ride.formatClock
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.RouteFit
import com.tegenwind.app.routes.habitByRoute
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.ArrivalChart
import com.tegenwind.app.ui.TimeAxis
import com.tegenwind.app.ui.TimeSeriesChart
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.rides.RideDetailScreen
import com.tegenwind.app.ui.theme.AmberInk
import com.tegenwind.app.ui.theme.Asphalt
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.SpeedColor
import com.tegenwind.app.ui.theme.scaleColor
import com.tegenwind.app.weather.Sky
import androidx.compose.ui.graphics.drawscope.clipRect
import com.tegenwind.app.weather.rainChanceStripes
import com.tegenwind.app.weather.rainBlueShare
import com.tegenwind.app.weather.WindSample
import com.tegenwind.app.weather.beaufort
import com.tegenwind.app.weather.compassPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PREF_ROUTE = "lastRouteId"
private const val PREF_SHARE_LIVE = "shareLive"

/** A route you picked yourself isn't replaced by the automatic choice for this long. */
private const val PICK_HOLDS_MS = 10 * 60_000L

private val etaNumber = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", lineHeight = 50.sp)

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
    var routeId by remember { mutableStateOf(prefs.getLong(PREF_ROUTE, -1L).takeIf { it >= 0 || it == RideService.AUTO_ROUTE }) }
    val liveShare = remember { context.appContainer.liveShare }
    var shareLive by remember { mutableStateOf(prefs.getBoolean(PREF_SHARE_LIVE, false)) }
    val justFinished by recorder.justFinished.collectAsStateWithLifecycle()
    val riderSetup = remember { context.appContainer.riderSetup }
    val setup by riderSetup.setup.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // The route that fits where you are and the hour is chosen for you whenever the screen comes
    // up, unless you've set it to Auto-select or picked one yourself in the last ten minutes.
    var resumed by remember { mutableIntStateOf(0) }
    var pickedAtMs by remember { mutableLongStateOf(0L) }
    var chosenForYou by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    LaunchedEffect(resumed, routes, locationOk, live == null) {
        if (live != null || routes.isEmpty() || routeId == RideService.AUTO_ROUTE) return@LaunchedEffect
        if (System.currentTimeMillis() - pickedAtMs < PICK_HOLDS_MS) return@LaunchedEffect
        val container = context.appContainer
        val starts = container.db.routes().routeStarts().associate { it.routeId to GeoPoint(it.lat, it.lon) }
        val here = if (locationOk) whereAmI(context) else null
        val habit = habitByRoute(container.db.rides().recentRouteStarts(), System.currentTimeMillis())
        val best = RouteFit.best(starts, here, habit) ?: return@LaunchedEffect
        if (best != routeId && System.currentTimeMillis() - pickedAtMs >= PICK_HOLDS_MS) {
            routeId = best
            prefs.edit().putLong(PREF_ROUTE, best).apply()
            chosenForYou = true
        }
    }

    val ride = live
    val finished = justFinished
    if (finished != null) {
        // A ride just ended: show what it was rather than dropping back to the start screen.
        BackHandler { recorder.clearJustFinished() }
        RideDetailScreen(rideId = finished, onBack = { recorder.clearJustFinished() }, backLabel = "Ride")
    } else if (ride == null && settingsOpen) {
        BackHandler { settingsOpen = false }
        RiderBikeScreen(onBack = { settingsOpen = false })
    } else if (ride == null) {
        IdleView(
            routes = routes,
            selectedRouteId = routeId?.takeIf { id -> id == RideService.AUTO_ROUTE || routes.any { it.id == id } },
            onSelectRoute = { id ->
                routeId = id
                pickedAtMs = System.currentTimeMillis()
                chosenForYou = false
                prefs.edit().putLong(PREF_ROUTE, id ?: -1L).apply()
            },
            chosenForYou = chosenForYou,
            setup = setup,
            onSelectBike = { id -> riderSetup.update { it.copy(activeBikeId = id) } },
            onOpenSettings = { settingsOpen = true },
            canShareLive = liveShare.configured,
            shareLive = shareLive,
            onShareLive = {
                shareLive = it
                prefs.edit().putBoolean(PREF_SHARE_LIVE, it).apply()
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
                RideService.start(
                    context,
                    simulated,
                    routeId?.takeIf { id -> id == RideService.AUTO_ROUTE || routes.any { it.id == id } },
                    shareLive = shareLive && liveShare.configured,
                )
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
    chosenForYou: Boolean,
    setup: RiderSetup,
    onSelectBike: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    canShareLive: Boolean,
    shareLive: Boolean,
    onShareLive: (Boolean) -> Unit,
    locationOk: Boolean,
    onAllow: () -> Unit,
    onStart: (simulated: Boolean) -> Unit,
) {
    // Centred when it fits, scrolling when it doesn't: nothing may end up hidden under the tabs.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tegenwind", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (routes.isNotEmpty()) {
                Picker(
                    selected = selectedRouteId,
                    options = listOf<Pair<Long?, String>>(RideService.AUTO_ROUTE to "Auto-select", null to "Free ride") +
                        routes.map { it.id to it.name },
                    onSelect = onSelectRoute,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selectedRouteId == RideService.AUTO_ROUTE) {
                Text(
                    "The route is worked out as you ride, from where you set off and which way you go.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                if (chosenForYou && selectedRouteId != null) {
                    Text(
                        "Chosen for where you are and the time of day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                selectedRouteId?.let {
                    RouteDoubtWarning(it, routes, locationOk, onSelectRoute = { id -> onSelectRoute(id) })
                    LeaveNowPreview(it)
                }
            }
            // Which bike the ride is on, and who rides it: what the arrival time is worked out with.
            if (setup.bikes.size > 1) {
                Picker(
                    selected = setup.activeBikeId,
                    options = setup.bikes.map { it.id to "On ${it.name}" },
                    onSelect = onSelectBike,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${setup.activeBike.name} · %s kg".format(
                        if (setup.riderKg == Math.rint(setup.riderKg)) setup.riderKg.toLong().toString() else "%.1f".format(setup.riderKg)
                    ).replace(' ', '\u00A0'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) { Text("Rider and bike") }
            }
            if (canShareLive) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share live", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (shareLive) "The link to follow along goes with your Share ETA message."
                            else "Nothing leaves the phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Two lines either way, so switching doesn't shift the whole page.
                            minLines = 2,
                        )
                    }
                    Switch(checked = shareLive, onCheckedChange = onShareLive)
                }
            }
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
                    textAlign = TextAlign.Center,
                )
            }
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
    // One time axis for the speed and arrival charts, so a moment sits above the same spot on both.
    val timeAxis = s.speeds.takeIf { it.isNotEmpty() }?.let {
        TimeAxis(it.first().timeMs, it.last().timeMs, if (fullRide) null else TWO_MINUTES_MS, if (fullRide) null else now, logXWhenFull = true)
    }

    val context = LocalContext.current
    val liveLink by remember { context.appContainer.liveShare.link }.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val state = when {
                    !ride.started -> "Waiting for you to set off"
                    gpsLost -> "Waiting for GPS"
                    s.paused -> "Paused"
                    else -> "Recording"
                }
                Text(
                    "● " + (if (ride.simulated) "Simulated · " else "") + state,
                    color = if (!ride.started || gpsLost || s.paused) MaterialTheme.colorScheme.onSurfaceVariant else GoodColor,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatElapsed(if (ride.started) now - s.startedAtMs else 0L),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                )
            }

            s.pausedSinceMs?.let { since -> if (ride.started && ride.arrivedAtMs == null) PausedBar(stoppedMs = now - since) }

            ride.arrivedAtMs?.let { at ->
                ArrivedBar(
                    secondsLeft = AutoFinish.secondsLeft(at, now),
                    onKeepRiding = { recorder.cancelAutoFinish() },
                )
            }

            ride.route?.let { EtaCard(it, ride.eta, s.lastLat, s.lastLon, liveLink) }
            if (ride.route == null) ride.freeWind?.let { FreeWindCard(it) }

            // Speed and distance head the chart they belong to, so the whole ride, Stop included,
            // fits on one screen. Heart rate only arrives after the ride, so it has no card here.
            Card(Modifier.fillMaxWidth().clickable { fullRide = !fullRide }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Column {
                            Label("Speed")
                            Text(
                                "${s.speedKmh?.let { "%.1f".format(it) } ?: "--"} km/h",
                                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Label("Distance")
                            Text(
                                "%.2f km".format(s.distanceM / 1000),
                                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(245.dp).padding(top = 4.dp)) {
                        TimeSeriesChart(
                            samples = s.speeds,
                            color = SpeedColor,
                            modifier = Modifier.fillMaxSize(),
                            windowMs = if (fullRide) null else TWO_MINUTES_MS,
                            endMs = if (fullRide) null else now,
                            yRange = if (fullRide) 0.0..45.0 else null,
                            niceY = true,
                            logXWhenFull = true,
                            emptyText = "Waiting for GPS…",
                            expected = ride.expectedSpeeds,
                            expectedColor = MaterialTheme.colorScheme.primary,
                            legend = true,
                        )
                        // Across from the legend: what the chart shows. Tapping the chart switches it.
                        ChartChip(
                            if (fullRide) "full ride" else "last 2 min",
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                        )
                    }
                }
            }

            // How the arrival time and its band have moved over the ride, on the speed chart's time
            // axis: tapping either chart switches both. Stop comes after it, behind the scroll.
            if (ride.route != null) {
                ChartCard(
                    "Arrival time", "predicted arrival and its ± · same time axis as speed",
                    Modifier.clickable { fullRide = !fullRide },
                ) {
                    Box(Modifier.fillMaxWidth().height(180.dp)) {
                        ArrivalChart(ride.arrivalTrail, timeAxis, Modifier.fillMaxSize())
                        ChartChip(
                            if (fullRide) "full ride" else "last 2 min",
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                        )
                    }
                }
            }

            if (ride.route != null) LiveMapCard(ride, now)

            // Music: last before Stop, so the screen down to the speed chart stays as calm as it was.
            MediaCard(context.appContainer.mediaControls)

            Button(
                onClick = { if (confirmStop) onStop() else confirmStop = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (confirmStop) ButtonDefaults.buttonColors(containerColor = Danger)
                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text(if (confirmStop) "Tap again to stop" else "Stop ride", style = MaterialTheme.typography.titleMedium) }
        }

        // Above the page rather than in it, so it can't be scrolled out of sight.
        val eta = ride.eta
        val route = ride.route
        if (ride.offerShare && ride.arrivedAtMs == null && eta != null && route != null) {
            AlmostThereBar(
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp, vertical = 8.dp),
                onYes = {
                    recorder.answerSharePrompt()
                    shareViaWhatsApp(
                        context,
                        etaMessage("I'm almost there!", eta, route.progress.remainingM, s.lastLat, s.lastLon, liveLink),
                    )
                },
                onNo = { recorder.answerSharePrompt() },
            )
        }
    }
}

private data class Preview(val eta: Eta, val wind: WindSample?)

/** "Leave now, arrive at 08:43": the ETA for the whole route with the current forecast and your usual form. */
@Composable
private fun LeaveNowPreview(routeId: Long) {
    val container = LocalContext.current.appContainer
    // Worked out again once the rides have been read at start-up (before that it is the old
    // guesses), and when the rider or bike changes, e.g. picking another bike on this screen.
    val learned by container.riderModel.learned.collectAsStateWithLifecycle()
    val setup by container.riderSetup.setup.collectAsStateWithLifecycle()
    val preview by produceState<Preview?>(null, routeId, learned, setup) {
        val route = container.routes.load(routeId) ?: return@produceState
        val model = route.etaModel(learned, setup.params())
        val rides = container.db.rides()
        val since = System.currentTimeMillis() - Banister.WINDOW_DAYS * 86_400_000L
        val start = startingForm(rides.recentForms(), rides.loadsSince(since), System.currentTimeMillis())
        val form = FormEstimate(start, (learned.formSd * start).let { it * it })
        while (true) {
            val weather = container.weather.alongRoute(route.line)
            val now = System.currentTimeMillis()
            // The headline wind is the one halfway along: a fair summary of the whole route.
            value = Preview(model.predict(0.0, now, weather, form), weather?.at(route.line.lengthM / 2, now))
            delay(60_000)
        }
    }
    val p = preview ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
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
                    "Wind %s %d Bft".format(compassPoint(w.fromDeg), beaufort(w.speedMps))
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
private fun AlmostThereBar(onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
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

/**
 * Arrival time, route progress and the wind as you feel it: the heart of the ride screen.
 * Tapping it shares the ETA, which keeps a button off a screen read at arm's length.
 */
@Composable
private fun EtaCard(r: RideRoute, live: LiveEta?, lat: Double?, lon: Double?, liveLink: String?) {
    val context = LocalContext.current
    val p = r.progress
    // The ride being recorded isn't among them yet: it only gets its form when it ends.
    val usualW by produceState<Double?>(null) { value = usualWatts(context.appContainer.db.rides().recentRidesWithForm()) }
    val share = Modifier.clickable(enabled = live != null) {
        live?.let {
            context.startActivity(
                Intent.createChooser(etaIntent(etaMessage("On my way!", it, p.remainingM, lat, lon, liveLink)), "Share ETA")
            )
        }
    }
    Card(Modifier.fillMaxWidth().then(share)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Arrival · ${r.name}" + if (r.provisional) " (still deciding)" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            ProgressTrack(r, Modifier.fillMaxWidth().height(28.dp))
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
                    Text(
                        // Your pedal power so far (none on a bike that rides itself), beside your usual from
                        // your own last real rides: never against a model rider.
                        listOfNotNull(powerText(it.pedalPowerW), it.pedalPowerW?.let { usualPowerText(usualW) })
                            .joinToString(" · ") { s -> s.replace(' ', ' ') },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * "On my way! Arriving around 19:34 (± 6 min), 3.2 km to go." plus somewhere to look: the live page
 * while sharing, otherwise a pin where you were when you sent it.
 */
private fun etaMessage(
    opening: String,
    live: LiveEta,
    remainingM: Double,
    lat: Double?,
    lon: Double?,
    liveLink: String?,
): String = buildString {
    append("$opening Arriving around ${formatClock(live.eta.arrivalMs)} (± ${formatMargin(live.eta.bandS)}), ")
    append("%.1f km to go.".format(remainingM / 1000))
    when {
        liveLink != null -> append("\nFollow me: $liveLink")
        lat != null && lon != null -> append("\nhttps://maps.google.com/?q=%.5f,%.5f".format(lat, lon))
    }
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
        val small = MaterialTheme.typography.labelSmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(if (exposureKnown) "%d km/h".format(kmh) else "~%d km/h".format(kmh), style = small, color = muted)
        if (exposureKnown) Text(shelter, style = small, color = muted)
    }
}

/** 42 s -> "40 s", 95 s -> "2 min" */
private fun formatMargin(seconds: Double): String =
    if (seconds < Eta.FIRM_BAND_S) "${maxOf(5, (seconds / 5).roundToInt() * 5)} s" else "${(seconds / 60).roundToInt()} min"

private val SunColor = Color(0xFFFFD233)
private val RainColor = Color(0xFF2F6FFF)

/**
 * Yellow under a clear sky, fading to white as it clouds over, then blue where rain is forecast:
 * clearly blue from drizzle on ([rainBlueShare]).
 */
private fun skyColor(s: Sky): Color {
    val clearToCloudy = lerp(SunColor, Color.White, (s.cloudPct / 100).toFloat().coerceIn(0f, 1f))
    return lerp(clearToCloudy, RainColor, rainBlueShare(s.rainMmH).toFloat())
}

/** From a fifth of the time lost or saved, a segment is fully red or green: a strong wind in the open. */
private const val FULL_WIND_IMPACT = 0.2

/** Red where the wind slows you, green where it pushes you, yellow where it makes no difference. */
private fun windColor(impact: Double): Color = scaleColor(impact / FULL_WIND_IMPACT)

/**
 * The whole route in two bars, labelled "sky" and "wind": the weather over each segment, and below
 * it what the wind does to you there. Segments run into each other without gaps, so the colours
 * read as a band rather than a row of blocks. A triangle underneath points at where you are.
 */
@Composable
private fun ProgressTrack(r: RideRoute, modifier: Modifier) {
    val unknown = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, lineHeight = 9.sp)
    Canvas(modifier) {
        val labels = listOf("sky", "wind").map { measurer.measure(it, labelStyle) }
        val left = labels.maxOf { it.size.width } + 6.dp.toPx()
        val len = r.progress.lengthM.toFloat()
        fun x(m: Double) = left + (m.toFloat() / len).coerceIn(0f, 1f) * (size.width - left)
        val barH = 8.dp.toPx()

        fun bar(top: Float, label: Int, stripesOf: (SegmentWeather) -> Double = { 0.0 }, colorOf: (SegmentWeather) -> Color) {
            val l = labels[label]
            drawText(l, topLeft = Offset(0f, top + (barH - l.size.height) / 2))
            val shape = Path().apply {
                addRoundRect(RoundRect(left, top, size.width, top + barH, CornerRadius(barH / 2)))
            }
            clipPath(shape) {
                r.segmentStartsM.forEachIndexed { i, startM ->
                    val x0 = x(startM)
                    val x1 = x(r.segmentStartsM.getOrNull(i + 1) ?: r.progress.lengthM)
                    val color = r.weather.getOrNull(i)?.let(colorOf) ?: unknown
                    // A pixel of overlap, so no hairline seam shows between two blocks.
                    drawRect(color, Offset(x0, top), Size(x1 - x0 + 1f, barH))
                    // Diagonal stripes: it could rain here, though no rain is forecast.
                    val stripes = r.weather.getOrNull(i)?.let(stripesOf) ?: 0.0
                    if (stripes > 0) clipRect(x0, top, x1 + 1f, top + barH) {
                        val gap = 4.dp.toPx()
                        var sx = x0 - barH
                        while (sx < x1 + 1f) {
                            drawLine(
                                RainColor.copy(alpha = stripes.toFloat()),
                                Offset(sx, top + barH),
                                Offset(sx + barH, top),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                            sx += gap
                        }
                    }
                }
            }
        }
        bar(0f, 0, stripesOf = { w -> w.sky?.let(::rainChanceStripes) ?: 0.0 }) { w -> w.sky?.let(::skyColor) ?: unknown }
        val windTop = barH + 2.dp.toPx()
        bar(windTop, 1) { w -> windColor(w.windImpact) }

        val half = 5.dp.toPx()
        val at = x(r.progress.progressM).coerceIn(left + half, size.width - half)
        val apex = windTop + barH + 1.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(at, apex)
                lineTo(at - half, apex + 8.dp.toPx())
                lineTo(at + half, apex + 8.dp.toPx())
                close()
            },
            // Neutral: red and green already say what the wind does.
            marker,
        )
    }
}

/** A small annotation inside a chart's corner, on the same translucent chip as the legend. */
@Composable
private fun ChartChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 12.sp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
