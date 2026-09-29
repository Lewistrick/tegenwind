package com.tegenwind.app.routes

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Route lines that follow your rides.
 *
 * A route's line is drawn once, from a GPX file or from one ride, and then ridden a little
 * differently: on the cycle path beside the road it follows, cutting its corners, or down another
 * street for a stretch. Every ride moves the line a little toward where it went ([nudge]), and a
 * stretch ridden elsewhere on two of the last three rides is rerouted ([detours], [splice]). Each
 * change says where distances along the old line land on the new one ([AlongMap]), so what the
 * route learned per segment and per 25 m stays with the road it was learned on.
 */
object RouteDrift {
    /** Closer to the line than this, a fix is on the same road, just beside the line; further, it's another street. */
    const val REACH_M = RouteTracker.ACQUIRE_M

    /** Each ride moves the line this share of the way: a fading average over about the last seven rides. */
    const val NUDGE_WEIGHT = 0.25

    /** The line is moved at points no more than this far apart. */
    const val STATION_M = 10.0

    /** Only fixes placed within this distance along the line, either way, can move a point of it. */
    const val WINDOW_M = 50.0

    /** Two fixes further apart than this (a GPS gap) don't say where the ride went in between. */
    const val MAX_STEP_M = 50.0

    /** Where the ride passes beside the line, not a street leaving it at an angle: this much along is fine. */
    const val ABEAM_SLACK_M = 2.0

    /**
     * Offsets are averaged over this many points either side, so GPS jitter doesn't put kinks in the
     * line. Not more: at a cut corner the offsets change sign within 20 m, and would cancel out.
     */
    const val SMOOTH_POINTS = 1

    /** Below this nothing moved enough to be worth storing: the line settles within a metre of where you ride. */
    const val MIN_MOVE_M = 0.25

    /** The moved line is stored without the points that lie within this of the rest. */
    const val STORE_TOLERANCE_M = 1.0

    /** A ride says something about a line only when it was on at least this share of it. */
    const val MIN_COVERAGE = 0.5
    const val COVER_STEP_M = 25.0

    /** A detour is off the line for at least this many fixes, and its ends are this far apart. */
    const val MIN_DETOUR_FIXES = 3
    const val MIN_DETOUR_M = 50.0

    /** A detour winding more than this compared to the straight line between its ends is an errand, not a road. */
    const val MAX_WINDING = 3.0

    /** Two detours are the same when they leave and rejoin within this of each other... */
    const val MATCH_ENDS_M = 60.0

    /** ...and nearly all of each ([MATCH_SHARE], sampled every [MATCH_STEP_M]) is this close to the other. */
    const val MATCH_PATH_M = 25.0
    const val MATCH_STEP_M = 20.0
    const val MATCH_SHARE = 0.9
}

private const val M_PER_DEG_LAT = 110_574.0

private fun mPerDegLon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

/**
 * Where distances along a route's old line land on its new one: piecewise linear through the pairs
 * ([fromM], [toM]), and null inside [gap], a stretch of the old line the new one no longer follows.
 */
class AlongMap(
    private val fromM: DoubleArray,
    private val toM: DoubleArray,
    val gap: Pair<Double, Double>? = null,
) {
    init {
        require(fromM.size >= 2 && fromM.size == toM.size)
    }

    fun map(oldM: Double): Double? {
        if (gap != null && oldM > gap.first && oldM < gap.second) return null
        if (oldM <= fromM.first()) return toM.first() + oldM - fromM.first()
        if (oldM >= fromM.last()) return toM.last() + oldM - fromM.last()
        val found = fromM.binarySearch(oldM)
        if (found >= 0) return toM[found]
        val i = -found - 2
        val span = fromM[i + 1] - fromM[i]
        return if (span > 0) toM[i] + (oldM - fromM[i]) / span * (toM[i + 1] - toM[i]) else toM[i]
    }
}

/** A fix placed on a line: how far along it, and how far beside it. */
class Placed(val point: GeoPoint, val alongM: Double, val offsetM: Double)

/**
 * Places a ride's fixes on [line] the way the ride screen follows it: forward only, from the first
 * fix within reach of the line. The fixes before that are left out.
 */
fun place(line: Polyline, fixes: List<GeoPoint>): List<Placed> {
    val tracker = RouteTracker(line)
    return fixes.mapNotNull { p ->
        val progress = tracker.update(p)
        if (progress.onRouteYet) Placed(p, progress.progressM, progress.offsetM) else null
    }
}

/** The share of [line] a ride was on: of its 25 m stretches, those with a fix within reach. */
fun coverage(line: Polyline, placed: List<Placed>): Double {
    val n = max(1, ceil(line.lengthM / RouteDrift.COVER_STEP_M).toInt())
    val hit = BooleanArray(n)
    for (p in placed) {
        if (p.offsetM <= RouteDrift.REACH_M) hit[(p.alongM / RouteDrift.COVER_STEP_M).toInt().coerceIn(0, n - 1)] = true
    }
    return hit.count { it }.toDouble() / n
}

/** [points] with more put in between, so none are more than [maxGapM] apart. The corners stay where they are. */
fun densify(points: List<GeoPoint>, maxGapM: Double): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    fun add(p: GeoPoint) {
        if (out.isEmpty() || distanceM(out.last(), p) > 1e-3) out += p
    }
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val n = max(1, ceil(distanceM(a, b) / maxGapM).toInt())
        for (k in 0 until n) add(GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n))
    }
    add(points.last())
    return out
}

/** A line moved toward one ride, and where distances along the old line land on it. */
class Nudge(val line: Polyline, val map: AlongMap, /** The furthest any point of the line moved. */ val movedM: Double)

/**
 * Moves [line] toward where a ride went beside it, [weight] of the way. Null when nothing moved
 * enough to be worth storing.
 *
 * Every 10 m the line looks sideways for the ride's path: the nearest point of it, using only fixes
 * within reach and placed near that spot. Only the sideways part counts, so the line never slides
 * along itself, and only where the ride passes alongside rather than turning off at an angle. A
 * stretch the ride didn't pass, or passed on another street, stays where it is.
 */
fun nudge(line: Polyline, placed: List<Placed>, weight: Double = RouteDrift.NUDGE_WEIGHT): Nudge? {
    val stations = densify(line.points, RouteDrift.STATION_M)
    if (stations.size < 2 || placed.size < 2) return null
    val alongOld = Polyline(stations).cumulative
    // Placed fixes only move forward along the line, so they're in order of distance along it.
    val alongs = DoubleArray(placed.size) { placed[it].alongM }

    val offset = DoubleArray(stations.size) { Double.NaN }
    val nx = DoubleArray(stations.size)
    val ny = DoubleArray(stations.size)
    for (i in stations.indices) {
        val p = stations[i]
        val kx = mPerDegLon(p.lat)
        // The line's direction here, through both neighbours: at a corner, halfway between its two legs.
        val prev = stations[max(i - 1, 0)]
        val next = stations[min(i + 1, stations.lastIndex)]
        val tx = (next.lon - prev.lon) * kx
        val ty = (next.lat - prev.lat) * M_PER_DEG_LAT
        val len = hypot(tx, ty)
        if (len == 0.0) continue
        nx[i] = -ty / len
        ny[i] = tx / len

        var best = Double.MAX_VALUE
        var bestOffset = Double.NaN
        var j = lowerBound(alongs, alongOld[i] - RouteDrift.WINDOW_M)
        while (j + 1 < placed.size && alongs[j + 1] <= alongOld[i] + RouteDrift.WINDOW_M) {
            val a = placed[j]
            val b = placed[j + 1]
            j++
            if (a.offsetM > RouteDrift.REACH_M || b.offsetM > RouteDrift.REACH_M) continue
            // In metres around this point of the line.
            val ax = (a.point.lon - p.lon) * kx
            val ay = (a.point.lat - p.lat) * M_PER_DEG_LAT
            val dx = (b.point.lon - p.lon) * kx - ax
            val dy = (b.point.lat - p.lat) * M_PER_DEG_LAT - ay
            val len2 = dx * dx + dy * dy
            if (len2 > RouteDrift.MAX_STEP_M * RouteDrift.MAX_STEP_M) continue
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val qx = ax + t * dx
            val qy = ay + t * dy
            val d = hypot(qx, qy)
            if (d >= best) continue
            best = d
            val across = qx * nx[i] + qy * ny[i]
            val along = qx * ny[i] - qy * nx[i]
            bestOffset = if (abs(along) <= abs(across) + RouteDrift.ABEAM_SLACK_M) across else Double.NaN
        }
        if (best <= RouteDrift.REACH_M) offset[i] = bestOffset
    }

    val moved = ArrayList<GeoPoint>(stations.size)
    var movedM = 0.0
    for (i in stations.indices) {
        var sum = 0.0
        var n = 0
        if (!offset[i].isNaN()) {
            for (k in max(0, i - RouteDrift.SMOOTH_POINTS)..min(stations.lastIndex, i + RouteDrift.SMOOTH_POINTS)) {
                if (!offset[k].isNaN()) {
                    sum += offset[k]
                    n++
                }
            }
        }
        val m = if (n > 0) weight * sum / n else 0.0
        movedM = max(movedM, abs(m))
        val p = stations[i]
        moved += GeoPoint(p.lat + m * ny[i] / M_PER_DEG_LAT, p.lon + m * nx[i] / mPerDegLon(p.lat))
    }
    if (movedM < RouteDrift.MIN_MOVE_M) return null

    // Stored without points bunched up at an inside corner, or lying on a straight between others.
    // Cutting a corner, both legs move onto the same diagonal, and some of their points would go
    // back over it: a point is kept only when it lies ahead of the last one kept, in the direction
    // the line had there.
    val clean = ArrayList<Int>()
    for (i in moved.indices) {
        if (clean.isEmpty()) {
            clean += i
            continue
        }
        val k = clean.last()
        if (distanceM(moved[k], moved[i]) < 1.0) continue
        val ahead = (moved[i].lon - moved[k].lon) * mPerDegLon(moved[k].lat) * ny[k] -
            (moved[i].lat - moved[k].lat) * M_PER_DEG_LAT * nx[k]
        if (ahead > 0 || (nx[k] == 0.0 && ny[k] == 0.0)) clean += i
    }
    // The end stays the end, even when the point before it lies within a metre.
    if (clean.last() != moved.lastIndex) {
        if (clean.size > 1) clean[clean.lastIndex] = moved.lastIndex else clean += moved.lastIndex
    }
    val kept = simplifyIndices(clean.map { moved[it] }, RouteDrift.STORE_TOLERANCE_M).map { clean[it] }
    val newLine = Polyline(kept.map { moved[it] })

    // Each point's distance along the new line: exact where it was kept, in proportion in between.
    val movedAlong = Polyline(moved).cumulative
    val toM = DoubleArray(moved.size)
    for (k in 0 until kept.size - 1) {
        val lo = kept[k]
        val hi = kept[k + 1]
        val span = movedAlong[hi] - movedAlong[lo]
        for (i in lo..hi) {
            val f = if (span > 0) (movedAlong[i] - movedAlong[lo]) / span else 0.0
            toM[i] = newLine.cumulative[k] + f * (newLine.cumulative[k + 1] - newLine.cumulative[k])
        }
    }
    return Nudge(newLine, AlongMap(alongOld, toM), movedM)
}

/** A stretch a ride took away from the line: it left at [leaveM] along it and came back at [rejoinM]. */
class Detour(val leaveM: Double, val rejoinM: Double, /** From the last fix on the line to the first one back. */ val path: List<GeoPoint>)

/**
 * The stretches where a ride left the line for another street and came back to it further on. Not a
 * GPS spike (a few fixes), not a ride that never came back, and not an errand that winds off and
 * returns to about where it left.
 */
fun detours(placed: List<Placed>): List<Detour> {
    val out = ArrayList<Detour>()
    var lastOn = -1
    var i = 0
    while (i < placed.size) {
        if (placed[i].offsetM <= RouteDrift.REACH_M) {
            lastOn = i++
            continue
        }
        var j = i
        while (j < placed.size && placed[j].offsetM > RouteDrift.REACH_M) j++
        if (lastOn >= 0 && j < placed.size && j - i >= RouteDrift.MIN_DETOUR_FIXES) {
            val leave = placed[lastOn]
            val rejoin = placed[j]
            val path = listOf(leave.point) + placed.subList(i, j).map { it.point } + rejoin.point
            val ridden = path.zipWithNext { a, b -> distanceM(a, b) }.sum()
            val straight = distanceM(leave.point, rejoin.point)
            if (rejoin.alongM > leave.alongM && straight >= RouteDrift.MIN_DETOUR_M && ridden <= RouteDrift.MAX_WINDING * straight) {
                out += Detour(leave.alongM, rejoin.alongM, path)
            }
        }
        i = j
    }
    return out
}

/** True when two rides took the same other street: leaving and rejoining in the same places, and close all the way. */
fun sameDetour(a: Detour, b: Detour): Boolean =
    abs(a.leaveM - b.leaveM) <= RouteDrift.MATCH_ENDS_M &&
        abs(a.rejoinM - b.rejoinM) <= RouteDrift.MATCH_ENDS_M &&
        follows(a.path, b.path) && follows(b.path, a.path)

/** True when nearly all of [path] lies close to [other]. */
private fun follows(path: List<GeoPoint>, other: List<GeoPoint>): Boolean {
    val line = Polyline(path)
    val target = Polyline(other)
    val n = max(2, ceil(line.lengthM / RouteDrift.MATCH_STEP_M).toInt() + 1)
    val close = (0 until n).count { k ->
        target.project(line.pointAt(line.lengthM * k / (n - 1))).offsetM <= RouteDrift.MATCH_PATH_M
    }
    return close >= RouteDrift.MATCH_SHARE * n
}

/** A line with one stretch replaced, where old distances land on it, and where the new stretch lies on it. */
class Reroute(val line: Polyline, val map: AlongMap, val fromM: Double, val toM: Double)

/** Replaces the stretch of [line] that [detour] rode elsewhere with the way it went. */
fun splice(line: Polyline, detour: Detour): Reroute {
    val a = detour.leaveM
    val b = detour.rejoinM
    val start = line.pointAt(a)
    val end = line.pointAt(b)
    val between = cleanPoints(listOf(start) + detour.path, minStepM = 3.0).toMutableList()
    if (between.size >= 2 && distanceM(between.last(), end) < 3.0) between[between.lastIndex] = end else between += end
    val stretch = simplify(between, RouteDrift.STORE_TOLERANCE_M)
    val before = line.points.filterIndexed { i, _ -> line.cumulative[i] < a }
    val after = line.points.filterIndexed { i, _ -> line.cumulative[i] > b }
    val newLine = Polyline(before + stretch + after)
    val fromM = newLine.cumulative[before.size]
    val toM = newLine.cumulative[before.size + stretch.size - 1]
    val map = AlongMap(
        doubleArrayOf(0.0, a, b, line.lengthM),
        doubleArrayOf(0.0, fromM, toM, newLine.lengthM),
        gap = a to b,
    )
    return Reroute(newLine, map, fromM, toM)
}

/** A segment while a line is relearned: where it lies now, and which stored segment it continues (null: a new one). */
data class SegmentSpan(val startM: Double, val endM: Double, val origIdx: Int?)

/**
 * Carries segments over to a changed line. Across a nudge each keeps its place and its learning.
 * Across a reroute the segments before and after it do too; those that ran over the old stretch are
 * replaced by new ones over the new stretch, since what they learned was about another road.
 */
fun remapSpans(spans: List<SegmentSpan>, map: AlongMap, newLengthM: Double): List<SegmentSpan> {
    val gap = map.gap
    val out = ArrayList<SegmentSpan>(spans.size)
    val first = if (gap == null) -1 else spans.indexOfFirst { it.endM > gap.first }
    val last = if (gap == null) -1 else spans.indexOfLast { it.startM < gap.second }
    if (first < 0 || last < first) {
        spans.forEach { out += it.copy(startM = map.map(it.startM)!!, endM = map.map(it.endM)!!) }
    } else {
        out += spans.subList(0, first)
        val from = spans[first].startM
        val to = map.map(spans[last].endM)!!
        segmentBounds(to - from).forEach { (s, e) -> out += SegmentSpan(from + s, from + e, null) }
        spans.subList(last + 1, spans.size).forEach { out += it.copy(startM = map.map(it.startM)!!, endM = map.map(it.endM)!!) }
    }
    out[0] = out[0].copy(startM = 0.0)
    out[out.lastIndex] = out.last().copy(endM = newLengthM)
    return out
}

/**
 * Folds rides into a route's line one at a time, oldest first, keeping track of where its segments
 * went and where distances along the line it started from land on the line it ends with.
 */
class LineFolder(start: Polyline, segments: List<Pair<Double, Double>>) {
    var line: Polyline = start
        private set
    var spans: List<SegmentSpan> = segments.mapIndexed { i, (s, e) -> SegmentSpan(s, e, i) }
        private set

    /** The stretch of the line, as it is now, that a ride rerouted; null when none was. */
    var rerouted: Pair<Double, Double>? = null
        private set

    private val maps = ArrayList<AlongMap>()

    val changed: Boolean get() = maps.isNotEmpty()

    /** Where [oldM] along the line this started from lies on the line now; null on a stretch no longer followed. */
    fun map(oldM: Double): Double? = maps.fold<AlongMap, Double?>(oldM) { m, step -> m?.let(step::map) }

    /**
     * Folds in one ride's [fixes]. Given [earlier], the fixes of the two rides before it, it may also
     * reroute a stretch this ride took elsewhere, when one of those took it too. Returns false for a
     * ride that was on too little of the line to say anything about it.
     */
    fun fold(fixes: List<GeoPoint>, earlier: List<List<GeoPoint>>? = null): Boolean {
        var placed = place(line, fixes)
        if (coverage(line, placed) < RouteDrift.MIN_COVERAGE) return false
        if (earlier != null) {
            val theirs = earlier.map { detours(place(line, it)) }
            val adopted = detours(placed).filter { d -> theirs.any { t -> t.any { sameDetour(d, it) } } }
            // From the end backwards, so each splice leaves the distances of the ones before it as they were.
            for (d in adopted.sortedByDescending { it.leaveM }) {
                val r = splice(line, d)
                apply(r.map, r.line)
                rerouted = rerouted?.let { (from, to) -> minOf(from, r.fromM) to maxOf(to, r.toM) } ?: (r.fromM to r.toM)
            }
            if (adopted.isNotEmpty()) placed = place(line, fixes)
        }
        nudge(line, placed)?.let { apply(it.map, it.line) }
        return true
    }

    private fun apply(map: AlongMap, next: Polyline) {
        spans = remapSpans(spans, map, next.lengthM)
        rerouted = rerouted?.let { (from, to) -> (map.map(from) ?: from) to (map.map(to) ?: to) }
        maps += map
        line = next
    }
}

private fun lowerBound(a: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < x) lo = mid + 1 else hi = mid
    }
    return lo
}
