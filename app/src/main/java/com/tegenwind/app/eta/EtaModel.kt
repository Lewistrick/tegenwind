package com.tegenwind.app.eta

import com.tegenwind.app.weather.RouteWeather
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
    /** What riding it has taught us on top of the physics; see [SegmentLearner]. */
    val learned: SegmentCorrection = SegmentCorrection(),
) {
    val lengthM: Double get() = endM - startM
    val midM: Double get() = (startM + endM) / 2
}

data class Eta(
    val arrivalMs: Long,
    val remainingS: Double,
    /** One standard deviation, seconds. */
    val sigmaS: Double,
    /** Extra time the wind costs (positive) or saves (negative) compared to no wind. */
    val windCostS: Double,
    /** When you're expected halfway along each segment still ahead; null for segments already behind you. */
    val segmentMidMs: List<Long?> = emptyList(),
) {
    /** The "±" shown to the rider: half the P10–P90 range. */
    val bandS: Double get() = 1.28 * sigmaS

    companion object {
        /** Below this the band is shown in seconds rather than minutes: the ETA counts as firm. */
        const val FIRM_BAND_S = 50.0
    }
}

/**
 * ETA v2: physics per segment with the wind forecast for the moment you reach it, corrected by
 * what each segment has taught us, scaled by today's form, plus expected waits at traffic lights.
 */
class EtaModel(val segments: List<EtaSegment>, private val params: RiderParams = RiderParams()) {

    /** Predicted riding speed (m/s) on [s] at form 1.0, physics only; [wind] null means no wind. */
    fun speedMps(s: EtaSegment, wind: WindSample?): Double {
        val head = if (wind == null) 0.0 else Physics.headwindMps(wind.speedMps, wind.fromDeg, s.bearingDeg, s.exposure)
        val rho = Physics.airDensity(wind?.tempC)
        return Physics.speedMps(params.powerW, s.gradePct, head, rho, params).coerceAtLeast(MIN_SPEED_MPS)
    }

    /** The same speed with the segment's learned correction applied: what to actually expect. */
    fun correctedSpeedMps(s: EtaSegment, wind: WindSample?): Double = speedMps(s, wind) / s.learned.timeFactor

    /**
     * What the wind does to your time on [s], given its direction against your bearing and how open
     * the segment is: +0.1 saves 10% of the time it would take in still air, -0.1 costs 10% more.
     * Measured in time rather than speed because that's what a commute feels, and because in time a
     * headwind costs more than the same tailwind gives back.
     */
    fun windImpact(s: EtaSegment, wind: WindSample): Double = 1 - speedMps(s, calm(wind)) / speedMps(s, wind)

    /**
     * The same air without the wind: the temperature stays, so cold dense air on a still day
     * isn't blamed on the wind.
     */
    private fun calm(wind: WindSample?): WindSample? = wind?.copy(speedMps = 0.0)

    fun segmentAt(progressM: Double): EtaSegment? = segments.firstOrNull { progressM < it.endM } ?: segments.lastOrNull()

    /**
     * The speed the ETA assumes at [progressM] right now, m/s: physics with the wind there, what the
     * segment has learned from past passes, and today's form. Null for a model without segments.
     *
     * Each segment has a single speed, which would draw as a staircase. At a segment's middle this is
     * exactly that speed; in between it follows a smooth curve to the next one (a monotone cubic, as
     * in PCHIP): no corners at the middles, and never overshooting above the faster or below the
     * slower of two neighbours. Before the first middle and after the last it holds.
     */
    fun expectedSpeedMps(progressM: Double, nowMs: Long, weather: RouteWeather?, form: FormEstimate): Double? {
        if (segments.isEmpty()) return null
        fun speed(i: Int) = segments[i].let { s -> correctedSpeedMps(s, weather?.at(s.midM, nowMs)) * form.mean }
        val b = segments.indexOfFirst { it.midM >= progressM }
        if (b == -1) return speed(segments.lastIndex)
        if (b == 0) return speed(0)
        val a = b - 1
        // Only the speeds around the stretch between the two middles matter for its curve.
        val v = (maxOf(a - 1, 0)..minOf(b + 1, segments.lastIndex)).associateWith { speed(it) }
        val h = segments[b].midM - segments[a].midM
        val t = (progressM - segments[a].midM) / h

        /** How steep the curve runs through middle [i]: flat at the ends and at a peak or dip. */
        fun slope(i: Int): Double {
            if (i == 0 || i == segments.lastIndex) return 0.0
            val before = (v.getValue(i) - v.getValue(i - 1)) / (segments[i].midM - segments[i - 1].midM)
            val after = (v.getValue(i + 1) - v.getValue(i)) / (segments[i + 1].midM - segments[i].midM)
            // A harmonic mean: steep only where both sides are, which is what prevents overshoot.
            return if (before * after <= 0) 0.0 else 2 / (1 / before + 1 / after)
        }

        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * v.getValue(a) + (t3 - 2 * t2 + t) * h * slope(a) +
            (-2 * t3 + 3 * t2) * v.getValue(b) + (t3 - t2) * h * slope(b)
    }

    /** [weather] gives each segment the forecast nearest to it, for the moment you're expected there. */
    fun predict(progressM: Double, nowMs: Long, weather: RouteWeather?, form: FormEstimate): Eta {
        var clockMs = nowMs.toDouble()
        var moving = 0.0
        var calm = 0.0
        var stops = 0.0
        var stopVar = 0.0
        var segVar = 0.0
        val midMs = arrayOfNulls<Long>(segments.size)
        for ((i, s) in segments.withIndex()) {
            if (s.endM <= progressM) continue
            val dist = s.endM - maxOf(s.startM, progressM)
            val wind = weather?.at(s.midM, clockMs.toLong())
            val t = dist / (correctedSpeedMps(s, wind) * form.mean)
            moving += t
            calm += dist / (correctedSpeedMps(s, calm(wind)) * form.mean)
            midMs[i] = (clockMs + t * 500).toLong()
            clockMs += t * 1000
            // How sure we are of this segment's own correction. Segments are unrelated to each
            // other, so unlike form these errors partly cancel out over a long route.
            segVar += t * t * s.learned.logVar
            // Signals are counted at the end of their segment.
            stops += s.signals * STOP_MEAN_S
            stopVar += s.signals * STOP_VAR_S2
            clockMs += s.signals * STOP_MEAN_S * 1000
        }
        val formSd = sqrt(form.variance) / form.mean
        val sigma = sqrt(
            (moving * formSd).let { it * it } + stopVar + segVar + (moving * BASE_SD).let { it * it }
        )
        return Eta(
            arrivalMs = (nowMs + (moving + stops) * 1000).toLong(),
            remainingS = moving + stops,
            sigmaS = sigma,
            windCostS = moving - calm,
            segmentMidMs = midMs.toList(),
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
