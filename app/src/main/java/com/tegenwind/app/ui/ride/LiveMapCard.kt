package com.tegenwind.app.ui.ride

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.eta.etaModel
import com.tegenwind.app.ride.Ghost
import com.tegenwind.app.ride.GhostChoice
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.expectedGhost
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
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.Ink
import com.tegenwind.app.ui.theme.SpeedColor
import com.tegenwind.app.ui.mapAspect
import com.tegenwind.app.ui.rides.usualWatts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PREF_GHOST = "ghost_choice"

/** Width over height of the live map: a little taller than wide would be too much on the ride screen. */
private const val LIVE_MAP_ASPECT = 1.25f

/** Within this many metres of the ghost counts as level. */
const val LEVEL_M = 25.0

/** Within this many seconds of the ghost counts as level. */
const val LEVEL_S = 5.0

/** "40 m" up to a kilometre, "1.2 km" beyond. */
fun gapText(metres: Double): String =
    if (abs(metres) < 995) "${(abs(metres) / 10).roundToInt() * 10} m" else "%.1f km".format(abs(metres) / 1000)

/** [gapText] with its sign: "+40 m" ahead of the ghost, "−1.2 km" behind it. */
fun signedGapText(metres: Double): String = (if (metres >= 0) "+" else "−") + gapText(metres)

/** The time gap as "+0:08" (ahead) or "−1:12" (behind), minutes and seconds. */
fun timeGapText(seconds: Double): String {
    val s = abs(seconds).roundToInt()
    return (if (seconds >= 0) "+" else "−") + "%d:%02d".format(s / 60, s % 60)
}

/** 1 ahead of the ghost, −1 behind it, 0 when within [levelWithin] of it: what colours a gap. */
fun gapSign(gap: Double, levelWithin: Double): Int = when {
    abs(gap) <= levelWithin -> 0
    gap > 0 -> 1
    else -> -1
}

/**
 * What the subtitle says when there are no gap numbers to show; null when there are, since the
 * numbers say it.
 */
fun ghostNote(hasGhost: Boolean, hasGap: Boolean, choice: GhostChoice): String? = when {
    hasGap -> null
    !hasGhost -> if (choice == GhostChoice.EXPECTED) null else "No earlier ride on this route to ride against yet."
    else -> "The light arrow is ${choice.words}."
}

@Composable
private fun GapText(text: String, sign: Int) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = when (sign) {
            1 -> GoodColor
            -1 -> Danger
            else -> Ink
        },
        textAlign = TextAlign.End,
    )
}

/**
 * The route around you, turned to the way you're going and zoomed to your speed: the plain road,
 * you as a blue arrow, and as a see-through white arrow the ghost, where it was this long after
 * setting off. In the top right, how far and how long you are ahead of or behind it.
 */
@Composable
fun LiveMapCard(ride: LiveRide, nowMs: Long) {
    val context = LocalContext.current
    val container = context.appContainer
    val loaded = container.recorder.ridingRoute ?: return
    val prefs = remember { context.getSharedPreferences("tegenwind", Context.MODE_PRIVATE) }
    var choice by remember {
        mutableStateOf(GhostChoice.entries.firstOrNull { it.name == prefs.getString(PREF_GHOST, null) } ?: GhostChoice.EXPECTED)
    }
    val learned by container.riderModel.learned.collectAsStateWithLifecycle()
    val setup by container.riderSetup.setup.collectAsStateWithLifecycle()
    val startedAtMs = ride.snapshot.startedAtMs
    // Replayed once per route and choice; the ride being recorded is not among the finished ones.
    val ghost by produceState<Ghost?>(null, loaded.route.id, loaded.line, choice, startedAtMs, learned, setup) {
        val rides = container.db.rides()
        if (choice == GhostChoice.EXPECTED) {
            val params = setup.params()
            val segments = loaded.etaModel(learned, params).segments
            // Your usual effort: none before a real ride on record, and none on a bike that rides itself.
            val powerW = usualWatts(rides.recentRidesWithPower()) ?: return@produceState
            var weather = container.weather.alongRoute(loaded.line)
            value = withContext(Dispatchers.Default) { expectedGhost(segments, params, loaded.profile, weather, powerW, startedAtMs) }
            // Without a forecast it is still air, until one arrives.
            while (weather == null) {
                delay(60_000)
                weather = container.weather.alongRoute(loaded.line)
                if (weather != null) value = withContext(Dispatchers.Default) { expectedGhost(segments, params, loaded.profile, weather, powerW, startedAtMs) }
            }
        } else {
            val pick = pickGhostRide(rides.ridesForRoute(loaded.route.id).first(), choice, loaded.line.lengthM)
            value = pick?.let {
                val points = rides.points(it.id)
                withContext(Dispatchers.Default) { ghostOf(loaded.line, it.startedAtMs, points) }
            }
        }
    }
    val progress = ride.route?.progress
    val onRoute = progress?.onRouteYet == true
    val elapsedS = if (ride.started) (nowMs - startedAtMs) / 1000.0 else 0.0
    val ghostAlongM = ghost?.alongAt(elapsedS)
    val gapM = if (ghostAlongM != null && onRoute) progress!!.progressM - ghostAlongM else null
    // Positive when you got to where you are sooner than the ghost did.
    val gapS = if (gapM != null) ghost?.elapsedAt(progress!!.progressM)?.let { it - elapsedS } else null
    val s = ride.snapshot
    ChartCard("Map", ghostNote(ghost != null, gapM != null, choice)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val you = if (s.lastLat != null && s.lastLon != null) GeoPoint(s.lastLat, s.lastLon) else null
            if (you == null) {
                // No position yet: the whole route, north up, until there is one.
                RouteMap(
                    line = loaded.line,
                    modifier = Modifier.fillMaxWidth().aspectRatio(mapAspect(loaded.line.points)),
                )
            } else {
                // On the route it turns with the road, which is steadier than the GPS's own heading.
                val heading = when {
                    onRoute && !progress!!.offRoute -> routeBearingAt(loaded.line, progress.progressM)
                    else -> s.headingDeg ?: 0.0
                }
                Box(Modifier.fillMaxWidth()) {
                    HeadingMap(
                        line = loaded.line,
                        you = you,
                        headingDeg = heading,
                        aheadM = aheadM(s.speedKmh?.let { it / 3.6 }, gapM),
                        modifier = Modifier.fillMaxWidth().aspectRatio(LIVE_MAP_ASPECT),
                        ghost = ghostAlongM?.let(loaded.line::pointAt),
                        ghostHeadingDeg = ghostAlongM?.let { routeBearingAt(loaded.line, it) },
                        // Each border is a checkpoint, bright until you pass it.
                        flags = loaded.segments.drop(1).map { seg ->
                            MapFlag(loaded.line.pointAt(seg.startM), passed = onRoute && progress!!.progressM >= seg.startM)
                        },
                        youColor = SpeedColor,
                    )
                    if (gapM != null) {
                        Column(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            GapText(signedGapText(gapM), gapSign(gapM, LEVEL_M))
                            if (gapS != null) GapText(timeGapText(gapS), gapSign(gapS, LEVEL_S))
                        }
                    }
                }
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
