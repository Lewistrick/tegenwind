package com.tegenwind.app.ui

import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Fits a set of positions into a box on screen: north up, the same scale both ways, centred, with
 * [padPx] kept free all round. Flat around the middle of the positions, which is fine at the size
 * of a ride.
 */
class MapProjection(points: List<GeoPoint>, private val widthPx: Float, private val heightPx: Float, padPx: Float) {
    private val lat0 = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
    private val lon0 = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_574.0

    /** Pixels per metre. */
    val scale: Double

    init {
        val wide = (points.maxOf { it.lon } - points.minOf { it.lon }) * kx
        val high = (points.maxOf { it.lat } - points.minOf { it.lat }) * ky
        val roomW = (widthPx - 2 * padPx).coerceAtLeast(1f)
        val roomH = (heightPx - 2 * padPx).coerceAtLeast(1f)
        // A single spot, or a dead-straight line, still gets a scale.
        scale = minOf(roomW / wide.coerceAtLeast(1.0), roomH / high.coerceAtLeast(1.0))
    }

    fun x(p: GeoPoint): Float = (widthPx / 2 + (p.lon - lon0) * kx * scale).toFloat()

    /** North is up, so latitude runs against the screen's y. */
    fun y(p: GeoPoint): Float = (heightPx / 2 - (p.lat - lat0) * ky * scale).toFloat()
}

/**
 * The shape of box that suits [points], as width over height, so a map is as tall as its route
 * needs and no taller. Kept between [flattest] and [tallest]: a long thin route still gets a card
 * you can tap on, and a square one doesn't fill the screen.
 */
fun mapAspect(points: List<GeoPoint>, tallest: Float = 1.3f, flattest: Float = 2.8f): Float {
    if (points.size < 2) return flattest
    val lat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
    val wide = (points.maxOf { it.lon } - points.minOf { it.lon }) * 111_320.0 * cos(Math.toRadians(lat))
    val high = (points.maxOf { it.lat } - points.minOf { it.lat }) * 110_574.0
    return (wide / high.coerceAtLeast(1.0)).toFloat().coerceIn(tallest, flattest)
}

/** The points of [line] between [startM] and [endM] along it, ends included. */
fun stretchOf(line: Polyline, startM: Double, endM: Double): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    out += line.pointAt(startM)
    for (i in line.points.indices) if (line.cumulative[i] > startM && line.cumulative[i] < endM) out += line.points[i]
    out += line.pointAt(endM)
    return out
}

/**
 * The stretch of [line] nearest to a tap at ([tapX], [tapY]), as an index into [spans]; null when
 * the tap is further than [withinPx] from all of them.
 */
fun nearestStretch(
    line: Polyline,
    spans: List<Pair<Double, Double>>,
    projection: MapProjection,
    tapX: Float,
    tapY: Float,
    withinPx: Float,
): Int? {
    var best: Int? = null
    var bestD = withinPx
    spans.forEachIndexed { i, (startM, endM) ->
        val pts = stretchOf(line, startM, endM)
        for (k in 0 until pts.size - 1) {
            val ax = projection.x(pts[k])
            val ay = projection.y(pts[k])
            val dx = projection.x(pts[k + 1]) - ax
            val dy = projection.y(pts[k + 1]) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((tapX - ax) * dx + (tapY - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
            val d = hypot(tapX - ax - t * dx, tapY - ay - t * dy)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
    }
    return best
}
