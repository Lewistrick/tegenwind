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
            )
            ACTION_STOP -> stopRide()
        }
        return START_NOT_STICKY
    }

    private fun startRide(simulated: Boolean, routeId: Long?) {
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting ride…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        scope.launch {
            val route = routeId?.let { appContainer.routes.load(it) }
            recorder.start(simulated, route)
            if (simulated) simulation = launch { RideSimulator.run(route?.line, recorder::onFix) }
            else startGps()
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
            recorder.stop()
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
        val routePart = ride.route?.let { r -> "%.1f km to go · ".format(r.progress.remainingM / 1000) } ?: ""
        val text = routePart + "%.1f km · %s moving".format(s.distanceM / 1000, formatElapsed(s.movingMs)) +
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
        private const val NO_ROUTE = -1L

        fun start(context: Context, simulated: Boolean, routeId: Long?) {
            val intent = Intent(context, RideService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SIMULATE, simulated)
                .putExtra(EXTRA_ROUTE_ID, routeId ?: NO_ROUTE)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** 754_000 -> "12:34", 3_725_000 -> "1:02:05" */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
