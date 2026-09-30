package com.tegenwind.app.ui.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.EnrichState
import com.tegenwind.app.data.RouteSegmentEntity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.tegenwind.app.ui.Action
import com.tegenwind.app.ui.ActionCard
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.StatTile
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.ui.rememberConfirmTap
import com.tegenwind.app.ui.theme.scaleColor
import com.tegenwind.app.weather.compassPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun RouteDetailScreen(routeId: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val container = LocalContext.current.appContainer
    val dao = container.db.routes()
    val repo = container.routes
    val scope = rememberCoroutineScope()
    val route by remember(routeId) { dao.routeFlow(routeId) }.collectAsStateWithLifecycle(null)
    val segments by remember(routeId) { dao.segmentsFlow(routeId) }.collectAsStateWithLifecycle(emptyList())
    // The second tap has to follow soon, or Delete goes back to asking.
    var confirmDelete by rememberConfirmTap()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Routes") }
        val r = route ?: return@Column
        Text(r.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "%.1f km · %d segments of ~%d m".format(r.lengthM / 1000, segments.size, if (segments.isEmpty()) 0 else (r.lengthM / segments.size).toInt()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnrichStatus(r)

        if (r.enrichState == EnrichState.DONE && segments.isNotEmpty()) {
            val lights = segments.sumOf { it.signals ?: 0 }
            val openKm = segments.filter { (it.exposure ?: 0.0) >= 0.7 }.sumOf { it.endM - it.startM } / 1000
            val steepest = segments.mapNotNull { it.gradePct }.maxByOrNull { kotlin.math.abs(it) } ?: 0.0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Traffic lights", "$lights", Modifier.weight(1f))
                StatTile("Open to wind", "%.1f km".format(openKm), Modifier.weight(1f))
                StatTile("Steepest", "%+.1f%%".format(steepest), Modifier.weight(1f))
            }
            ChartCard("Wind exposure along the route", "Tall bars: open fields. Short bars: between buildings. Amber ticks: traffic lights.") {
                ExposureStrip(segments, r.lengthM, Modifier.fillMaxWidth().height(104.dp))
            }
        }

        if (segments.isNotEmpty()) {
            // 50-odd rows is a lot to scroll past, so the table waits until asked for.
            var showSegments by rememberSaveable(routeId) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Segments", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showSegments = !showSegments }) {
                            Text(if (showSegments) "Hide" else "Show all ${segments.size}")
                        }
                    }
                    if (showSegments) {
                        SegmentHeader()
                        segments.forEach { s ->
                            HorizontalDivider()
                            SegmentRow(s)
                        }
                    }
                }
            }
        }

        ActionCard(
            listOf(
                Action("Add reverse route", "The same route the other way round, e.g. werk-woon from woon-werk.") {
                    scope.launch { onOpen(repo.createReverse(routeId)) }
                },
                // Also while it's running: that's exactly when a stalled lookup needs restarting.
                Action("Look up again", "Fetches elevation, buildings and traffic lights again, e.g. after a failed lookup.") {
                    repo.enrich(routeId)
                },
                Action(
                    if (confirmDelete) "Sure? Tap again" else "Delete route",
                    "Removes the route and what it learned. Your rides on it stay, no longer linked to a route.",
                    danger = true,
                    armed = confirmDelete,
                ) {
                    if (confirmDelete) {
                        confirmDelete = false
                        scope.launch {
                            repo.delete(routeId)
                            onBack()
                        }
                    } else confirmDelete = true
                },
            )
        )
        Text(DATA_ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * How open each segment is to the wind, as bars along the route: tall is an open field, short is
 * between buildings. Grey, because it describes the road rather than judging it; amber ticks mark
 * traffic lights. With [axis], km marks run underneath.
 */
@Composable
internal fun ExposureStrip(segments: List<RouteSegmentEntity>, lengthM: Double, modifier: Modifier, axis: Boolean = true) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.onSurfaceVariant
    val light = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    Canvas(modifier) {
        val axisH = if (axis) 16.dp.toPx() else 0f
        val h = size.height - axisH
        val gap = 1.dp.toPx()
        val tick = minOf(10.dp.toPx(), h * 0.3f)
        segments.forEach { s ->
            val x0 = (s.startM / lengthM * size.width).toFloat()
            val w = ((s.endM - s.startM) / lengthM * size.width).toFloat() - gap
            val e = (s.exposure ?: 0.0).toFloat()
            drawRect(track, Offset(x0, 0f), Size(w, h))
            drawRect(bar.copy(alpha = 0.35f + 0.65f * e), Offset(x0, h * (1 - e)), Size(w, h * e))
            repeat(s.signals ?: 0) { k ->
                val x = x0 + w - 2.dp.toPx() - k * 4.dp.toPx()
                drawRect(light, Offset(x, 0f), Size(2.dp.toPx(), tick))
            }
        }
        if (axis) {
            // A mark every 1, 2 or 5 km, whichever gives at most seven.
            val km = lengthM / 1000
            val step = listOf(1, 2, 5, 10).first { km / it <= 7 }
            var k = 0
            while (k <= km + 1e-9) {
                val text = if (k + step > km) "$k km" else "$k"
                val layout = measurer.measure(text, labelStyle)
                val x = (k * 1000 / lengthM * size.width).toFloat()
                val lx = (x - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
                k += step
            }
        }
    }
}

/** Column widths of the segments table, dp: six columns that still fit a 360 dp screen. */
private val SEGMENT_COLUMNS = listOf("km" to 56, "dir" to 40, "slope" to 56, "open" to 48, "lights" to 44, "learned" to 60)

/** From a quarter more or less time than your usual pace, a segment's learned time is fully red or green. */
private const val FULL_LEARNED = 0.25

@Composable
private fun SegmentHeader() {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            "learned: how each segment rides compared with your usual pace. Green is faster, red slower.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 6.dp)) {
            SEGMENT_COLUMNS.forEach { (t, w) ->
                Text(t, Modifier.width(w.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SegmentRow(s: RouteSegmentEntity) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val w = SEGMENT_COLUMNS.map { it.second.dp }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%.2f".format(s.startM / 1000), Modifier.width(w[0]), style = style)
        Text(compassPoint(s.bearingDeg), Modifier.width(w[1]), style = style)
        Text(s.gradePct?.let { "%+.1f%%".format(it) } ?: "–", Modifier.width(w[2]), style = style)
        Text(s.exposure?.let { "%d%%".format((it * 100).toInt()) } ?: "–", Modifier.width(w[3]), style = style)
        Text(s.signals?.takeIf { it > 0 }?.toString() ?: "", Modifier.width(w[4]), style = style, color = MaterialTheme.colorScheme.primary)
        // How much longer than your usual pace the segment takes, as the ETA now counts it; nothing before a first pass.
        if (s.learnedPasses > 0) {
            val extra = SegmentCorrection(s.learnedLogMean).timeFactor - 1
            Text("%+d%%".format((extra * 100).roundToInt()), Modifier.width(w[5]), style = style, color = scaleColor(-extra / FULL_LEARNED))
        } else {
            Text("–", Modifier.width(w[5]), style = style)
        }
        Spacer(Modifier.weight(1f))
    }
}
