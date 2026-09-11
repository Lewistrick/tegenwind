package com.tegenwind.app.eta

import com.tegenwind.app.weather.WindForecast
import com.tegenwind.app.weather.WindSample
import kotlin.math.sqrt

/** What the ETA needs to know about one stretch of the route. */
data class EtaSegment(
    val startM: Double,
    val endM: Double,
    val bearingDeg: Double,
    val gradePct: Double,
    val exposure: Double,
    val signals: Int,
) {
    val lengthM: Double get() = endM - startM
}

data class Eta(
    val arrivalMs: Long,
    val remainingS: Double,
    /** One standard deviation, seconds. */
    val sigmaS: Double,
    /** Extra time the wind costs (positive) or saves (negative) compared to no wind. */
    val windCostS: Double,
)

/**
 * ETA v1: physics per segment with the wind forecast for the moment you reach it,
 * scaled by today's form, plus expected waits at traffic lights.
 */
class EtaModel(private val segments: List<EtaSegment>, private val params: RiderParams = RiderParams()) {

    /** Predicted riding speed (m/s) on [s] at form 1.0; [wind] null means no wind. */
    fun speedMps(s: EtaSegment, wind: WindSample?): Double {
        val head = if (wind == null) 0.0 else Physics.headwindMps(wind.speedMps, wind.fromDeg, s.bearingDeg, s.exposure)
        val rho = Physics.airDensity(wind?.tempC)
        return Physics.speedMps(params.powerW, s.gradePct, head, rho, params).coerceAtLeast(MIN_SPEED_MPS)
    }

    fun segmentAt(progressM: Double): EtaSegment? = segments.firstOrNull { progressM < it.endM } ?: segments.lastOrNull()

    fun predict(progressM: Double, nowMs: Long, forecast: WindForecast?, form: FormEstimate): Eta {
        var clockMs = nowMs.toDouble()
        var moving = 0.0
        var calm = 0.0
        var stops = 0.0
        var stopVar = 0.0
        for (s in segments) {
            if (s.endM <= progressM) continue
            val dist = s.endM - maxOf(s.startM, progressM)
            val wind = forecast?.at(clockMs.toLong())
            val t = dist / (speedMps(s, wind) * form.mean)
            moving += t
            calm += dist / (speedMps(s, null) * form.mean)
            clockMs += t * 1000
            // Signals are counted at the end of their segment.
            stops += s.signals * STOP_MEAN_S
            stopVar += s.signals * STOP_VAR_S2
            clockMs += s.signals * STOP_MEAN_S * 1000
        }
        val formSd = sqrt(form.variance) / form.mean
        val sigma = sqrt((moving * formSd).let { it * it } + stopVar + (moving * BASE_SD).let { it * it })
        return Eta(
            arrivalMs = (nowMs + (moving + stops) * 1000).toLong(),
            remainingS = moving + stops,
            sigmaS = sigma,
            windCostS = moving - calm,
        )
    }

    companion object {
        const val MIN_SPEED_MPS = 1.5
        /** About half the lights are red, with a ~25 s wait: ~12 s per junction on average. */
        const val STOP_MEAN_S = 12.0
        const val STOP_VAR_S2 = 15.0 * 15.0
        /** Unexplained ride-to-ride variation (traffic, bridges the elevation data misses). */
        const val BASE_SD = 0.04
    }
}

data class FormEstimate(val mean: Double, val variance: Double)

/**
 * "Today's form": how fast you ride compared to the physics model, as a speed multiplier.
 * A one-dimensional Kalman filter, updated after every completed segment.
 */
class FormEstimator(prior: Double = 1.0, priorSd: Double = 0.10) {
    private var mean = prior
    private var variance = priorSd * priorSd

    fun estimate() = FormEstimate(mean, variance)

    /**
     * [ratio] = observed moving speed / predicted speed at form 1.0 on a finished segment.
     * Returns false when the observation was rejected as implausible (e.g. after a GPS jump).
     */
    fun observe(ratio: Double): Boolean {
        val r = ratio.coerceIn(0.5, 1.6)
        if (kotlin.math.abs(r - mean) > 3 * kotlin.math.sqrt(variance + OBS_VAR)) return false
        variance += PROCESS_VAR
        val gain = variance / (variance + OBS_VAR)
        mean += gain * (r - mean)
        variance *= 1 - gain
        return true
    }

    private companion object {
        const val PROCESS_VAR = 0.0004
        /** Single segments vary a lot (a slow cyclist ahead, a bend), so each counts modestly. */
        const val OBS_VAR = 0.15 * 0.15
    }
}
