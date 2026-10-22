package com.tegenwind.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.bearingDeg
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.Water
import com.tegenwind.app.routes.FeatureKind
import com.tegenwind.app.routes.MapFeature
import kotlin.math.cos
import kotlin.math.sin

/**
 * A view of the map centred on you and turned so the way you are going points up, the way a map
 * held in your hands is turned. You sit [youAt] of the way down, so most of it is road ahead.
 */
class HeadingView(
    private val you: GeoPoint,
    headingDeg: Double,
    /** Metres from you to the top edge. */
    aheadM: Double,
    private val widthPx: Float,
    private val heightPx: Float,
    youAt: Float = YOU_AT,
) {
    private val kx = 111_320.0 * cos(Math.toRadians(you.lat))
    private val ky = 110_574.0
    private val c = cos(Math.toRadians(headingDeg))
    private val s = sin(Math.toRadians(headingDeg))
    private val cy = heightPx * youAt

    /** Pixels per metre. */
    val scale: Double = cy / aheadM

    /** Where [p] lands on screen. */
    fun at(p: GeoPoint): Offset {
        val east = (p.lon - you.lon) * kx
        val north = (p.lat - you.lat) * ky
        // Turned so the heading points up: what lies ahead is up, what lies to the right is right.
        val right = east * c - north * s
        val ahead = east * s + north * c
        return Offset((widthPx / 2 + right * scale).toFloat(), (cy - ahead * scale).toFloat())
    }

    companion object {
        const val YOU_AT = 0.68f
    }
}

/** The way a route runs at [alongM], over a stretch either side so a kink in the line doesn't swing it. */
fun routeBearingAt(line: Polyline, alongM: Double, overM: Double = 40.0): Double =
    bearingDeg(line.pointAt(alongM - overM), line.pointAt(alongM + overM))

/** The smallest turn from [fromDeg] to [toDeg], in −180..180. */
fun shortestTurn(fromDeg: Double, toDeg: Double): Double = ((toDeg - fromDeg) % 360 + 540) % 360 - 180

/**
 * How far ahead the map shows, metres: about the next 45 seconds at your speed, and far enough that
 * a ghost close by is in view. Between [MIN_AHEAD_M] (standing still, at a light) and
 * [MAX_AHEAD_M].
 */
fun aheadM(speedMps: Double?, ghostGapM: Double?): Double {
    val bySpeed = (speedMps ?: 0.0) * AHEAD_S
    val byGhost = ghostGapM?.let { kotlin.math.abs(it) * 1.3 }?.takeIf { it <= MAX_AHEAD_M } ?: 0.0
    return maxOf(bySpeed, byGhost).coerceIn(MIN_AHEAD_M, MAX_AHEAD_M)
}

const val AHEAD_S = 45.0
const val MIN_AHEAD_M = 150.0
const val MAX_AHEAD_M = 700.0

/**
 * An arrow: a triangle with a notch cut out of its back, pointing up, its middle at [at], turned by
 * [turnDeg] clockwise. Filled, or only its edge with a [style] of [Stroke].
 */
fun DrawScope.arrow(at: Offset, turnDeg: Float, lengthPx: Float, color: Color, style: DrawStyle = Fill) {
    val half = lengthPx / 2
    val path = Path().apply {
        moveTo(at.x, at.y - half)
        lineTo(at.x + lengthPx * 0.38f, at.y + half)
        lineTo(at.x, at.y + half * 0.45f)
        lineTo(at.x - lengthPx * 0.38f, at.y + half)
        close()
    }
    rotate(turnDeg, pivot = at) { drawPath(path, color, style = style) }
}

/** A segment border on the live map; [passed] once you've ridden past it. */
class MapFlag(val at: GeoPoint, val passed: Boolean)

/**
 * A small checkpoint flag standing on [at]: a pole with a pennant to its right, upright on the
 * screen whichever way the map is turned.
 */
fun DrawScope.flag(at: Offset, heightPx: Float, color: Color, rim: Color) {
    val top = Offset(at.x, at.y - heightPx)
    val pennant = Path().apply {
        moveTo(top.x, top.y)
        lineTo(top.x + heightPx * 0.55f, top.y + heightPx * 0.22f)
        lineTo(top.x, top.y + heightPx * 0.44f)
        close()
    }
    val pole = heightPx * 0.09f
    drawLine(rim, at, top, strokeWidth = pole + 3f, cap = StrokeCap.Round)
    drawPath(pennant, rim, style = Stroke(3f, join = StrokeJoin.Round))
    drawLine(color, at, top, strokeWidth = pole, cap = StrokeCap.Round)
    drawPath(pennant, color)
    drawCircle(color, pole * 1.2f, at)
}

/**
 * The route around you, turned to your direction of travel and zoomed to your speed: the plain
 * road, you as a blue arrow, and the ghost as a see-through white one. Turning and zooming glide
 * rather than jump.
 */
@Composable
fun HeadingMap(
    line: Polyline,
    you: GeoPoint,
    headingDeg: Double,
    aheadM: Double,
    modifier: Modifier = Modifier,
    ghost: GeoPoint? = null,
    ghostHeadingDeg: Double? = null,
    flags: List<MapFlag> = emptyList(),
    youColor: Color,
    /** Off the route: the nearest point of it, which a thin red line points you back to, however far. */
    offRouteTo: GeoPoint? = null,
    /** Side roads, bridges and traffic lights around the route ([com.tegenwind.app.routes.mapFeatures]). */
    features: List<MapFeature> = emptyList(),
) {
    val plain = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ROAD_ALPHA)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceContainer
    val amber = MaterialTheme.colorScheme.primary
    val side = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SIDE_ALPHA)
    val sideRoads = remember(features) { features.filter { it.kind == FeatureKind.SIDE } }
    val bridges = remember(features) { features.filter { it.kind == FeatureKind.BRIDGE && it.points.size == 2 } }
    val lights = remember(features) { features.filter { it.kind == FeatureKind.LIGHT }.map { it.points.first() } }

    // The heading turns the short way round, smoothly; the zoom eases between speeds.
    val turn = remember { Animatable(headingDeg.toFloat()) }
    LaunchedEffect(headingDeg) {
        val target = turn.value + shortestTurn(turn.value.toDouble(), headingDeg).toFloat()
        turn.animateTo(target, tween(TURN_MS, easing = LinearOutSlowInEasing))
    }
    val zoom by animateFloatAsState(aheadM.toFloat(), tween(ZOOM_MS), label = "zoom")

    Canvas(modifier) {
        val view = HeadingView(you, turn.value.toDouble(), zoom.toDouble(), size.width, size.height)
        fun pathOf(points: List<GeoPoint>) = Path().apply {
            points.forEachIndexed { i, g -> view.at(g).let { o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        }
        val road = Stroke(ROAD.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Side roads first and fainter, so they read as streets that join, not as the way to go.
        val sideStroke = Stroke(SIDE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (s in sideRoads) drawPath(pathOf(s.points), side, style = sideStroke)
        // Water under each bridge: a band across the road, a little longer than the bridge is wide.
        for (b in bridges) {
            val a = view.at(b.points[0])
            val z = view.at(b.points[1])
            val len = (z - a).getDistance()
            if (len < 1f) continue
            val dir = (z - a) / len
            val across = Offset(-dir.y, dir.x)
            val mid = (a + z) / 2f
            val halfAlong = len * WATER_ALONG / 2
            val halfAcross = WATER_ACROSS.toPx() / 2
            drawPath(
                Path().apply {
                    (mid - dir * halfAlong - across * halfAcross).let { moveTo(it.x, it.y) }
                    (mid + dir * halfAlong - across * halfAcross).let { lineTo(it.x, it.y) }
                    (mid + dir * halfAlong + across * halfAcross).let { lineTo(it.x, it.y) }
                    (mid - dir * halfAlong + across * halfAcross).let { lineTo(it.x, it.y) }
                    close()
                },
                Water,
            )
        }
        drawPath(pathOf(line.points), plain, style = road)
        // A bridge's railings: a light line on each side of the road over its length.
        for (b in bridges) {
            val a = view.at(b.points[0])
            val z = view.at(b.points[1])
            val len = (z - a).getDistance()
            if (len < 1f) continue
            val across = Offset(-(z - a).y / len, (z - a).x / len) * (ROAD.toPx() / 2 + RAIL_GAP.toPx())
            for (sign in listOf(1f, -1f)) {
                drawLine(ink.copy(alpha = RAIL_ALPHA), a + across * sign, z + across * sign, strokeWidth = RAIL.toPx(), cap = StrokeCap.Round)
            }
        }
        // Traffic lights where they really are: small amber dots with a dark rim.
        for (l in lights) {
            val o = view.at(l)
            drawCircle(card, LIGHT.toPx() + 1.5.dp.toPx(), o)
            drawCircle(amber, LIGHT.toPx(), o)
        }
        drawCircle(muted, END.toPx(), view.at(line.points.last()))
        // Where one segment ends and the next begins: the ones ahead bright, the ones behind faint.
        for (f in flags) {
            val o = view.at(f.at)
            if (o.x < -FLAG.toPx() || o.x > size.width + FLAG.toPx() || o.y < 0 || o.y > size.height + FLAG.toPx()) continue
            flag(o, FLAG.toPx(), youColor.copy(alpha = if (f.passed) FLAG_PASSED_ALPHA else 1f), card)
        }
        if (offRouteTo != null) {
            drawLine(Danger, view.at(you), view.at(offRouteTo), strokeWidth = OFF_ROUTE.toPx(), cap = StrokeCap.Round)
        }
        val length = ARROW.toPx()
        if (ghost != null) {
            val turnDeg = ghostHeadingDeg?.let { shortestTurn(turn.value.toDouble(), it) } ?: 0.0
            val at = view.at(ghost)
            arrow(at, turnDeg.toFloat(), length, ink.copy(alpha = GHOST_FILL_ALPHA))
            arrow(at, turnDeg.toFloat(), length, ink.copy(alpha = GHOST_EDGE_ALPHA), Stroke(GHOST_EDGE.toPx(), join = StrokeJoin.Round))
        }
        val here = view.at(you)
        // A dark rim, so the arrow stands out on the road.
        arrow(here, 0f, length + 4.dp.toPx(), card)
        arrow(here, 0f, length, youColor.copy(alpha = YOU_ALPHA))
    }
}

private val ROAD = 8.dp
private val SIDE = 5.dp
private const val SIDE_ALPHA = 0.4f
/** The water band: this share of the bridge's length along the road, and this wide across it. */
private const val WATER_ALONG = 0.8f
private val WATER_ACROSS = 34.dp
private val RAIL = 1.5.dp
private val RAIL_GAP = 2.dp
private const val RAIL_ALPHA = 0.6f
private val LIGHT = 4.dp
private val OFF_ROUTE = 2.dp
private val END = 5.dp
private val ARROW = 26.dp
private val FLAG = 16.dp
private const val FLAG_PASSED_ALPHA = 0.35f
private const val YOU_ALPHA = 0.92f
private const val GHOST_FILL_ALPHA = 0.25f
private const val GHOST_EDGE_ALPHA = 0.7f
private val GHOST_EDGE = 1.5.dp
private const val ROAD_ALPHA = 0.65f
private const val TURN_MS = 2500
private const val ZOOM_MS = 1500
