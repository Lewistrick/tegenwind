package com.tegenwind.app.ui.rides

import com.tegenwind.app.ride.RideTracker
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.RouteTracker
import com.tegenwind.app.routes.distanceM
import kotlin.math.round

/*
 * How a ride's own track is coloured on its street map, kept apart from drawing so it can be tested.
 * The track is coloured fix by fix, and drawn as runs of fixes that share a colour.
 */

/**
 * For each fix of [track], the segment of the route it was ridden on: an index into [spans], the
 * segments' stretches of [line]. Null before reaching the route and where the ride was off it.
 * Follows the ride along the line the way the ride screen does, so a route that passes close to
 * itself doesn't make a fix jump to the wrong part.
 */
fun segmentsAlong(track: List<GeoPoint>, line: Polyline, spans: List<Pair<Double, Double>>): List<Int?> {
    if (spans.isEmpty()) return track.map { null }
    val tracker = RouteTracker(line)
    var i = 0
    return track.map { p ->
        val progress = tracker.update(p)
        if (!progress.onRouteYet || progress.offRoute) return@map null
        // Progress only moves forward, so the segment does too.
        while (i < spans.size - 1 && progress.progressM >= spans[i].second) i++
        i
    }
}

/** Each of [speeds] averaged with the [half] fixes either side that have one: GPS speed jumps from fix to fix. */
fun smoothSpeeds(speeds: List<Double?>, half: Int = 2): List<Double?> = speeds.indices.map { i ->
    if (speeds[i] == null) return@map null
    val near = (maxOf(0, i - half)..minOf(speeds.size - 1, i + half)).mapNotNull { speeds[it] }
    near.average()
}

/** From a quarter faster or slower than the ride's median, a fix is fully green or red. */
const val FULL_SPEED_SHARE = 0.25

/**
 * Where each of [speeds] (m/s) lies against the ride's median moving speed, on the scale of
 * [com.tegenwind.app.ui.theme.scaleColor]: 0 at the median, +1 a quarter faster or more, −1 a
 * quarter slower or more (standing still included). In steps of a quarter, so the track is drawn in
 * a few runs rather than a colour per fix. Null where a fix has no speed, or the ride never moved.
 */
fun speedAmounts(speeds: List<Double?>): List<Double?> {
    val moving = speeds.filterNotNull().filter { it >= RideTracker.STOP_SPEED_MPS }.sorted()
    if (moving.isEmpty()) return speeds.map { null }
    val median = moving[moving.size / 2]
    return speeds.map { v ->
        v?.let { round(((it - median) / (FULL_SPEED_SHARE * median)).coerceIn(-1.0, 1.0) * 4) / 4 }
    }
}

/** A stretch of the track in one colour: fixes [from] through [to], where [to] is the next run's first fix, so runs join. */
data class TrackRun<T>(val from: Int, val to: Int, val key: T?)

/** [keys] (one per fix) cut into runs of the same key. */
fun <T> runsOf(keys: List<T?>): List<TrackRun<T>> {
    if (keys.size < 2) return emptyList()
    val out = ArrayList<TrackRun<T>>()
    var start = 0
    for (i in 1 until keys.size) {
        if (keys[i] != keys[start]) {
            out += TrackRun(start, i, keys[start])
            start = i
        }
    }
    out += TrackRun(start, keys.size - 1, keys[start])
    // A last fix that differs makes a run of one point, with nothing to draw.
    return out.filter { it.to > it.from }
}

/** How far into the ride each fix of [track] was, in metres. */
fun distancesAlong(track: List<GeoPoint>): DoubleArray {
    val out = DoubleArray(track.size)
    for (i in 1 until track.size) out[i] = out[i - 1] + distanceM(track[i - 1], track[i])
    return out
}
