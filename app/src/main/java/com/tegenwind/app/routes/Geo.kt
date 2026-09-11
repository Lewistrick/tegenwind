package com.tegenwind.app.routes

import com.tegenwind.app.ride.haversineM
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class GeoPoint(val lat: Double, val lon: Double)

fun distanceM(a: GeoPoint, b: GeoPoint): Double = haversineM(a.lat, a.lon, b.lat, b.lon)

/** Initial compass bearing from [a] to [b], degrees clockwise from north. */
fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Where a position lands on a route: distance along it, and how far beside it. */
data class Projection(val alongM: Double, val offsetM: Double)

/** A route line with distances measured along it. */
class Polyline(val points: List<GeoPoint>) {
    init {
        require(points.size >= 2) { "A route needs at least 2 points" }
    }

    /** Distance from the start to each point. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + distanceM(points[i - 1], points[i])
    }

    val lengthM: Double get() = cumulative.last()

    fun pointAt(alongM: Double): GeoPoint {
        val d = alongM.coerceIn(0.0, lengthM)
        var i = cumulative.binarySearch(d).let { if (it >= 0) it else -it - 2 }
        i = i.coerceIn(0, points.size - 2)
        val span = cumulative[i + 1] - cumulative[i]
        val f = if (span > 0) (d - cumulative[i]) / span else 0.0
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
    }

    fun reversed() = Polyline(points.reversed())

    /**
     * Nearest spot on the line to [p], only considering the stretch between [fromM] and [toM].
     * Restricting the stretch stops a position jumping to another part of a route that passes close by.
     */
    fun project(p: GeoPoint, fromM: Double = 0.0, toM: Double = lengthM): Projection {
        var best = Projection(0.0, Double.MAX_VALUE)
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM || cumulative[i] > toM) continue
            val a = points[i]
            val b = points[i + 1]
            // Local flat approximation around a: fine for segments of a few km.
            val kx = 111_320.0 * cos(Math.toRadians(a.lat))
            val ky = 110_574.0
            val bx = (b.lon - a.lon) * kx
            val by = (b.lat - a.lat) * ky
            val px = (p.lon - a.lon) * kx
            val py = (p.lat - a.lat) * ky
            val len2 = bx * bx + by * by
            // Only the part of this segment inside [fromM, toM] counts.
            val segLen = cumulative[i + 1] - cumulative[i]
            val tMin = if (segLen > 0) ((fromM - cumulative[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
            val tMax = if (segLen > 0) ((toM - cumulative[i]) / segLen).coerceIn(0.0, 1.0) else 1.0
            val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(tMin, tMax) else 0.0
            val offset = hypot(px - t * bx, py - t * by)
            if (offset < best.offsetM) best = Projection(cumulative[i] + t * segLen, offset)
        }
        return best
    }
}

/** Drops repeated points and points closer than [minStepM] to the previous one (GPX files often contain both). */
fun cleanPoints(points: List<GeoPoint>, minStepM: Double = 1.0): List<GeoPoint> {
    val out = ArrayList<GeoPoint>(points.size)
    for (p in points) if (out.isEmpty() || distanceM(out.last(), p) >= minStepM) out += p
    return out
}

/** Merges positions along a route that lie within [withinM] of the previous one; returns the first of each group. */
fun clusterAlong(alongM: List<Double>, withinM: Double): List<Double> {
    val out = ArrayList<Double>()
    var last = Double.NEGATIVE_INFINITY
    for (d in alongM.sorted()) {
        if (d - last > withinM) out += d
        last = d
    }
    return out
}

/** Equal-length stretches of about [targetM], as (start, end) distances along the route. */
fun segmentBounds(lengthM: Double, targetM: Double = 250.0): List<Pair<Double, Double>> {
    val n = maxOf(1, Math.round(lengthM / targetM).toInt())
    return (0 until n).map { i -> lengthM * i / n to lengthM * (i + 1) / n }
}

/** "woon-werk" -> "werk-woon"; anything without a dash gets " (reverse)". */
fun reverseName(name: String): String {
    val parts = name.split(Regex("\\s*(-|→|>)\\s*"))
    return if (parts.size == 2 && parts.all { it.isNotBlank() }) {
        val sep = Regex("\\s*(-|→|>)\\s*").find(name)!!.value
        parts[1] + sep + parts[0]
    } else "$name (reverse)"
}
