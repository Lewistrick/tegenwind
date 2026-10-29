package com.tegenwind.app.ui.rides

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.AppContainer
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.RiderLearned
import com.tegenwind.app.ride.RideTracker
import com.tegenwind.app.routes.FeatureKind
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.routes.RideLines
import com.tegenwind.app.routes.sameLine
import com.tegenwind.app.ui.MapProjector
import com.tegenwind.app.ui.OsmMap
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.WorldXY
import com.tegenwind.app.ui.routes.FULL_LEARNED
import com.tegenwind.app.ui.routes.RouteOverlay
import com.tegenwind.app.ui.routes.overlayColor
import com.tegenwind.app.ui.routes.segmentSummary
import com.tegenwind.app.ui.theme.scaleColor
import com.tegenwind.app.ui.worldOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a ride's own track is coloured by on its street map. */
enum class TrackColoring(val label: String, val overlay: RouteOverlay? = null) {
    PREDICTED("Against predicted"),
    SPEED("Speed"),
    PACE("Learned pace", RouteOverlay.PACE),
    EXPOSURE("Wind exposure", RouteOverlay.EXPOSURE),
    GRADE("Slope", RouteOverlay.GRADE),
    STOPS("Standing still", RouteOverlay.STOPS);

    /** What the colours mean, under the picker. */
    val explains: String
        get() = when (this) {
            PREDICTED -> "Green: quicker than predicted there, red: slower. Dots: where you stood still."
            SPEED -> "Green: faster than your median speed on this ride, red: slower, fully from a quarter. " +
                "Dots: where you stood still."
            else -> overlay!!.explains
        }
}

/** Everything the street map of one ride draws, worked out once from what is stored. */
private class StreetMapData(
    /** Where the ride went: its accurate fixes, in order. */
    val track: List<GeoPoint>,
    val trackWorld: List<WorldXY>,
    /** The speed at each fix (m/s), smoothed over a few seconds; null where a fix has none. */
    val speeds: List<Double?>,
    /** How far into the ride each fix was, metres. */
    val alongM: DoubleArray,
    val stops: List<GeoPoint>,
    /** The route the ride was on, as it is now; null for a free ride. */
    val route: LoadedRoute?,
    /** This ride's pass over each segment, by segment number. */
    val passes: Map<Int, SegmentTraversalEntity>,
    /** For each fix, the segment it was ridden on: an index into the route's segments; null off the route. */
    val segAt: List<Int?>,
    /** The route's line before and after this ride, when kept. */
    val lines: RideLines?,
    /** The route as it is now is the line this ride left: what the next ride gets. */
    val afterIsNow: Boolean,
    /** Finished a few minutes ago: the line may still be learning from it. */
    val recent: Boolean,
) {
    /** The route drawn as a band: the planned line, or the route as it is now when that's all there is. */
    val bandWorld: List<WorldXY>? = (lines?.planned ?: route?.line)?.points?.map(::worldOf)

    /** The route after this ride, when it moved. */
    val afterWorld: List<WorldXY>? = lines?.takeUnless { it.unchanged }?.after?.points?.map(::worldOf)

    /** Everything the map opens on. */
    val all: List<WorldXY> = trackWorld + bandWorld.orEmpty() + afterWorld.orEmpty()
}

private suspend fun loadStreetMap(container: AppContainer, rideId: Long): StreetMapData? {
    val dao = container.db.rides()
    val ride = dao.ride(rideId) ?: return null
    val points = dao.points(rideId)
    val passes = dao.traversals(rideId)
    // The route the passes were ridden on, or failing that the one the ride is filed under.
    val route = (passes.firstOrNull()?.routeId ?: ride.routeId)?.let { container.routes.load(it) }
    val lines = if (route == null) null else withContext(Dispatchers.IO) { container.rideLines.load(rideId) }
    return withContext(Dispatchers.Default) {
        val good = points.filter { (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }
        val track = good.map { GeoPoint(it.lat, it.lon) }
        StreetMapData(
            track = track,
            trackWorld = track.map(::worldOf),
            speeds = smoothSpeeds(good.map { it.speedMps }),
            alongM = distancesAlong(track),
            stops = standingSpots(good),
            route = route,
            passes = passes.filter { it.routeId == route?.route?.id }.associateBy { it.segIdx },
            segAt = if (route == null) track.map { null } else segmentsAlong(track, route.line, route.segments.map { it.startM to it.endM }),
            lines = lines,
            afterIsNow = lines != null && route != null && sameLine(lines.after, route.line),
            recent = System.currentTimeMillis() - (ride.endedAtMs ?: ride.startedAtMs) < RECENT_MS,
        )
    }
}

/** Within this of finishing, the route may still be learning from the ride: its lines are looked for again. */
private const val RECENT_MS = 5 * 60_000L

/** Opens a ride's street map full screen, over the ride's page; back closes it. */
@Composable
fun RideStreetMapDialog(rideId: Long, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RideStreetMap(rideId, onClose)
    }
}

/**
 * A ride on OpenStreetMap: the route as it was planned for the ride, where the ride went (coloured
 * by what the picker says) and the route as the line learned it from this ride, which is what the
 * next ride gets. Each can be switched off, to see the others.
 */
@Composable
fun RideStreetMap(rideId: Long, onClose: () -> Unit) {
    val container = LocalContext.current.appContainer
    val learned by container.riderModel.learned.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<StreetMapData?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var coloring by rememberSaveable { mutableStateOf(TrackColoring.PREDICTED) }
    var showPlanned by rememberSaveable { mutableStateOf(true) }
    var showRidden by rememberSaveable { mutableStateOf(true) }
    var showAfter by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var fitCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(rideId) {
        data = loadStreetMap(container, rideId)
        loaded = true
        // Just finished: the line learns from the ride a moment after it ends. Look again until it has.
        repeat(LOOK_AGAIN_TIMES) {
            val d = data ?: return@LaunchedEffect
            if (d.lines != null || d.route == null || !d.recent) return@LaunchedEffect
            delay(LOOK_AGAIN_MS)
            if (withContext(Dispatchers.IO) { container.rideLines.load(rideId) } != null) {
                data = loadStreetMap(container, rideId)
                selected = null
            }
        }
    }

    val d = data
    // On a free ride only speed says anything.
    val shown = if (d?.route == null) TrackColoring.SPEED else coloring
    val grey = MaterialTheme.colorScheme.onSurface
    val colors = remember(d, shown, learned) { if (d == null) emptyList() else trackColors(d, shown, learned, grey) }
    val runs = remember(colors) { runsOf(colors) }
    val marks = remember(d, shown, learned) { if (d == null) emptyList() else marksFor(d, shown, learned) }

    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val amber = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background
    val card = MaterialTheme.colorScheme.surfaceContainer

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (d != null && d.all.size >= 2) {
                OsmMap(
                    fitTo = d.all,
                    modifier = Modifier.fillMaxSize(),
                    fitKey = fitCount,
                    // Clear of the status bar and the buttons over the map.
                    fitTopPad = 96.dp,
                    onTap = { at, projector -> selected = if (showRidden) nearestFix(d, projector, at, TAP_REACH_PX) else null },
                ) { p ->
                    val band = d.bandWorld
                    if (showPlanned && band != null) {
                        drawPath(pathOf(p, band), ink.copy(alpha = BAND_ALPHA), style = round(BAND.toPx()))
                        drawCircle(muted, END.toPx(), p.at(band.first()))
                        drawCircle(muted, END.toPx(), p.at(band.last()))
                    }
                    val after = d.afterWorld
                    if (showAfter && after != null) {
                        drawPath(
                            pathOf(p, after),
                            amber,
                            style = Stroke(
                                AFTER.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), GAP.toPx())),
                            ),
                        )
                    }
                    if (showRidden && d.trackWorld.size >= 2) {
                        // A dark edge first, so the colours stand out from the streets and the route under them.
                        drawPath(pathOf(p, d.trackWorld), dark.copy(alpha = 0.85f), style = round((TRACK + 2.dp).toPx()))
                        for (run in runs) {
                            drawPath(pathOf(p, d.trackWorld, run.from, run.to), run.key ?: ink.copy(alpha = PLAIN_ALPHA), style = round(TRACK.toPx()))
                        }
                    }
                    for (m in marks) {
                        drawCircle(dark, (MARK + 1.5.dp).toPx(), p.at(m.first))
                        drawCircle(if (m.second) amber else muted, MARK.toPx(), p.at(m.first))
                    }
                    selected?.let { d.trackWorld.getOrNull(it) }?.let { w ->
                        drawCircle(dark, (RING + 2.dp).toPx(), p.at(w), style = Stroke(4.dp.toPx()))
                        drawCircle(ink, RING.toPx(), p.at(w), style = Stroke(2.dp.toPx()))
                    }
                }
            } else {
                Text(
                    if (loaded) "Nothing to show: this ride has no track." else "Loading…",
                    Modifier.align(Alignment.Center),
                    color = muted,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MapButton("‹ Ride", onClose)
                if (d != null) MapButton("Whole ride") { fitCount++ }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(card)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (d?.route != null) {
                Picker(
                    coloring,
                    TrackColoring.entries.map { it to it.label },
                    onSelect = { coloring = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(shown.explains, style = MaterialTheme.typography.bodySmall, color = muted, minLines = 2)
            if (d != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modifier = Modifier.weight(1f)
                    val twoLines = d.afterWorld != null
                    LayerToggle(if (twoLines) "Planned" else "Route", showPlanned, modifier, { showPlanned = !showPlanned }) {
                        drawLine(ink.copy(alpha = BAND_ALPHA), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                    }
                    LayerToggle("Ridden", showRidden, modifier, { showRidden = !showRidden }) {
                        drawLine(scaleColor(1.0), Offset(0f, size.height / 2), Offset(size.width / 2, size.height / 2), TRACK.toPx())
                        drawLine(scaleColor(-1.0), Offset(size.width / 2, size.height / 2), Offset(size.width, size.height / 2), TRACK.toPx())
                    }
                    if (twoLines) {
                        LayerToggle(if (d.afterIsNow) "Next ride" else "After ride", showAfter, modifier, { showAfter = !showAfter }) {
                            drawLine(
                                amber, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), AFTER.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx() / 2, GAP.toPx() / 2)),
                            )
                        }
                    }
                }
            }
            Text(
                infoText(d, loaded, shown, selected, learned),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected != null) ink else muted,
                minLines = 3,
            )
        }
    }
}

/** Taps further than this from the track select nothing. */
private const val TAP_REACH_PX = 72f

private const val LOOK_AGAIN_TIMES = 30
private const val LOOK_AGAIN_MS = 2_000L

private val BAND = 9.dp
private const val BAND_ALPHA = 0.3f
private val AFTER = 2.5.dp
private val DASH = 8.dp
private val GAP = 5.dp
private val TRACK = 4.dp
private const val PLAIN_ALPHA = 0.7f
private val END = 3.5.dp
private val MARK = 3.5.dp
private val RING = 8.dp

private fun round(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun pathOf(p: MapProjector, points: List<WorldXY>, from: Int = 0, to: Int = points.size - 1): Path = Path().apply {
    for (i in from..to) {
        val o = p.at(points[i])
        if (i == from) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
}

/** The colour of the track at each fix; null where there is nothing to say (off the route, no pass). */
private fun trackColors(d: StreetMapData, coloring: TrackColoring, learned: RiderLearned, grey: Color): List<Color?> {
    val segments = d.route?.segments.orEmpty()
    fun segment(i: Int): RouteSegmentEntity? = d.segAt[i]?.let(segments::getOrNull)
    return when (coloring) {
        TrackColoring.PREDICTED -> d.track.indices.map { i ->
            segment(i)?.let { s -> d.passes[s.idx] }?.let(::gained)?.let { scaleColor(it / FULL_LEARNED) }
        }
        TrackColoring.SPEED -> speedAmounts(d.speeds).map { a -> a?.let(::scaleColor) }
        else -> d.track.indices.map { i -> segment(i)?.let { s -> overlayColor(coloring.overlay!!, s, learned, grey) } }
    }
}

/**
 * The dots on the map, and whether each matters (amber) or not (grey): where the ride stood still,
 * or under "Standing still" the route's traffic lights, where they really are, grey for one that
 * never stops you.
 */
private fun marksFor(d: StreetMapData, coloring: TrackColoring, learned: RiderLearned): List<Pair<WorldXY, Boolean>> {
    val route = d.route
    if (coloring != TrackColoring.STOPS || route == null) return d.stops.map { worldOf(it) to true }
    fun matters(s: RouteSegmentEntity) = learned.lights(s.routeId, s.idx, s.signals ?: 0) > 0
    val lights = route.features.filter { it.kind == FeatureKind.LIGHT }.mapNotNull { it.points.firstOrNull() }
    if (lights.isEmpty()) {
        // Not looked up on the map yet: the lights the segments count, at their ends.
        return route.segments.filter { (it.signals ?: 0) > 0 }.map { worldOf(route.line.pointAt(it.endM)) to matters(it) }
    }
    return lights.map { light ->
        val along = route.line.project(light).alongM
        val s = route.segments.firstOrNull { along < it.endM } ?: route.segments.lastOrNull()
        worldOf(light) to (s != null && matters(s))
    }
}

/** The fix of the track nearest to [at] on screen, if one is within [reachPx]. */
private fun nearestFix(d: StreetMapData, p: MapProjector, at: Offset, reachPx: Float): Int? {
    var best: Int? = null
    var bestD = reachPx * reachPx
    for (i in d.trackWorld.indices) {
        val o = p.at(d.trackWorld[i])
        val dd = (o.x - at.x) * (o.x - at.x) + (o.y - at.y) * (o.y - at.y)
        if (dd <= bestD) {
            bestD = dd
            best = i
        }
    }
    return best
}

/** The line under the map: what was tapped, or else what the lines are. */
private fun infoText(d: StreetMapData?, loaded: Boolean, coloring: TrackColoring, selected: Int?, learned: RiderLearned): String {
    if (d == null) return if (loaded) "" else "Loading…"
    val i = selected
    if (i != null && i in d.track.indices) {
        val parts = arrayListOf("%.2f km into the ride".format(d.alongM[i] / 1000))
        d.speeds[i]?.let { parts += "%.0f km/h".format(it * 3.6) }
        val s = d.segAt[i]?.let { d.route?.segments?.getOrNull(it) }
        when {
            s == null -> parts += if (d.route == null) "a free ride" else "off the route here"
            coloring == TrackColoring.PREDICTED || coloring == TrackColoring.SPEED -> parts += passSummary(s, d.passes[s.idx])
            else -> parts += segmentSummary(s, learned)
        }
        return parts.joinToString(" · ")
    }
    val lines = d.lines
    return when {
        d.route == null -> "A free ride: no route to show. Tap the track to see what happened there."
        lines == null && d.recent -> "The route is still learning from this ride; its new line shows here once it has. Tap the track for details."
        lines == null -> "The route as it is now: this ride is from before rides kept the route they were given. Tap the track for details."
        lines.unchanged -> "This ride didn't move the route. Tap the track for details."
        d.afterIsNow -> "Planned: the route as this ride had it. Next ride: the route after learning from this ride, as your next ride gets it. Tap the track for details."
        else -> "Planned: the route as this ride had it. After ride: the route once it had learned from this ride; later rides have moved it since."
    }
}

/** A button on the map: the card's colour, nearly solid, so it reads over any street. */
@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Switches one of the map's lines on or off; it shows the line's own look, so it is the legend too. */
@Composable
private fun LayerToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit, swatch: DrawScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (on) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp), onDraw = swatch)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
