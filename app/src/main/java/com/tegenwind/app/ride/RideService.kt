package com.tegenwind.app.ride

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tegenwind.app.MainActivity
import com.tegenwind.app.R
import com.tegenwind.app.appContainer
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.startingForm
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.habitByRoute
import com.tegenwind.app.weather.RouteWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps GPS running while you ride, also with the screen off or another app in front. */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder by lazy { appContainer.recorder }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var simulation: Job? = null
    private var gpsOn = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { l ->
                recorder.onFix(
                    Fix(
                        timeMs = l.time,
                        lat = l.latitude,
                        lon = l.longitude,
                        speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
                        accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                        altitudeM = if (l.hasAltitude()) l.altitude else null,
                    )
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRide(
                simulated = intent.getBooleanExtra(EXTRA_SIMULATE, false),
                routeId = intent.getLongExtra(EXTRA_ROUTE_ID, NO_ROUTE).takeIf { it != NO_ROUTE },
                shareLive = intent.getBooleanExtra(EXTRA_SHARE_LIVE, false),
            )
            ACTION_STOP -> stopRide()
        }
        return START_NOT_STICKY
    }

    private fun startRide(simulated: Boolean, routeId: Long?, shareLive: Boolean) {
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting ride…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        scope.launch {
            val container = appContainer
            val route = routeId?.takeIf { it != AUTO_ROUTE }?.let { container.routes.load(it) }
            val rides = container.db.rides()
            val now = System.currentTimeMillis()
            // Auto-select: every saved route is a candidate until riding rules it out.
            val candidates = if (routeId == AUTO_ROUTE) container.routes.loadAll() else emptyList()
            recorder.start(
                simulated = simulated,
                route = route,
                priorForm = startingForm(rides.recentForms(), rides.loadsSince(now - Banister.WINDOW_DAYS * 86_400_000L), now),
                candidates = candidates,
                habit = habitByRoute(rides.recentRouteStarts(), now),
            )
            // Forecast refreshed every 15 minutes: every ~2 km along the route being ridden, or on a
            // free ride for wherever you are by then (which also means waiting for the first fix).
            // With auto-select the route can still change, so it refetches when it does.
            launch {
                var fetchedFor: Long? = null
                var fetchedAtMs = 0L
                while (isActive) {
                    val riding = recorder.ridingRoute
                    val stale = System.currentTimeMillis() - fetchedAtMs >= 15 * 60_000L
                    if (!stale && riding?.route?.id == fetchedFor) {
                        delay(5_000)
                        continue
                    }
                    val weather = if (riding != null) container.weather.alongRoute(riding.line)
                    else {
                        val here = recorder.live.value?.snapshot
                            ?.let { s -> if (s.lastLat != null && s.lastLon != null) GeoPoint(s.lastLat, s.lastLon) else null }
                        if (here == null) {
                            delay(5_000)
                            continue
                        }
                        container.weather.forecast(here)?.let(RouteWeather::everywhere)
                    }
                    if (weather != null) {
                        recorder.setWeather(weather)
                        fetchedFor = riding?.route?.id
                        fetchedAtMs = System.currentTimeMillis()
                    }
                    delay(5_000)
                }
            }
            // Live sharing, when you asked for it before setting off. Nothing is sent until the ride
            // has actually started, so waiting at the door doesn't broadcast where you live.
            if (shareLive && container.liveShare.configured) {
                container.liveShare.begin()
                launch {
                    while (isActive) {
                        val live = recorder.live.value
                        if (live == null || !live.started) {
                            delay(2_000)
                            continue
                        }
                        container.liveShare.push(live, recorder.ridingRoute)
                        delay(if (live.snapshot.paused) 15_000L else 5_000L)
                    }
                }
            }
            if (simulated) {
                // Simulating auto-select: ride the first candidate and let the matcher find it.
                val line = route ?: candidates.firstOrNull()
                simulation = launch {
                    RideSimulator.run(line?.line, line?.signalsAtM.orEmpty(), recorder::simulatedSpeedAt, recorder::onFix)
                }
            } else startGps()
            // Finish by itself once the route's end is reached.
            launch {
                while (isActive) {
                    val arrivedAt = recorder.live.value?.arrivedAtMs
                    if (arrivedAt != null && AutoFinish.dueToStop(arrivedAt, System.currentTimeMillis())) {
                        stopRide()
                        break
                    }
                    delay(1_000)
                }
            }
            while (isActive) {
                recorder.live.value?.let { updateNotification(it) }
                delay(5_000)
            }
        }
    }

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopRide()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        gpsOn = true
    }

    private fun stopRide() {
        if (gpsOn) fused.removeLocationUpdates(callback)
        gpsOn = false
        simulation?.cancel()
        scope.launch {
            // Say goodbye before the ride is cleared, so the last thing shared is the arrival.
            val last = recorder.live.value
            val route = recorder.ridingRoute
            recorder.stop()
            appContainer.liveShare.finish(last, route)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (gpsOn) fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride)
            .setContentTitle("Tegenwind is recording")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(ride: LiveRide) {
        val s = ride.snapshot
        val etaPart = ride.eta?.let { "ETA ${formatClock(it.eta.arrivalMs)} · " } ?: ""
        val routePart = etaPart + (ride.route?.let { r -> "%.1f km to go · ".format(r.progress.remainingM / 1000) } ?: "")
        if (ride.arrivedAtMs != null) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Arrived · finishing the ride"))
            return
        }
        val text = routePart + "%.1f km · %s moving".format(s.distanceM / 1000, formatElapsed(s.movingMs)) +
            (if (s.paused) " · paused" else "") +
            (if (ride.simulated) " · simulated" else "")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.tegenwind.app.START_RIDE"
        private const val ACTION_STOP = "com.tegenwind.app.STOP_RIDE"
        private const val EXTRA_SIMULATE = "simulate"
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_SHARE_LIVE = "shareLive"
        private const val NO_ROUTE = -1L

        /** Passed instead of a route id to let the ride work out the route as you go. */
        const val AUTO_ROUTE = -2L

        fun start(context: Context, simulated: Boolean, routeId: Long?, shareLive: Boolean = false) {
            val intent = Intent(context, RideService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SIMULATE, simulated)
                .putExtra(EXTRA_ROUTE_ID, routeId ?: NO_ROUTE)
                .putExtra(EXTRA_SHARE_LIVE, shareLive)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Wall-clock time as HH:mm in the phone's time zone. */
fun formatClock(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** 754_000 -> "12:34", 3_725_000 -> "1:02:05" */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
