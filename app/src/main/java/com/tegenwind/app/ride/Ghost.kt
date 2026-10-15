package com.tegenwind.app.ride

import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.RouteTracker

/** Which earlier ride to ride against. */
enum class GhostChoice(val label: String, val words: String) {
    MEDIAN("Your median ride", "your median ride"),
    BEST("Your best ride", "your best ride"),
    LAST("Your last ride", "your last ride"),
}

/**
 * An earlier ride on the same route, replayed: how far along the route it was after each second
 * since it set off. Standing still counts, as it does for the ride you compare it with.
 */
class Ghost(private val elapsedS: DoubleArray, private val alongM: DoubleArray) {
    init {
        require(elapsedS.size == alongM.size && elapsedS.isNotEmpty())
    }

    /** How long the ghost took to its last spot on the route. */
    val totalS: Double get() = elapsedS.last()

    /** Where along the route the ghost was [afterS] seconds into its ride. */
    fun alongAt(afterS: Double): Double {
        if (afterS <= elapsedS.first()) return alongM.first()
        if (afterS >= elapsedS.last()) return alongM.last()
        val found = elapsedS.binarySearch(afterS)
        if (found >= 0) return alongM[found]
        val i = -found - 2
        val span = elapsedS[i + 1] - elapsedS[i]
        return if (span > 0) alongM[i] + (afterS - elapsedS[i]) / span * (alongM[i + 1] - alongM[i]) else alongM[i]
    }
}

/**
 * The ride to chase out of [rides] (real, finished rides on one route): the fastest, the one in the
 * middle, or the latest. By time from start to finish, as rides are ranked elsewhere. Rides that
 * covered less than most of [routeLengthM] don't count: a ride broken off early isn't a fast one.
 */
fun pickGhostRide(rides: List<RideEntity>, choice: GhostChoice, routeLengthM: Double): RideEntity? {
    val whole = rides.filter { it.endedAtMs != null && it.distanceM >= GHOST_MIN_SHARE * routeLengthM }
    if (whole.isEmpty()) return null
    fun total(r: RideEntity) = r.endedAtMs!! - r.startedAtMs
    return when (choice) {
        GhostChoice.BEST -> whole.minBy(::total)
        GhostChoice.MEDIAN -> whole.sortedBy(::total)[whole.size / 2]
        GhostChoice.LAST -> whole.maxBy { it.startedAtMs }
    }
}

/**
 * Replays [points] (one ride, in order) along [line] the way the ride screen follows a route. Null
 * when the ride was hardly on the line. [startedAtMs] is when that ride set off.
 */
fun ghostOf(line: Polyline, startedAtMs: Long, points: List<TrackPointEntity>): Ghost? {
    val tracker = RouteTracker(line)
    val elapsed = ArrayList<Double>()
    val along = ArrayList<Double>()
    for (p in points) {
        if ((p.accuracyM ?: 0.0) > RideTracker.MAX_ACCURACY_M) continue
        val progress = tracker.update(GeoPoint(p.lat, p.lon))
        if (!progress.onRouteYet) continue
        val t = (p.timeMs - startedAtMs) / 1000.0
        // Time only runs forward, and a ghost never rides backwards.
        if (elapsed.isNotEmpty() && t <= elapsed.last()) continue
        elapsed += t
        along += maxOf(progress.progressM, along.lastOrNull() ?: 0.0)
    }
    if (elapsed.size < 2 || along.last() < GHOST_MIN_SHARE * line.lengthM) return null
    return Ghost(elapsed.toDoubleArray(), along.toDoubleArray())
}

private const val GHOST_MIN_SHARE = 0.9
