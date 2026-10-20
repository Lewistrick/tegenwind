package com.tegenwind.app.ride

import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.eta.EtaModel
import com.tegenwind.app.eta.EtaSegment
import com.tegenwind.app.eta.Physics
import com.tegenwind.app.eta.RiderParams
import com.tegenwind.app.eta.SteadyPower
import com.tegenwind.app.eta.SpeedProfile
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.RouteTracker
import com.tegenwind.app.weather.RouteWeather
import kotlin.math.ceil

/** Which earlier ride to ride against. */
enum class GhostChoice(val label: String, val words: String) {
    /** Not a ride at all: what your usual effort gives today, see [expectedGhost]. */
    EXPECTED("Expected ride", "the expected ride"),
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

    /**
     * How many seconds into its ride the ghost first reached [alongM]: where it stood still, the
     * moment it got there. Null beyond its last spot on the route.
     */
    fun elapsedAt(alongM: Double): Double? {
        if (alongM <= this.alongM.first()) return elapsedS.first()
        if (alongM > this.alongM.last()) return null
        // The first index at or past [alongM]; the ghost never rides backwards, so this is ordered.
        var lo = 0
        var hi = this.alongM.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (this.alongM[mid] >= alongM) hi = mid else lo = mid + 1
        }
        val a = this.alongM[lo - 1]
        val b = this.alongM[lo]
        return elapsedS[lo - 1] + (alongM - a) / (b - a) * (elapsedS[lo] - elapsedS[lo - 1])
    }
}

/**
 * The ride your usual effort gives today: [powerW] of pedal power on each segment, against the
 * forecast wind for when you get there (all of it, as in [SteadyPower]), its slope and its
 * openness, shaped within the segment by [profile]. Wind costs or gains time, so into a headwind
 * it is slower than on a calm day. It never waits at a light: you race it, and a red light costs
 * you time against it. [startedAtMs] is when you set off.
 */
fun expectedGhost(
    segments: List<EtaSegment>,
    params: RiderParams,
    profile: SpeedProfile?,
    weather: RouteWeather?,
    powerW: Double,
    startedAtMs: Long,
): Ghost {
    val elapsed = arrayListOf(0.0)
    val along = arrayListOf(segments.firstOrNull()?.startM ?: 0.0)
    val rho = Physics.airDensity(null)
    var t = 0.0
    for (s in segments) {
        if (s.lengthM <= 0) continue
        val wind = weather?.at(s.midM, startedAtMs + (t * 1000).toLong())
        val head = wind?.let { Physics.headwindMps(it.speedMps, it.fromDeg, s.bearingDeg, s.exposure) } ?: 0.0
        val speed = Physics.speedMps(powerW, s.gradePct, head, rho, params).coerceAtLeast(EtaModel.MIN_SPEED_MPS)
        val steps = ceil(s.lengthM / EXPECTED_STEP_M).toInt().coerceAtLeast(1)
        val step = s.lengthM / steps
        for (i in 0 until steps) {
            val shape = profile?.factorAt(s.startM + (i + 0.5) * step, s.startM, s.endM) ?: 1.0
            t += step / (speed * shape)
            elapsed += t
            along += s.startM + (i + 1) * step
        }
    }
    if (elapsed.size < 2) {
        elapsed += 1.0
        along += along.first()
    }
    return Ghost(elapsed.toDoubleArray(), along.toDoubleArray())
}

/** How finely the expected ride follows the speed profile, as [EtaModel.movingTimeS] does. */
private const val EXPECTED_STEP_M = 12.5

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
        GhostChoice.EXPECTED -> null
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
