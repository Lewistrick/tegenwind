package com.tegenwind.app.eta

import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.weather.RouteWeather
import com.tegenwind.app.weather.WindSample

/** The wind as it affects you right now, for the ride screen. */
data class WindNow(
    val speed10Mps: Double,
    val fromDeg: Double,
    /** At rider height, positive against you. */
    val headwindMps: Double,
    /** Direction the wind blows towards, relative to your heading: 0 = pushing you forward. */
    val relativeDeg: Double,
    val exposure: Double,
) {
    val label: String
        get() = when {
            speed10Mps < 1.5 -> "Little wind"
            kotlin.math.abs(kotlin.math.cos(Math.toRadians(relativeDeg))) < 0.4 -> "Crosswind"
            headwindMps > 0 -> "Headwind"
            else -> "Tailwind"
        }
}

data class LiveEta(
    val eta: Eta,
    val form: Double,
    val wind: WindNow?,
    /** Your usual form going into this ride (see [usualForm]); null without earlier rides to say. */
    val usualForm: Double? = null,
) {
    /** Today's form as a share of your usual: see [pace]. */
    val pace: Double? get() = usualForm?.let { pace(form, it) }
}

fun LoadedRoute.etaModel(learned: RiderLearned = RiderLearned(), params: RiderParams = RiderParams()) = EtaModel(
    segments.map { s ->
        EtaSegment(
            startM = s.startM,
            endM = s.endM,
            bearingDeg = s.bearingDeg,
            // Until the lookup has finished: flat, half-sheltered, no lights.
            gradePct = s.gradePct ?: 0.0,
            exposure = s.exposure ?: UNKNOWN_EXPOSURE,
            signals = learned.lights(route.id, s.idx, s.signals ?: 0),
            learned = SegmentCorrection(s.learnedLogMean, s.learnedLogVar, s.learnedPasses),
            stop = learned.stop(route.id, s.idx, s.signals ?: 0),
        )
    },
    params,
    learned,
)

fun windNow(model: EtaModel, progressM: Double, nowMs: Long, weather: RouteWeather?): WindNow? {
    val seg = model.segmentAt(progressM) ?: return null
    val w = weather?.at(progressM, nowMs) ?: return null
    return windAlong(seg.bearingDeg, w, seg.exposure)
}

/**
 * Wind [w] as it meets you riding along [headingDeg]. Without a route there's no map data to say how
 * sheltered you are, so [exposure] defaults to the same half-open guess used before a route's lookup.
 */
fun windAlong(headingDeg: Double, w: WindSample, exposure: Double = UNKNOWN_EXPOSURE): WindNow =
    WindNow(
        speed10Mps = w.speedMps,
        fromDeg = w.fromDeg,
        headwindMps = Physics.headwindMps(w.speedMps, w.fromDeg, headingDeg, exposure),
        relativeDeg = ((w.fromDeg + 180 - headingDeg) % 360 + 360) % 360,
        exposure = exposure,
    )

const val UNKNOWN_EXPOSURE = 0.6

/**
 * Starting guess for today's form: the median of recent rides, or 1.0 without history. Clamped to
 * the range a segment's observation can have ([FormEstimator.observe]), no tighter, so moving what
 * all segments share into form (see [SegmentLearner.sharedTilt]) is never cut short.
 */
fun priorForm(recent: List<Double>): Double =
    if (recent.isEmpty()) 1.0 else recent.sorted()[recent.size / 2].coerceIn(0.5, 1.6)

/** How many recent rides make up "your usual": the same ones [priorForm] starts each ride from. */
const val USUAL_RIDES = 10

/** Your usual form: the median of your recent rides' form, or null when no ride has one yet. */
fun usualForm(recent: List<Double>): Double? = if (recent.isEmpty()) null else priorForm(recent)

/**
 * The usual form a ride was compared with: [usualForm] of the up to [USUAL_RIDES] rides with a form
 * that started before it. [forms] pairs each real ride's start time with its form.
 */
fun usualFormBefore(forms: List<Pair<Long, Double>>, startedAtMs: Long): Double? =
    usualForm(forms.filter { it.first < startedAtMs }.sortedByDescending { it.first }.take(USUAL_RIDES).map { it.second })

/**
 * Pace: [form] as a share of your [usual] form. 1.0 is your usual speed for the conditions, 0.5 half
 * as fast, 2.0 twice as fast. Form itself is measured against the model rider; pace is against you.
 */
fun pace(form: Double, usual: Double): Double = form / usual

/** Pace as shown: "103%". Null when either is unknown. */
fun pacePercent(form: Double?, usual: Double?): String? =
    if (form == null || usual == null || usual <= 0) null else "%d%%".format(Math.round(pace(form, usual) * 100))
