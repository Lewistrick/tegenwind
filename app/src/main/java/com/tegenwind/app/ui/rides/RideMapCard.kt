package com.tegenwind.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.ride.RideTracker
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.simplify
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.MapMark
import com.tegenwind.app.ui.RouteMap
import com.tegenwind.app.ui.mapAspect
import com.tegenwind.app.ui.routes.FULL_LEARNED
import com.tegenwind.app.ui.theme.scaleColor
import kotlin.math.roundToInt

/** Everything a finished ride's map draws, worked out once from what is stored. */
class RideMapData(
    /** The route the ride was on, and its segments; null for a free ride. */
    val line: Polyline?,
    val segments: List<RouteSegmentEntity>,
    /** This ride's pass over each segment, by segment number. */
    val passes: Map<Int, SegmentTraversalEntity>,
    /** Where the ride went, thinned for drawing. */
    val track: List<GeoPoint>,
    /** Where it stood still for a while. */
    val stops: List<GeoPoint>,
)

/** Standing shorter than this isn't worth a dot on the map. */
private const val STOP_WORTH_MS = 10_000L

/** The share of the expected time a pass gained (positive) or lost: what a stretch is coloured by. */
fun gained(pass: SegmentTraversalEntity): Double? =
    pass.expectedMovingMs?.takeIf { it > 0 }?.let { (it - pass.movingMs).toDouble() / it }

/** Where a ride stood still for [STOP_WORTH_MS] or longer: the first fix of each such stand. */
fun standingSpots(points: List<TrackPointEntity>): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    var since: TrackPointEntity? = null
    var counted = false
    for (p in points) {
        if ((p.speedMps ?: 0.0) >= RideTracker.STOP_SPEED_MPS) {
            since = null
            counted = false
            continue
        }
        val from = since ?: p.also { since = it }
        if (!counted && p.timeMs - from.timeMs >= STOP_WORTH_MS) {
            out += GeoPoint(from.lat, from.lon)
            counted = true
        }
    }
    return out
}

fun rideMapData(
    line: Polyline?,
    segments: List<RouteSegmentEntity>,
    passes: List<SegmentTraversalEntity>,
    points: List<TrackPointEntity>,
): RideMapData {
    val good = points.filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }
    return RideMapData(
        line = line,
        segments = segments,
        passes = passes.associateBy { it.segIdx },
        track = simplify(good.map { GeoPoint(it.lat, it.lon) }, toleranceM = 3.0),
        stops = standingSpots(good),
    )
}

/**
 * Where the ride went. On a route, each stretch is coloured by the time gained or lost against what
 * the ETA assumed for it that day, and tapping one says by how much.
 */
@Composable
fun RideMapCard(data: RideMapData) {
    if (data.track.size < 2 && data.line == null) return
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val onRoute = data.line != null && data.passes.isNotEmpty()
    ChartCard(
        "Map",
        when {
            onRoute -> "Green: quicker than the ETA assumed there, red: slower. Dots: where you stood still."
            else -> "Where you rode. Dots: where you stood still."
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteMap(
                line = data.line,
                modifier = Modifier.fillMaxWidth().aspectRatio(mapAspect(data.line?.points.orEmpty() + data.track)),
                spans = data.segments.map { it.startM to it.endM },
                colors = data.segments.map { s -> data.passes[s.idx]?.let(::gained)?.let { scaleColor(it / FULL_LEARNED) } },
                track = data.track,
                marks = data.stops.map { MapMark(it) },
                selected = selected,
                onSelect = if (onRoute) ({ selected = it }) else null,
            )
            if (onRoute) {
                val s = selected?.let(data.segments::getOrNull)
                Text(
                    if (s == null) "Tap a stretch to see how it went." else passSummary(s, data.passes[s.idx]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                )
            }
        }
    }
}

/** One stretch of a ride in words: what the ETA assumed, what it took, and the difference. */
fun passSummary(s: RouteSegmentEntity, pass: SegmentTraversalEntity?): String {
    val where = "%.2f–%.2f km".format(s.startM / 1000, s.endM / 1000)
    val expected = pass?.expectedMovingMs ?: return "$where · not timed on this ride"
    val parts = arrayListOf(
        where,
        "ETA assumed ${formatElapsed(expected)}",
        "took ${formatElapsed(pass.movingMs)}",
        "%s%d s".format(if (pass.movingMs > expected) "+" else "−", (kotlin.math.abs(pass.movingMs - expected) / 1000.0).roundToInt()),
    )
    val stoodS = (pass.exitMs - pass.enterMs - pass.movingMs) / 1000.0
    if (stoodS >= 2) parts += "stood %.0f s".format(stoodS)
    return parts.joinToString(" · ")
}
