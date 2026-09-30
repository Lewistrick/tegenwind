package com.tegenwind.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

// Night palette from the prototype (docs/prototype/index.html): asphalt greys, reflector amber.
val Asphalt = Color(0xFF0D1113)
val Surface1 = Color(0xFF151B1E)
val Surface2 = Color(0xFF1D2528)
val Line = Color(0xFF2A3438)
val Ink = Color(0xFFEDF2F0)
val InkMuted = Color(0xFF8E9A9E)
val Amber = Color(0xFFF2B53A)
val AmberInk = Color(0xFF241800)
/** Amber dimmed into the dark: the background of a selected tab. */
val AmberDim = Color(0xFF3B2F17)
val Danger = Color(0xFFFF6A5C)

/*
 * What colours mean, everywhere in the app:
 * amber  = the accent: actions, the wind arrow, what the model expects, traffic lights
 * blue   = speed, and nothing else
 * green  = good: faster, a tailwind, the fastest ride, the top x% of a route's rides
 * red    = bad or dangerous: slower, a headwind, off route, the bottom x%, delete (and heart rate as a series)
 * grey   = everything descriptive, such as how open a stretch of road is
 */

/** Series colors: speed and heart rate always look the same across screens. */
val SpeedColor = Color(0xFF3FB6FF)
val HeartColor = Color(0xFFFF5B4F)
val GoodColor = Color(0xFF48D695)

/*
 * The red–yellow–green scale, for how much something costs or gains along a route: the wind on the
 * ride screen's route bar, and a segment's learned time against physics on a route's page.
 */
val ScaleWorse = Color(0xFFFF3B30)
val ScaleEven = Color(0xFFFFD60A)
val ScaleBetter = Color(0xFF30D158)

/** Yellow at 0, turning green towards +1 (gains) and red towards −1 (costs); beyond that it stays put. */
fun scaleColor(amount: Double): Color =
    lerp(ScaleEven, if (amount < 0) ScaleWorse else ScaleBetter, abs(amount).toFloat().coerceIn(0f, 1f))
