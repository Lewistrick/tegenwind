package com.tegenwind.app.ui

import com.tegenwind.app.routes.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/*
 * The arithmetic of a street map from OpenStreetMap's tiles, kept apart from drawing so it can be
 * tested. The world is a square in Web Mercator, 0 to 1 from west to east and from north to south.
 * At zoom level z it is cut into 2^z by 2^z tiles of 256 pixels; a map's zoom may lie between two
 * levels, and the tiles of the nearest level are then drawn a little larger or smaller.
 */

/** A place on the world square: [x] from west (0) to east (1), [y] from north (0) to south (1). */
data class WorldXY(val x: Double, val y: Double)

/** Beyond this latitude Web Mercator stretches to infinity; OpenStreetMap's tiles stop here too. */
private const val MAX_LAT = 85.05112878

fun worldOf(p: GeoPoint): WorldXY {
    val lat = Math.toRadians(p.lat.coerceIn(-MAX_LAT, MAX_LAT))
    return WorldXY((p.lon + 180.0) / 360.0, (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2)
}

fun geoOf(w: WorldXY): GeoPoint =
    GeoPoint(Math.toDegrees(atan(sinh(PI * (1 - 2 * w.y)))), w.x * 360.0 - 180.0)

/** One tile of OpenStreetMap: zoom level [z], column [x] from the west and row [y] from the north. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    val url: String get() = "https://tile.openstreetmap.org/$z/$x/$y.png"
}

object OsmZoom {
    /** OpenStreetMap's own tiles go no deeper than this. */
    const val MAX_TILE = 19

    /** Zooming in further only enlarges the deepest tiles: about a house across the whole screen. */
    const val MAX = 20.0

    /** About the whole of the Netherlands on a phone. */
    const val MIN = 6.0
}

/** The tile level drawn at [zoom]: the nearest one, so tiles are drawn between 0.71 and 1.41 times their size. */
fun tileZoom(zoom: Double): Int = floor(zoom + 0.5).toInt().coerceIn(0, OsmZoom.MAX_TILE)

/** How wide the whole world is at [zoom], in pixels, with tiles [tilePx] wide at their own level. */
fun worldPx(zoom: Double, tilePx: Float): Double = tilePx * 2.0.pow(zoom)

/**
 * The tiles that cover a view of [widthPx] by [heightPx] around [center] at [zoom], the middle ones
 * first, so those load first.
 */
fun visibleTiles(center: WorldXY, zoom: Double, widthPx: Float, heightPx: Float, tilePx: Float): List<TileKey> {
    if (widthPx <= 0f || heightPx <= 0f) return emptyList()
    val z = tileZoom(zoom)
    val n = 1 shl z
    val world = worldPx(zoom, tilePx)
    val left = center.x - widthPx / 2.0 / world
    val right = center.x + widthPx / 2.0 / world
    val top = center.y - heightPx / 2.0 / world
    val bottom = center.y + heightPx / 2.0 / world
    val x0 = floor(left * n).toInt().coerceIn(0, n - 1)
    val x1 = floor(right * n).toInt().coerceIn(0, n - 1)
    val y0 = floor(top * n).toInt().coerceIn(0, n - 1)
    val y1 = floor(bottom * n).toInt().coerceIn(0, n - 1)
    val cx = center.x * n - 0.5
    val cy = center.y * n - 0.5
    val out = ArrayList<TileKey>((x1 - x0 + 1) * (y1 - y0 + 1))
    for (x in x0..x1) for (y in y0..y1) out += TileKey(z, x, y)
    return out.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
}

/** The view that shows all of [points]: its middle and zoom, leaving [padPx] free along every edge. */
fun fitView(points: List<WorldXY>, widthPx: Float, heightPx: Float, tilePx: Float, padPx: Float): Pair<WorldXY, Double> {
    if (points.isEmpty()) return WorldXY(0.5, 0.5) to OsmZoom.MIN
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val center = WorldXY((minX + maxX) / 2, (minY + maxY) / 2)
    val w = (widthPx - 2 * padPx).coerceAtLeast(1f)
    val h = (heightPx - 2 * padPx).coerceAtLeast(1f)
    // A single spot, or a ride that never moved: close enough in to see the streets.
    val spanX = (maxX - minX).coerceAtLeast(1e-9)
    val spanY = (maxY - minY).coerceAtLeast(1e-9)
    val zoom = minOf(log2(w / (spanX * tilePx)), log2(h / (spanY * tilePx)))
    return center to zoom.coerceIn(OsmZoom.MIN, 17.0)
}

/**
 * OpenStreetMap's light map turned dark, to sit in the app's dark screens: every colour inverted,
 * then turned half way round the colour wheel so water stays blue and parks stay green, then a
 * little dimmed. As a 4 × 5 colour matrix, rows red, green, blue and alpha, the fifth column an
 * offset on the 0–255 scale.
 */
fun darkTileMatrix(): FloatArray {
    // Inverted, then rotated by 180° around the grey axis (luminance weights 0.213, 0.715, 0.072).
    val rotate = arrayOf(
        doubleArrayOf(-0.574, 1.430, 0.144),
        doubleArrayOf(0.426, 0.430, 0.144),
        doubleArrayOf(0.426, 1.430, -0.856),
    )
    val out = FloatArray(20)
    for (row in 0..2) {
        // Inverting first: each channel c becomes 255 − c, so the matrix flips sign and gains an offset.
        var offset = 0.0
        for (col in 0..2) {
            out[row * 5 + col] = (-rotate[row][col] * DIM).toFloat()
            offset += rotate[row][col] * 255
        }
        out[row * 5 + 4] = (offset * DIM + LIFT).toFloat()
    }
    out[3 * 5 + 3] = 1f
    return out
}

/** How much of the inverted map's brightness is kept: white labels and black land would glare. */
private const val DIM = 0.82

/** Lifts the darkest parts a little, so the darkest land isn't quite black. */
private const val LIFT = 14.0
