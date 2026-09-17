package com.tegenwind.app.eta

import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.weather.WindForecast

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

data class LiveEta(val eta: Eta, val form: Double, val wind: WindNow?)

fun LoadedRoute.etaModel(params: RiderParams = RiderParams()) = EtaModel(
    segments.map { s ->
        EtaSegment(
            startM = s.startM,
            endM = s.endM,
            bearingDeg = s.bearingDeg,
            // Until the lookup has finished: flat, half-sheltered, no lights.
            gradePct = s.gradePct ?: 0.0,
            exposure = s.exposure ?: 0.6,
            signals = s.signals ?: 0,
            learned = SegmentCorrection(s.learnedLogMean, s.learnedLogVar, s.learnedPasses),
        )
    },
    params,
)

fun windNow(model: EtaModel, progressM: Double, nowMs: Long, forecast: WindForecast?): WindNow? {
    val seg = model.segmentAt(progressM) ?: return null
    val w = forecast?.at(nowMs) ?: return null
    return WindNow(
        speed10Mps = w.speedMps,
        fromDeg = w.fromDeg,
        headwindMps = Physics.headwindMps(w.speedMps, w.fromDeg, seg.bearingDeg, seg.exposure),
        relativeDeg = ((w.fromDeg + 180 - seg.bearingDeg) % 360 + 360) % 360,
        exposure = seg.exposure,
    )
}

/** Starting guess for today's form: the median of recent rides, or 1.0 without history. */
fun priorForm(recent: List<Double>): Double =
    if (recent.isEmpty()) 1.0 else recent.sorted()[recent.size / 2].coerceIn(0.7, 1.4)
