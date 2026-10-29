package com.tegenwind.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tegenwind.app.appContainer
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.ui.theme.AxisInk
import kotlin.math.log2
import kotlin.math.roundToInt

/** Where an [OsmMap] looks: the middle of the view on the world square, and how far in. */
@Stable
class OsmView(center: WorldXY, zoom: Double) {
    var center by mutableStateOf(center)
        private set
    var zoom by mutableDoubleStateOf(zoom)
        private set

    /** Moves the map along with a finger that moved [dx], [dy] pixels. */
    fun panBy(dx: Float, dy: Float, tilePx: Float) {
        val world = worldPx(zoom, tilePx)
        center = WorldXY((center.x - dx / world).coerceIn(0.0, 1.0), (center.y - dy / world).coerceIn(0.0, 1.0))
    }

    /** Zooms in by [levels] (out when negative), keeping the place under [focus] where it is on screen. */
    fun zoomAround(focus: Offset, levels: Double, widthPx: Float, heightPx: Float, tilePx: Float) {
        val before = worldPx(zoom, tilePx)
        val fx = center.x + (focus.x - widthPx / 2) / before
        val fy = center.y + (focus.y - heightPx / 2) / before
        zoom = (zoom + levels).coerceIn(OsmZoom.MIN, OsmZoom.MAX)
        val after = worldPx(zoom, tilePx)
        center = WorldXY(fx - (focus.x - widthPx / 2) / after, fy - (focus.y - heightPx / 2) / after)
    }
}

/** Puts places on the screen as the map is drawn right now. */
class MapProjector(private val center: WorldXY, private val worldPx: Double, private val widthPx: Float, private val heightPx: Float) {
    fun at(w: WorldXY): Offset =
        Offset(((w.x - center.x) * worldPx + widthPx / 2).toFloat(), ((w.y - center.y) * worldPx + heightPx / 2).toFloat())

    fun at(p: GeoPoint): Offset = at(worldOf(p))
}

/** A tile is drawn this big at its own zoom level: OpenStreetMap's 256 pixels, as density-independent pixels so its text is readable. */
private val TILE_SIZE = 256.dp

/** Room left around everything a map is opened on. */
private val FIT_PAD = 28.dp

/**
 * A street map from OpenStreetMap, dark like the rest of the app, opened on all of [fitTo] and
 * opened on it again whenever [fitKey] changes. Drag to move, pinch or double-tap to zoom.
 * [overlay] draws on top of the streets, with a [MapProjector] for the view as it is; [onTap] gets
 * where a single tap landed, on screen. OpenStreetMap's credit sits in the bottom right corner, as
 * its licence asks.
 */
@Composable
fun OsmMap(
    fitTo: List<WorldXY>,
    modifier: Modifier = Modifier,
    fitKey: Any? = null,
    onTap: ((Offset, MapProjector) -> Unit)? = null,
    overlay: DrawScope.(MapProjector) -> Unit,
) {
    val tiles = LocalContext.current.appContainer.tiles
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tilePx = with(density) { TILE_SIZE.toPx() }
    val padPx = with(density) { FIT_PAD.toPx() }
    val dark = remember { ColorFilter.colorMatrix(ColorMatrix(darkTileMatrix())) }
    val background = MaterialTheme.colorScheme.background
    // The latest tap handler, without restarting the gesture detection each time it changes.
    val tapped by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier.clipToBounds().background(background)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val view = remember(fitKey) {
            val (center, zoom) = fitView(fitTo, widthPx, heightPx, tilePx, padPx)
            OsmView(center, zoom)
        }
        val visible = visibleTiles(view.center, view.zoom, widthPx, heightPx, tilePx)
        LaunchedEffect(visible) { tiles.request(visible, scope) }
        fun projector(w: Float, h: Float) = MapProjector(view.center, worldPx(view.zoom, tilePx), w, h)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(view) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        view.zoomAround(centroid, log2(zoom.toDouble()), size.width.toFloat(), size.height.toFloat(), tilePx)
                        view.panBy(pan.x, pan.y, tilePx)
                    }
                }
                .pointerInput(view) {
                    detectTapGestures(
                        onDoubleTap = { at -> view.zoomAround(at, 1.0, size.width.toFloat(), size.height.toFloat(), tilePx) },
                        onTap = { at -> tapped?.invoke(at, projector(size.width.toFloat(), size.height.toFloat())) },
                    )
                },
        ) {
            // Read so the map draws again as each tile arrives.
            tiles.arrived
            drawTiles(tiles, view, visible, tilePx, dark)
            overlay(projector(size.width, size.height))
        }
        val uri = LocalUriHandler.current
        Text(
            "© OpenStreetMap contributors",
            style = TextStyle(color = AxisInk, fontSize = 10.sp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .clickable {
                    try {
                        uri.openUri("https://www.openstreetmap.org/copyright")
                    } catch (e: Exception) {
                        // No browser to open it in: the credit is still on the map.
                    }
                }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * The [visible] tiles of the view. A tile that isn't loaded yet borrows the part of a coarser one
 * that is, enlarged, so zooming in shows a blurred map rather than holes.
 */
private fun DrawScope.drawTiles(tiles: OsmTiles, view: OsmView, visible: List<TileKey>, tilePx: Float, filter: ColorFilter) {
    val world = worldPx(view.zoom, tilePx)
    fun screenX(wx: Double) = ((wx - view.center.x) * world + size.width / 2).roundToInt()
    fun screenY(wy: Double) = ((wy - view.center.y) * world + size.height / 2).roundToInt()
    for (key in visible) {
        val n = (1 shl key.z).toDouble()
        // Each edge rounded on its own, so neighbouring tiles meet without a hairline between them.
        val left = screenX(key.x / n)
        val top = screenY(key.y / n)
        val dstOffset = IntOffset(left, top)
        val dstSize = IntSize(screenX((key.x + 1) / n) - left, screenY((key.y + 1) / n) - top)
        val image = tiles[key]
        if (image != null) {
            drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), dstOffset, dstSize, colorFilter = filter, filterQuality = FilterQuality.Low)
            continue
        }
        for (up in 1..MAX_STAND_IN) {
            if (key.z - up < 0) break
            val parent = tiles[TileKey(key.z - up, key.x shr up, key.y shr up)] ?: continue
            val part = parent.width shr up
            if (part < 1) break
            val src = IntOffset((key.x - ((key.x shr up) shl up)) * part, (key.y - ((key.y shr up) shl up)) * part)
            drawImage(parent, src, IntSize(part, part), dstOffset, dstSize, colorFilter = filter, filterQuality = FilterQuality.Low)
            break
        }
    }
}

/** How many levels coarser a tile may be to stand in for a missing one. */
private const val MAX_STAND_IN = 4
