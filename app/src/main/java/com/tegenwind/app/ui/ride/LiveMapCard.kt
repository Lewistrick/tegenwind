package com.tegenwind.app.ui.ride

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tegenwind.app.appContainer
import com.tegenwind.app.ride.Ghost
import com.tegenwind.app.ride.GhostChoice
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.ghostOf
import com.tegenwind.app.ride.pickGhostRide
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.ui.ChartCard
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.HeadingMap
import com.tegenwind.app.ui.MapFlag
import com.tegenwind.app.ui.RouteMap
import com.tegenwind.app.ui.aheadM
import com.tegenwind.app.ui.routeBearingAt
import com.tegenwind.app.ui.theme.SpeedColor
import com.tegenwind.app.ui.mapAspect
import com.tegenwind.app.ui.routes.FULL_LEARNED
import com.tegenwind.app.ui.theme.scaleColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PREF_GHOST = "ghost"

/** Width over height of the live map: a little taller than wide would be too much on the ride screen. */
private const val LIVE_MAP_ASPECT = 1.25f

/** "40 m" up to a kilometre, "1.2 km" beyond. */
fun gapText(metres: Double): String =
    if (abs(metres) < 995) "${(abs(metres) / 10).roundToInt() * 10} m" else "%.1f km".format(abs(metres) / 1000)

/** How you stand against the ghost, in words; [gapM] is positive when you are ahead of it. */
fun ghostLine(gapM: Double?, choice: GhostChoice): String = when {
    gapM == null -> "No earlier ride on this route to ride against yet."
    abs(gapM) < 10 -> "Level with ${choice.words}."
    gapM > 0 -> "${gapText(gapM)} ahead of ${choice.words}."
    else -> "${gapText(gapM)} behind ${choice.words}."
}

/**
 * The route around you, turned to the way you're going and zoomed to your speed: the road behind
 * you coloured by the time gained or lost on each stretch, the road ahead plain, you as a blue
 * arrow, and as a faint grey arrow the ghost of an earlier ride, where it was this long after
 * setting off.
 */
@Composable
fun LiveMapCard(ride: LiveRide, nowMs: Long) {
    val context = LocalContext.current
    val container = context.appContainer
    val loaded = container.recorder.ridingRoute ?: return
    val prefs = remember { context.getSharedPreferences("tegenwind", Context.MODE_PRIVATE) }
    var choice by remember {
        mutableStateOf(GhostChoice.entries.firstOrNull { it.name == prefs.getString(PREF_GHOST, null) } ?: GhostChoice.MEDIAN)
    }
    // Replayed once per route and choice; the ride being recorded is not among the finished ones.
    val ghost by produceState<Ghost?>(null, loaded.route.id, loaded.line, choice) {
        val rides = container.db.rides().ridesForRoute(loaded.route.id).first()
        val pick = pickGhostRide(rides, choice, loaded.line.lengthM)
        value = pick?.let {
            val points = container.db.rides().points(it.id)
            withContext(Dispatchers.Default) { ghostOf(loaded.line, it.startedAtMs, points) }
        }
    }
    val progress = ride.route?.progress
    val elapsedS = if (ride.started) (nowMs - ride.snapshot.startedAtMs) / 1000.0 else 0.0
    val ghostAlongM = ghost?.alongAt(elapsedS)
    val gapM = if (ghostAlongM != null && progress?.onRouteYet == true) progress.progressM - ghostAlongM else null
    val s = ride.snapshot
    ChartCard("Map", if (ghost == null || gapM != null) ghostLine(gapM, choice) else "The grey arrow is ${choice.words}.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val spans = loaded.segments.map { it.startM to it.endM }
            val colors = loaded.segments.map { seg -> ride.segmentGains[seg.idx]?.let { scaleColor(it / FULL_LEARNED) } }
            val you = if (s.lastLat != null && s.lastLon != null) GeoPoint(s.lastLat, s.lastLon) else null
            if (you == null) {
                // No position yet: the whole route, north up, until there is one.
                RouteMap(
                    line = loaded.line,
                    modifier = Modifier.fillMaxWidth().aspectRatio(mapAspect(loaded.line.points)),
                    spans = spans,
                    colors = colors,
                )
            } else {
                // On the route it turns with the road, which is steadier than the GPS's own heading.
                val heading = when {
                    progress?.onRouteYet == true && !progress.offRoute -> routeBearingAt(loaded.line, progress.progressM)
                    else -> s.headingDeg ?: 0.0
                }
                HeadingMap(
                    line = loaded.line,
                    you = you,
                    headingDeg = heading,
                    aheadM = aheadM(s.speedKmh?.let { it / 3.6 }, gapM),
                    modifier = Modifier.fillMaxWidth().aspectRatio(LIVE_MAP_ASPECT),
                    spans = spans,
                    colors = colors,
                    ghost = ghostAlongM?.let(loaded.line::pointAt),
                    ghostHeadingDeg = ghostAlongM?.let { routeBearingAt(loaded.line, it) },
                    // Each border is a checkpoint: a stretch takes its colour once you pass its end.
                    flags = loaded.segments.drop(1).map { seg ->
                        MapFlag(loaded.line.pointAt(seg.startM), passed = progress?.onRouteYet == true && progress.progressM >= seg.startM)
                    },
                    youColor = SpeedColor,
                )
            }
            Picker(
                choice,
                GhostChoice.entries.map { it to it.label },
                onSelect = {
                    choice = it
                    prefs.edit().putString(PREF_GHOST, it.name).apply()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
