package com.tegenwind.app.ride

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.routes.RouteProgress
import com.tegenwind.app.routes.RouteTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The route being followed during a ride, with the parts the ride screen draws. */
data class RideRoute(
    val routeId: Long,
    val name: String,
    val segmentStartsM: List<Double>,
    val signalsAtM: List<Double>,
    val progress: RouteProgress,
)

data class LiveRide(
    val rideId: Long,
    val simulated: Boolean,
    val snapshot: RideSnapshot,
    val route: RideRoute? = null,
)

/**
 * Owns the ride in progress: feeds fixes to [RideTracker] (and [RouteTracker] when following a route),
 * exposes live stats to the UI, and writes track points to the database in batches.
 * All calls are expected on the main thread.
 */
class RideRecorder(private val dao: RideDao, private val scope: CoroutineScope) {

    private val _live = MutableStateFlow<LiveRide?>(null)
    val live: StateFlow<LiveRide?> = _live.asStateFlow()

    private var tracker: RideTracker? = null
    private var routeTracker: RouteTracker? = null
    private val pending = ArrayList<TrackPointEntity>()
    private var lastFlushMs = 0L

    suspend fun start(simulated: Boolean, route: LoadedRoute?) {
        if (_live.value != null) return
        closeUnfinished()
        val now = System.currentTimeMillis()
        val id = dao.insertRide(RideEntity(startedAtMs = now, simulated = simulated, routeId = route?.route?.id))
        val t = RideTracker(now)
        tracker = t
        routeTracker = route?.let { RouteTracker(it.line) }
        lastFlushMs = now
        _live.value = LiveRide(
            rideId = id,
            simulated = simulated,
            snapshot = t.snapshot(),
            route = route?.let {
                RideRoute(
                    routeId = it.route.id,
                    name = it.route.name,
                    segmentStartsM = it.segments.map { s -> s.startM },
                    signalsAtM = it.signalsAtM,
                    progress = routeTracker!!.progress(),
                )
            },
        )
    }

    fun onFix(fix: Fix) {
        val ride = _live.value ?: return
        val t = tracker ?: return
        pending += TrackPointEntity(
            rideId = ride.rideId, timeMs = fix.timeMs, lat = fix.lat, lon = fix.lon,
            speedMps = fix.speedMps, accuracyM = fix.accuracyM, altitudeM = fix.altitudeM,
        )
        if (t.add(fix)) {
            val progress = routeTracker?.update(GeoPoint(fix.lat, fix.lon))
            _live.value = ride.copy(
                snapshot = t.snapshot(),
                route = ride.route?.let { r -> progress?.let { r.copy(progress = it) } ?: r },
            )
        }
        if (fix.timeMs - lastFlushMs >= FLUSH_EVERY_MS) {
            lastFlushMs = fix.timeMs
            flush()
        }
    }

    /** Ends the ride and returns its id, or null if nothing was recording. */
    suspend fun stop(): Long? {
        val ride = _live.value ?: return null
        val snap = tracker?.snapshot() ?: ride.snapshot
        val batch = pending.toList()
        pending.clear()
        if (batch.isNotEmpty()) dao.insertPoints(batch)
        dao.updateRide(
            RideEntity(
                id = ride.rideId,
                startedAtMs = snap.startedAtMs,
                endedAtMs = snap.lastFixMs ?: System.currentTimeMillis(),
                distanceM = snap.distanceM,
                movingMs = snap.movingMs,
                simulated = ride.simulated,
                routeId = ride.route?.routeId,
            )
        )
        tracker = null
        routeTracker = null
        _live.value = null
        return ride.rideId
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        scope.launch { dao.insertPoints(batch) }
    }

    /** A ride left open by a crash or a killed app is closed at its last recorded fix. */
    private suspend fun closeUnfinished() {
        dao.unfinishedRides().forEach { r ->
            dao.updateRide(r.copy(endedAtMs = dao.lastPointTime(r.id) ?: r.startedAtMs))
        }
    }

    private companion object {
        const val FLUSH_EVERY_MS = 10_000L
    }
}
