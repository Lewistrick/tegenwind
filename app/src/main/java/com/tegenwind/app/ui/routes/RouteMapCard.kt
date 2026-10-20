package com.tegenwind.app.ui.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.eta.RiderLearned
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.MapMark
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.RouteMap
import com.tegenwind.app.ui.mapAspect
import com.tegenwind.app.ui.theme.ScaleEven
import com.tegenwind.app.ui.theme.ScaleWorse
import com.tegenwind.app.ui.theme.scaleColor
import com.tegenwind.app.weather.compassPoint
import kotlin.math.roundToInt

/** What a route's map is coloured by. */
enum class RouteOverlay(val label: String, val explains: String) {
    PACE("Learned pace", "What your rides taught Tegenwind: red where it allows more time, green less. Grey: not ridden yet."),
    EXPOSURE("Wind exposure", "Bright is open to the wind, dim is sheltered."),
    GRADE("Slope", "Red climbs, green descends."),
    STOPS("Standing still", "Red where you wait, by how long on average. Amber dots: traffic lights; grey ones never stop you."),
}

/** From a quarter more or less time than your usual pace, a segment is fully red or green. */
const val FULL_LEARNED = 0.25

/** A 2% slope is fully red or green: about the steepest a Dutch commute gets. */
private const val FULL_GRADE_PCT = 2.0

/** Ten seconds of standing on average is fully red. */
private const val FULL_STOP_S = 10.0

/** What [s] is coloured under [overlay]; null where there's nothing to say. */
fun overlayColor(overlay: RouteOverlay, s: RouteSegmentEntity, learned: RiderLearned, grey: Color): Color? = when (overlay) {
    RouteOverlay.PACE ->
        if (s.learnedPasses > 0) scaleColor(-(SegmentCorrection(s.learnedLogMean).timeFactor - 1) / FULL_LEARNED) else null
    RouteOverlay.EXPOSURE -> s.exposure?.let { grey.copy(alpha = (0.25 + 0.75 * it).toFloat()) }
    RouteOverlay.GRADE -> s.gradePct?.let { scaleColor(-it / FULL_GRADE_PCT) }
    RouteOverlay.STOPS -> learned.stop(s.routeId, s.idx, s.signals ?: 0).meanS.takeIf { it >= 0.5 }
        ?.let { lerp(ScaleEven, ScaleWorse, (it / FULL_STOP_S).toFloat().coerceIn(0f, 1f)) }
}

/**
 * The route as a line, coloured by the thing chosen above it. Tapping a stretch says what is known
 * about it. [segments] carry the map's lights as stored: under "Standing still" the ones that never
 * stop you are shown greyed rather than left out.
 */
@Composable
fun RouteMapCard(line: Polyline, segments: List<RouteSegmentEntity>, learned: RiderLearned) {
    var overlay by rememberSaveable { mutableStateOf(RouteOverlay.PACE) }
    var selected by rememberSaveable(segments.size) { mutableStateOf<Int?>(null) }
    val grey = MaterialTheme.colorScheme.onSurface
    ChartCard("Map", overlay.explains) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(overlay, RouteOverlay.entries.map { it to it.label }, onSelect = { overlay = it }, modifier = Modifier.fillMaxWidth())
            RouteMap(
                line = line,
                modifier = Modifier.fillMaxWidth().aspectRatio(mapAspect(line.points)),
                spans = segments.map { it.startM to it.endM },
                colors = segments.map { overlayColor(overlay, it, learned, grey) },
                marks = if (overlay != RouteOverlay.STOPS) emptyList() else segments.filter { (it.signals ?: 0) > 0 }.map {
                    MapMark(line.pointAt(it.endM), counts = learned.lights(it.routeId, it.idx, it.signals ?: 0) > 0)
                },
                selected = selected,
                onSelect = { selected = it },
            )
            val s = selected?.let(segments::getOrNull)
            Text(
                if (s == null) "Tap a stretch to see what's known about it." else segmentSummary(s, learned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 3,
            )
        }
    }
}

/** One stretch in words: where it is, how it lies, and what riding it has taught. */
fun segmentSummary(s: RouteSegmentEntity, learned: RiderLearned): String {
    val parts = ArrayList<String>()
    parts += "%.2f–%.2f km".format(s.startM / 1000, s.endM / 1000)
    parts += "heading ${compassPoint(s.bearingDeg)}"
    s.gradePct?.let { parts += "slope %+.1f%%".format(it) }
    s.exposure?.let { parts += "%d%% open".format((it * 100).toInt()) }
    if (s.learnedPasses > 0) {
        val extra = SegmentCorrection(s.learnedLogMean).timeFactor - 1
        parts += "%+d%% time vs your pace".format((extra * 100).roundToInt())
        parts += "${s.learnedPasses} pass${if (s.learnedPasses == 1) "" else "es"}"
    } else parts += "not ridden yet"
    val stop = learned.stop(s.routeId, s.idx, s.signals ?: 0).meanS
    if (stop >= 0.5) parts += "stands %.0f s on average".format(stop)
    val mapped = s.signals ?: 0
    if (mapped > 0 && learned.lights(s.routeId, s.idx, mapped) == 0) parts += "a light that never stops you"
    return parts.joinToString(" · ")
}
