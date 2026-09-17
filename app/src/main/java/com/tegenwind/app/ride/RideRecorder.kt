package com.tegenwind.app.ride

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.RouteDao
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.EtaModel
import com.tegenwind.app.eta.FormEstimator
import com.tegenwind.app.eta.LiveEta
import com.tegenwind.app.eta.Physics
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.eta.SegmentLearner
import com.tegenwind.app.eta.etaModel
import com.tegenwind.app.eta.windNow
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.routes.RouteProgress
import com.tegenwind.app.routes.RouteTracker
import com.tegenwind.app.weather.WindForecast
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
    val eta: LiveEta? = null,
    /** Set when the end of the route is reached; the ride then finishes by itself. */
    val arrivedAtMs: Long? = null,
)

/**
 * Owns the ride in progress: feeds fixes to [RideTracker] (and [RouteTracker] when following a route),
 * keeps the ETA up to date, times each route segment, and writes everything to the database.
 * All calls are expected on the main thread.
 */
class RideRecorder(
    private val dao: RideDao,
    private val routeDao: RouteDao,
    private val scope: CoroutineScope,
) {

    private val _live = MutableStateFlow<LiveRide?>(null)
    val live: StateFlow<LiveRide?> = _live.asStateFlow()

    private var tracker: RideTracker? = null
    private var routeTracker: RouteTracker? = null
    private var route: LoadedRoute? = null
    private var model: EtaModel? = null
    private var form = FormEstimator()
    private var formObservations = 0
    private var forecast: WindForecast? = null
    private val pending = ArrayList<TrackPointEntity>()
    private val traversals = ArrayList<SegmentTraversalEntity>()
    private var lastFlushMs = 0L

    private var autoFinishCancelled = false

    // The segment being timed right now.
    private var segIdx = -1
    private var segEnterMs = 0L
    private var segEnterMovingMs = 0L
    private var segClean = false

    suspend fun start(simulated: Boolean, route: LoadedRoute?, priorForm: Double) {
        if (_live.value != null) return
        closeUnfinished()
        val now = System.currentTimeMillis()
        val id = dao.insertRide(RideEntity(startedAtMs = now, simulated = simulated, routeId = route?.route?.id))
        val t = RideTracker(now)
        tracker = t
        this.route = route
        routeTracker = route?.let { RouteTracker(it.line) }
        model = route?.etaModel()
        form = FormEstimator(prior = priorForm)
        formObservations = 0
        autoFinishCancelled = false
        forecast = null
        traversals.clear()
        segIdx = -1
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
        refreshEta(now)
    }

    fun setForecast(f: WindForecast) {
        forecast = f
        refreshEta(System.currentTimeMillis())
    }

    fun onFix(fix: Fix) {
        val ride = _live.value ?: return
        val t = tracker ?: return
        pending += TrackPointEntity(
            rideId = ride.rideId, timeMs = fix.timeMs, lat = fix.lat, lon = fix.lon,
            speedMps = fix.speedMps, accuracyM = fix.accuracyM, altitudeM = fix.altitudeM,
        )
        if (t.add(fix)) {
            val snap = t.snapshot()
            val progress = routeTracker?.update(GeoPoint(fix.lat, fix.lon))
            if (progress != null) timeSegments(progress, fix.timeMs, snap.movingMs)
            val arrived = when {
                ride.arrivedAtMs != null -> ride.arrivedAtMs
                autoFinishCancelled || progress == null || !progress.onRouteYet -> null
                AutoFinish.arrived(progress.remainingM) -> fix.timeMs
                else -> null
            }
            _live.value = ride.copy(
                snapshot = snap,
                route = ride.route?.let { r -> progress?.let { r.copy(progress = it) } ?: r },
                arrivedAtMs = arrived,
            )
            refreshEta(fix.timeMs)
        }
        if (fix.timeMs - lastFlushMs >= FLUSH_EVERY_MS) {
            lastFlushMs = fix.timeMs
            flush()
        }
    }

    /** Keeps a ride going that was about to finish on arrival; it won't ask again. */
    fun cancelAutoFinish() {
        autoFinishCancelled = true
        _live.value = _live.value?.copy(arrivedAtMs = null)
    }

    /** Target speed for the simulator: what the model predicts on this stretch, ridden a bit briskly. */
    fun simulatedSpeedAt(alongM: Double): Double? {
        val m = model ?: return null
        val seg = m.segmentAt(alongM) ?: return null
        return m.speedMps(seg, forecast?.at(System.currentTimeMillis())) * 1.05
    }

    /** Ends the ride and returns its id, or null if nothing was recording. */
    suspend fun stop(): Long? {
        val ride = _live.value ?: return null
        val snap = tracker?.snapshot() ?: ride.snapshot
        val batch = pending.toList()
        pending.clear()
        if (batch.isNotEmpty()) dao.insertPoints(batch)
        if (traversals.isNotEmpty()) dao.insertTraversals(traversals.toList())
        val startWind = forecast?.at(snap.startedAtMs)
        dao.updateRide(
            RideEntity(
                id = ride.rideId,
                startedAtMs = snap.startedAtMs,
                endedAtMs = snap.lastFixMs ?: System.currentTimeMillis(),
                distanceM = snap.distanceM,
                movingMs = snap.movingMs,
                simulated = ride.simulated,
                routeId = ride.route?.routeId,
                windSpeedMps = startWind?.speedMps,
                windFromDeg = startWind?.fromDeg,
                formFactor = form.estimate().mean.takeIf { formObservations >= MIN_FORM_OBSERVATIONS },
                loadTss = if (ride.simulated) null else Banister.loadFromRide(snap.movingMs, snap.distanceM),
            )
        )
        // A made-up ride must not teach the model anything about the real road.
        if (!ride.simulated) learnFromTraversals(route, traversals)
        tracker = null
        routeTracker = null
        route = null
        model = null
        traversals.clear()
        _live.value = null
        return ride.rideId
    }

    private fun refreshEta(nowMs: Long) {
        val ride = _live.value ?: return
        val m = model ?: return
        val progress = ride.route?.progress ?: return
        val live = LiveEta(
            eta = m.predict(progress.progressM, nowMs, forecast, form.estimate()),
            form = form.estimate().mean,
            wind = windNow(m, progress.progressM, nowMs, forecast),
        )
        _live.value = ride.copy(eta = live)
    }

    /**
     * Times each segment from entering to leaving it. Only complete, on-route passes count:
     * they update today's form and are stored for stats and learning.
     */
    private fun timeSegments(p: RouteProgress, nowMs: Long, movingMs: Long) {
        val m = model ?: return
        val segs = route?.segments ?: return
        if (!p.onRouteYet) return
        if (p.offRoute) {
            segClean = false
            return
        }
        val idx = if (p.remainingM < FINISH_WITHIN_M) segs.size else segs.indexOfFirst { p.progressM < it.endM }.let { if (it < 0) segs.size else it }
        if (segIdx < 0) {
            segIdx = idx
            segEnterMs = nowMs
            segEnterMovingMs = movingMs
            segClean = idx < segs.size && p.progressM <= segs[idx].startM + CLEAN_ENTRY_M
            return
        }
        while (idx > segIdx) {
            if (segClean && segIdx in segs.indices) {
                val s = segs[segIdx]
                val etaSeg = m.segmentAt((s.startM + s.endM) / 2)!!
                val wind = forecast?.at(segEnterMs)
                val moved = movingMs - segEnterMovingMs
                val predictedMs = (etaSeg.lengthM / m.speedMps(etaSeg, wind) * 1000).toLong()
                traversals += SegmentTraversalEntity(
                    rideId = _live.value!!.rideId,
                    routeId = route!!.route.id,
                    segIdx = segIdx,
                    enterMs = segEnterMs,
                    exitMs = nowMs,
                    movingMs = moved,
                    headwindMps = wind?.let { Physics.headwindMps(it.speedMps, it.fromDeg, etaSeg.bearingDeg, etaSeg.exposure) },
                    predictedMovingMs = predictedMs,
                )
                // Form measures how today differs from what this segment normally costs, so it is
                // compared against the corrected time: a permanently slow corner is not bad form.
                val expectedMs = predictedMs * etaSeg.learned.timeFactor
                if (moved >= MIN_MOVING_MS && form.observe(expectedMs / moved)) formObservations++
            }
            segIdx++
            segEnterMs = nowMs
            segEnterMovingMs = movingMs
            // Skipping segments in one go (GPS gap) means the next one wasn't entered at its start.
            segClean = idx == segIdx
        }
    }

    /**
     * Layer 2 learning: each segment ridden cleanly this ride folds its actual time into what that
     * segment knows, so the next ETA over this road starts from experience instead of from physics.
     */
    private suspend fun learnFromTraversals(loaded: LoadedRoute?, ridden: List<SegmentTraversalEntity>) {
        if (loaded == null || ridden.isEmpty()) return
        val bySegIdx = loaded.segments.associateBy { it.idx }
        val updated = ridden.mapNotNull { t ->
            val seg = bySegIdx[t.segIdx] ?: return@mapNotNull null
            val learned = SegmentLearner.update(
                SegmentCorrection(seg.learnedLogMean, seg.learnedLogVar, seg.learnedPasses),
                t.movingMs,
                t.predictedMovingMs,
            )
            if (learned.passes == seg.learnedPasses) null
            else seg.copy(
                learnedLogMean = learned.logMean,
                learnedLogVar = learned.logVar,
                learnedPasses = learned.passes,
            )
        }
        if (updated.isNotEmpty()) routeDao.updateSegments(updated)
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
        const val FINISH_WITHIN_M = 20.0
        const val CLEAN_ENTRY_M = 30.0
        const val MIN_MOVING_MS = 5_000L
        const val MIN_FORM_OBSERVATIONS = 3
    }
}
