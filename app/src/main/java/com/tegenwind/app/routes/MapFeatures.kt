package com.tegenwind.app.routes

import java.util.Locale

/*
 * What the live map shows around the route besides the road itself: the side roads that join it,
 * the bridges it crosses and its traffic lights, from OpenStreetMap. Kept as places (not distances
 * along the line), so they stay where they are when the line moves toward your rides.
 */

/** A road from OpenStreetMap near the route: its course, and whether it is a bridge. */
data class OsmWay(val points: List<GeoPoint>, val bridge: Boolean)

enum class FeatureKind(val key: String) {
    /** A short stub of a road that joins or crosses the route. */
    SIDE("side"),

    /** The stretch of the route that runs over a bridge: its two ends. */
    BRIDGE("bridge"),

    /** A traffic light: one point. */
    LIGHT("light");

    companion object {
        fun of(key: String): FeatureKind? = entries.firstOrNull { it.key == key }
    }
}

data class MapFeature(val kind: FeatureKind, val points: List<GeoPoint>)

object MapFeatureRules {
    /** How much of a side road is drawn, from where it meets the route. */
    const val STUB_M = 40.0

    /** Closer than this to the line, a road is the route's own road there. */
    const val ALONG_M = 15.0

    /** A road with at least this share of it close to the line runs along the route: no side road. */
    const val ALONG_SHARE = 0.5

    /** A road that never comes this close doesn't meet the route. */
    const val MEETS_M = 20.0

    /** A stub ending this close to the line runs along it rather than away from it, and isn't drawn. */
    const val AWAY_M = 10.0

    /** A bridge with at least this share of it close to the line carries the route. */
    const val BRIDGE_SHARE = 0.6

    /** Shorter bridges than this aren't worth drawing. */
    const val MIN_BRIDGE_M = 5.0

    /** A traffic light within this of the line is on the route. */
    const val LIGHT_M = 25.0

    /** Lights closer together than this (one per approach at a junction) are drawn as one. */
    const val LIGHT_CLUSTER_M = 20.0

    /** Roads are judged at points this far apart. */
    const val STEP_M = 5.0
}

/**
 * The side roads among [ways]: each one that meets [line] without running along it, as stubs of up
 * to [MapFeatureRules.STUB_M] from where it comes closest, in both directions where it crosses.
 */
fun sideRoadStubs(line: Polyline, ways: List<OsmWay>): List<List<GeoPoint>> = ways.flatMap { way ->
    if (way.points.size < 2) return@flatMap emptyList()
    val samples = densify(way.points, MapFeatureRules.STEP_M)
    val offsets = samples.map { line.project(it).offsetM }
    val nearest = offsets.indices.minBy { offsets[it] }
    if (offsets[nearest] > MapFeatureRules.MEETS_M) return@flatMap emptyList()
    if (offsets.count { it <= MapFeatureRules.ALONG_M } >= MapFeatureRules.ALONG_SHARE * offsets.size) return@flatMap emptyList()
    listOf(stub(samples, nearest, +1), stub(samples, nearest, -1)).filter { s ->
        s.size >= 2 && line.project(s.last()).offsetM > MapFeatureRules.AWAY_M
    }
}

/** From [samples] at [from], [step] by step, until [MapFeatureRules.STUB_M] has been walked. */
private fun stub(samples: List<GeoPoint>, from: Int, step: Int): List<GeoPoint> {
    val out = arrayListOf(samples[from])
    var walked = 0.0
    var i = from + step
    while (i in samples.indices) {
        val d = distanceM(out.last(), samples[i])
        if (walked + d > MapFeatureRules.STUB_M) {
            val f = (MapFeatureRules.STUB_M - walked) / d
            val a = out.last()
            val b = samples[i]
            out += GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
            break
        }
        walked += d
        out += samples[i]
        i += step
    }
    return out
}

/**
 * The stretches of [line] that run over a bridge among [ways]: for each bridge mostly close to the
 * line, the points on the line where it starts and ends. A bridge the route passes under crosses it
 * and doesn't count.
 */
fun bridgeStretches(line: Polyline, ways: List<OsmWay>): List<Pair<GeoPoint, GeoPoint>> = ways.filter { it.bridge && it.points.size >= 2 }.mapNotNull { way ->
    val samples = densify(way.points, MapFeatureRules.STEP_M).map { line.project(it) }
    val close = samples.filter { it.offsetM <= MapFeatureRules.ALONG_M }
    if (close.size < MapFeatureRules.BRIDGE_SHARE * samples.size) return@mapNotNull null
    val from = close.minOf { it.alongM }
    val to = close.maxOf { it.alongM }
    if (to - from < MapFeatureRules.MIN_BRIDGE_M) null else line.pointAt(from) to line.pointAt(to)
}

/** The traffic lights among [signals] that are on [line], one per junction: lights close together become their middle. */
fun routeLights(line: Polyline, signals: List<GeoPoint>): List<GeoPoint> {
    val near = signals.filter { line.project(it).offsetM <= MapFeatureRules.LIGHT_M }
    val groups = ArrayList<MutableList<GeoPoint>>()
    for (s in near) {
        val g = groups.firstOrNull { grp -> grp.any { distanceM(it, s) <= MapFeatureRules.LIGHT_CLUSTER_M } }
        if (g != null) g += s else groups += mutableListOf(s)
    }
    return groups.map { g -> GeoPoint(g.map { it.lat }.average(), g.map { it.lon }.average()) }
}

/** Everything the live map shows around [line], from the roads and lights found near it. */
fun mapFeatures(line: Polyline, ways: List<OsmWay>, signals: List<GeoPoint>): List<MapFeature> =
    sideRoadStubs(line, ways).map { MapFeature(FeatureKind.SIDE, it) } +
        bridgeStretches(line, ways).map { (a, b) -> MapFeature(FeatureKind.BRIDGE, listOf(a, b)) } +
        routeLights(line, signals).map { MapFeature(FeatureKind.LIGHT, listOf(it)) }

/** Points as stored: "lat,lon;lat,lon". */
fun encodePoints(points: List<GeoPoint>): String =
    points.joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lat, it.lon) }

fun decodePoints(text: String): List<GeoPoint> =
    text.split(';').filter { it.isNotBlank() }.map { p -> p.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
