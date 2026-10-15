package com.tegenwind.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline

/** A spot marked on a [RouteMap]: [counts] false draws it greyed, for something there that doesn't matter to you. */
class MapMark(val at: GeoPoint, val counts: Boolean = true)

/**
 * A route as a line on the dark card, north up, without streets around it: for a road you ride
 * every day the shape is enough to know where you are on it.
 *
 * @param line the route; null draws only [track].
 * @param spans the route's segments, as distances along [line].
 * @param colors one per span: what that stretch is coloured; null for "nothing to say here".
 * @param track where a ride actually went, drawn thinly over the route.
 * @param marks spots along the way, such as where you stood still.
 * @param you your position; [ghost] where an earlier ride would be now.
 * @param selected the span to stand out; [onSelect] makes the map tappable.
 */
@Composable
fun RouteMap(
    line: Polyline?,
    modifier: Modifier = Modifier,
    spans: List<Pair<Double, Double>> = emptyList(),
    colors: List<Color?> = emptyList(),
    track: List<GeoPoint> = emptyList(),
    marks: List<MapMark> = emptyList(),
    you: GeoPoint? = null,
    ghost: GeoPoint? = null,
    selected: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
) {
    // Lighter than an outline: the road has to read as a road where nothing colours it.
    val plain = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val card = MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    // Everything that has to fit: the route, and a ride that strayed from it.
    val all = remember(line, track) { line?.points.orEmpty() + track }
    val stretches = remember(line, spans) {
        if (line == null) emptyList() else spans.map { (s, e) -> stretchOf(line, s, e) }
    }
    val tappable = if (onSelect == null || line == null) Modifier else Modifier.pointerInput(line, spans) {
        detectTapGestures { tap ->
            val projection = MapProjection(all, size.width.toFloat(), size.height.toFloat(), PAD.toPx())
            onSelect(nearestStretch(line, spans, projection, tap.x, tap.y, TAP_REACH.toPx()))
        }
    }
    Canvas(modifier.then(tappable)) {
        if (all.size < 2) return@Canvas
        val p = MapProjection(all, size.width, size.height, PAD.toPx())
        fun at(g: GeoPoint) = Offset(p.x(g), p.y(g))
        fun pathOf(points: List<GeoPoint>) = Path().apply {
            points.forEachIndexed { i, g -> if (i == 0) moveTo(p.x(g), p.y(g)) else lineTo(p.x(g), p.y(g)) }
        }
        fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // A ride's own track goes under its route, so it shows where the ride left the road and
        // doesn't wash out the road's colours where it followed it.
        if (track.size >= 2) {
            drawPath(pathOf(track), ink.copy(alpha = if (line == null) 1f else 0.7f), style = stroke((if (line == null) ROAD else TRACK).toPx()))
        }
        if (line != null) {
            // The whole road first, so stretches with nothing to say still show where it goes.
            drawPath(pathOf(line.points), plain, style = stroke(ROAD.toPx()))
            // The selected stretch gets a light edge under its colour.
            selected?.let { stretches.getOrNull(it) }?.let { drawPath(pathOf(it), ink, style = stroke(SELECTED.toPx())) }
            stretches.forEachIndexed { i, pts ->
                val color = colors.getOrNull(i) ?: return@forEachIndexed
                drawPath(pathOf(pts), color, style = stroke(ROAD.toPx()))
            }
            drawCircle(muted, END.toPx(), at(line.points.first()))
            drawCircle(muted, END.toPx(), at(line.points.last()))
        }
        for (m in marks) {
            drawCircle(card, (MARK + 1.5.dp).toPx(), at(m.at))
            drawCircle(if (m.counts) accent else muted, MARK.toPx(), at(m.at))
        }
        ghost?.let {
            drawCircle(card, (DOT + 1.dp).toPx(), at(it))
            drawCircle(muted, DOT.toPx(), at(it), style = Stroke(2.dp.toPx()))
        }
        you?.let {
            drawCircle(card, (DOT + 2.dp).toPx(), at(it))
            drawCircle(ink, DOT.toPx(), at(it))
        }
    }
}

private val PAD = 14.dp
private val ROAD = 5.dp
private val SELECTED = 9.dp
private val TRACK = 2.dp
private val END = 3.dp
private val MARK = 3.dp
private val DOT = 6.dp
private val TAP_REACH = 28.dp
