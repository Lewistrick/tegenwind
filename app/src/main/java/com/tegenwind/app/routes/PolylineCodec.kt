package com.tegenwind.app.routes

import kotlin.math.roundToLong

/*
 * A line as one short string: Google's encoded polyline format at six decimals (about 0.1 m), the
 * same as OSRM's "polyline6". Each coordinate is stored as the difference from the one before, in
 * printable characters from '?' to '~', so a line of a thousand points takes a few kilobytes and
 * never contains a line break.
 */

private const val SCALE = 1e6

fun encodePolyline(points: List<GeoPoint>): String {
    val out = StringBuilder(points.size * 8)
    var lastLat = 0L
    var lastLon = 0L
    for (p in points) {
        val lat = (p.lat * SCALE).roundToLong()
        val lon = (p.lon * SCALE).roundToLong()
        encodeValue(lat - lastLat, out)
        encodeValue(lon - lastLon, out)
        lastLat = lat
        lastLon = lon
    }
    return out.toString()
}

/** The points of [text] made by [encodePolyline]. Throws [IllegalArgumentException] on text it didn't make. */
fun decodePolyline(text: String): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    var i = 0
    var lat = 0L
    var lon = 0L
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(i < text.length) { "The line ends in the middle of a number" }
            val b = text[i++].code - 63
            require(b in 0..63) { "Not a character of an encoded line" }
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
            require(shift < 64) { "A number too long for an encoded line" }
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (i < text.length) {
        lat += next()
        lon += next()
        out += GeoPoint(lat / SCALE, lon / SCALE)
    }
    return out
}

private fun encodeValue(value: Long, out: StringBuilder) {
    var v = if (value < 0) (value shl 1).inv() else value shl 1
    while (v >= 0x20) {
        out.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
        v = v ushr 5
    }
    out.append((v + 63).toInt().toChar())
}
