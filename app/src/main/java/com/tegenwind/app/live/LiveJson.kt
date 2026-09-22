package com.tegenwind.app.live

import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.routes.LoadedRoute
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The contract version both sides speak; see docs/CONTRACT.md in the meewind repo. */
const val CONTRACT_VERSION = 1

/** About a metre, which is as much as anyone watching a map needs. */
private fun coord(v: Double) = "%.5f".format(Locale.ROOT, v).toDouble()

/**
 * The route being ridden, sent once per [routeVersion]: the line to draw and what riding it has
 * taught the model about each segment. A free ride has no route, and says so.
 */
fun manifestJson(routeVersion: Int, simulated: Boolean, route: LoadedRoute?): JSONObject {
    val body = JSONObject()
        .put("v", CONTRACT_VERSION)
        .put("routeVersion", routeVersion)
        .put("simulated", simulated)
    if (route == null) return body.put("route", JSONObject.NULL)

    val points = JSONArray()
    route.line.points.forEach { p ->
        points.put(JSONArray().put(coord(p.lat)).put(coord(p.lon)))
    }
    val segments = JSONArray()
    route.segments.forEach { s ->
        val learned = SegmentCorrection(s.learnedLogMean, s.learnedLogVar, s.learnedPasses)
        segments.put(
            JSONObject()
                .put("i", s.idx)
                .put("startM", s.startM)
                .put("endM", s.endM)
                .put("timeFactor", learned.timeFactor)
                .put("passes", s.learnedPasses)
        )
    }
    return body.put(
        "route",
        JSONObject()
            .put("routeId", route.route.id)
            .put("name", route.route.name)
            .put("provisional", false)
            .put("lengthM", route.line.lengthM)
            .put("points", points)
            .put("segments", segments)
    )
}

/** Where you are and what the ride looks like right now. Everything route-shaped is absent on a free ride. */
fun tickJson(routeVersion: Int, ride: LiveRide, nowMs: Long): JSONObject {
    val s = ride.snapshot
    val body = JSONObject()
        .put("v", CONTRACT_VERSION)
        .put("routeVersion", routeVersion)
        .put("tMs", nowMs)

    if (s.lastLat != null && s.lastLon != null) {
        body.put(
            "pos",
            JSONObject()
                .put("lat", coord(s.lastLat))
                .put("lon", coord(s.lastLon))
                .putOpt("headingDeg", s.headingDeg)
                .putOpt("speedKmh", s.speedKmh)
        )
    }
    body.put(
        "ride",
        JSONObject()
            .put("started", ride.started)
            .put("paused", s.paused)
            .put("finished", ride.arrivedAtMs != null)
            .put("distanceM", s.distanceM)
    )

    ride.route?.let { r ->
        body.put(
            "progress",
            JSONObject()
                .put("progressM", r.progress.progressM)
                .put("lengthM", r.progress.lengthM)
                .put("onRouteYet", r.progress.onRouteYet)
                .put("offRoute", r.progress.offRoute)
        )
    }
    ride.eta?.let { live ->
        body.put(
            "eta",
            JSONObject()
                .put("arrivalMs", live.eta.arrivalMs)
                .put("bandS", live.eta.bandS)
                .put("windCostS", live.eta.windCostS)
        )
    }
    // On a route the wind comes with the ETA; on a free ride your heading is enough to know it.
    (ride.eta?.wind ?: ride.freeWind)?.let { w ->
        body.put(
            "wind",
            JSONObject()
                .put("speed10Mps", w.speed10Mps)
                .put("fromDeg", w.fromDeg)
                .put("headwindMps", w.headwindMps)
                .put("relativeDeg", w.relativeDeg)
                .put("label", w.label)
        )
    }
    return body
}

/** Marks the last tick of a ride, so the viewer can say "arrived" instead of going quiet. */
fun JSONObject.markFinished(): JSONObject = apply {
    optJSONObject("ride")?.put("finished", true)
}
