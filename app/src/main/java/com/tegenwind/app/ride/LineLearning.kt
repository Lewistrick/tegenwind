package com.tegenwind.app.ride

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RouteDao
import com.tegenwind.app.data.RoutePointEntity
import com.tegenwind.app.data.RouteProfileEntity
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.eta.SpeedProfile
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LineFolder
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.SegmentSpan
import com.tegenwind.app.routes.bearingDeg
import com.tegenwind.app.routes.between
import com.tegenwind.app.routes.contradicts
import com.tegenwind.app.routes.lineFromTrack
import com.tegenwind.app.routes.rescaleSpans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.ln

/** The fixes of a ride accurate enough to say where it went. */
fun fixesOf(points: List<TrackPointEntity>): List<GeoPoint> =
    points.filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }.map { GeoPoint(it.lat, it.lon) }

/**
 * The segments of a relearned line, built from what is [stored] now. One that continues a stored
 * segment keeps what it learned and what the map lookup found; a new one starts from nothing.
 *
 * A segment that got longer or shorter keeps its expected time: its learned correction had absorbed
 * the length the old line had wrong, and physics now sees the length the road really has.
 */
fun rebuildSegments(
    routeId: Long,
    spans: List<SegmentSpan>,
    stored: List<RouteSegmentEntity>,
    line: Polyline,
): List<RouteSegmentEntity> {
    val byIdx = stored.associateBy { it.idx }
    return spans.mapIndexed { i, s ->
        val bearing = bearingDeg(line.pointAt(s.startM), line.pointAt(s.endM))
        val was = s.origIdx?.let(byIdx::get)
        if (was == null) {
            RouteSegmentEntity(routeId = routeId, idx = i, startM = s.startM, endM = s.endM, bearingDeg = bearing)
        } else {
            val oldLengthM = was.endM - was.startM
            val newLengthM = s.endM - s.startM
            val logMean = if (was.learnedPasses > 0 && oldLengthM > 0 && newLengthM > 0) {
                was.learnedLogMean - ln(newLengthM / oldLengthM)
            } else was.learnedLogMean
            was.copy(idx = i, startM = s.startM, endM = s.endM, bearingDeg = bearing, learnedLogMean = logMean)
        }
    }
}

/**
 * The speed profile carried over to a relearned line: each 25 m goes where [map] puts its middle.
 * Those on a stretch the line no longer follows go; of two landing together, the better known stays.
 */
fun remapProfile(bins: List<RouteProfileEntity>, map: (Double) -> Double?): List<RouteProfileEntity> {
    val out = HashMap<Int, RouteProfileEntity>()
    for (b in bins) {
        val at = map((b.bin + 0.5) * SpeedProfile.BIN_M) ?: continue
        if (at < 0) continue
        val bin = floor(at / SpeedProfile.BIN_M).toInt()
        val was = out[bin]
        if (was == null || b.rides > was.rides) out[bin] = b.copy(bin = bin)
    }
    return out.values.sortedBy { it.bin }
}

/**
 * Rebuilds a route's line when the latest ride that passed nearly all of its segments lies mostly
 * away from it: a line damaged by a faulty update, since no ride can do both. The line becomes the
 * way that ride went between the route's start and end. Every segment keeps its share of the
 * length, what it learned (at the same expected time) and what the map lookup found; the speed
 * profile is dropped, to be learned again from the stored rides. Returns true when it rebuilt.
 */
suspend fun repairLine(rideDao: RideDao, routeDao: RouteDao, routeId: Long, commit: () -> Boolean = { true }): Boolean {
    val stored = routeDao.full(routeId) ?: return false
    if (stored.points.size < 2 || stored.segments.isEmpty()) return false
    val minPasses = (stored.segments.size * FULL_RIDE_SHARE).toInt().coerceAtLeast(1)
    val ride = rideDao.lastFullRideOnRoute(routeId, minPasses) ?: return false
    val fixes = fixesOf(rideDao.points(ride.id))
    val old = Polyline(stored.points.map { GeoPoint(it.lat, it.lon) })
    val line = withContext(Dispatchers.Default) {
        if (!contradicts(old, fixes)) return@withContext null
        try {
            lineFromTrack(between(fixes, old.points.first(), old.points.last()))
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: return false
    val spans = rescaleSpans(stored.segments.map { it.startM to it.endM }, line.lengthM)
    if (spans.isEmpty() || !commit()) return false
    routeDao.relearnGeometry(
        routeId = routeId,
        lengthM = line.lengthM,
        foldedThroughMs = ride.startedAtMs,
        points = line.points.mapIndexed { i, p -> RoutePointEntity(routeId, i, p.lat, p.lon) },
        newIdx = spans.indices.associateWith { it },
    ) { segments, _ -> rebuildSegments(routeId, spans, segments, line) to emptyList() }
    return true
}

/** A ride that passed at least this share of a route's segments rode the route. */
private const val FULL_RIDE_SHARE = 0.9

/**
 * Folds the rides on a route that aren't in its line yet into it, oldest first: see [LineFolder].
 * Only [rerouteRideId], the ride that just finished, may reroute a stretch; the others only nudge.
 * Nothing is stored when [commit] says no once it's all worked out: a ride has started meanwhile, on
 * the line as it was. Once stored, [onFolded] hears of every ride folded in: the line before it and
 * after it (the same line when the ride didn't move it). Returns true when a stretch was rerouted,
 * which needs the map lookup again.
 */
suspend fun foldRidesIntoLine(
    rideDao: RideDao,
    routeDao: RouteDao,
    routeId: Long,
    rerouteRideId: Long? = null,
    commit: () -> Boolean = { true },
    onFolded: suspend (rideId: Long, planned: Polyline, after: Polyline) -> Unit = { _, _, _ -> },
): Boolean {
    val stored = routeDao.full(routeId) ?: return false
    if (stored.points.size < 2) return false
    val rides = rideDao.ridesOnRouteAfter(routeId, stored.route.lineFoldedThroughMs ?: Long.MIN_VALUE)
    if (rides.isEmpty()) return false
    val folder = LineFolder(
        Polyline(stored.points.map { GeoPoint(it.lat, it.lon) }),
        stored.segments.map { it.startM to it.endM },
    )
    // Each ride's line before and after it was folded in, for its map.
    val folded = ArrayList<Triple<Long, Polyline, Polyline>>()
    for (ride in rides) {
        val fixes = fixesOf(rideDao.points(ride.id))
        val earlier = if (ride.id != rerouteRideId) null else {
            rideDao.twoRidesOnRouteBefore(routeId, ride.startedAtMs).map { fixesOf(rideDao.points(it.id)) }
        }
        val planned = folder.line
        // Thousands of fixes against the line: off the main thread.
        withContext(Dispatchers.Default) { folder.fold(fixes, earlier) }
        folded += Triple(ride.id, planned, folder.line)
    }
    val foldedThroughMs = rides.last().startedAtMs
    if (!commit()) return false
    if (!folder.changed) {
        routeDao.setLineFolded(routeId, foldedThroughMs)
        folded.forEach { (rideId, planned, after) -> onFolded(rideId, planned, after) }
        return false
    }
    val line = folder.line
    val spans = folder.spans
    routeDao.relearnGeometry(
        routeId = routeId,
        lengthM = line.lengthM,
        foldedThroughMs = foldedThroughMs,
        points = line.points.mapIndexed { i, p -> RoutePointEntity(routeId, i, p.lat, p.lon) },
        newIdx = spans.withIndex().filter { it.value.origIdx != null }.associate { it.value.origIdx!! to it.index },
    ) { segments, profile ->
        rebuildSegments(routeId, spans, segments, line) to remapProfile(profile, folder::map)
    }
    folded.forEach { (rideId, planned, after) -> onFolded(rideId, planned, after) }
    val rerouted = folder.rerouted ?: return false
    if (rerouteRideId != null) rideDao.setReroute(rerouteRideId, rerouted.first, rerouted.second, folder.reroutedFully)
    return true
}
