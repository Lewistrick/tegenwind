package com.tegenwind.app.eta

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** What one 25 m stretch of a route has taught: log of its speed relative to its segment, and from how many rides. */
data class ProfileBin(val logShape: Double, val rides: Int)

/**
 * How speed varies *within* the segments of a route: a bridge ramp or a blind corner rides slower
 * than the rest of its segment, the way down off a bridge faster. Learned from rides, per 25 m, as a
 * factor on the segment's own speed.
 *
 * It only shapes the expected-speed line. Each segment's time, and so the ETA, stays exactly what the
 * segment model says: [factorAt] rescales the shape so riding a whole segment takes just as long as
 * its flat speed would. The segment corrections already absorb the slow spots in their totals.
 */
class SpeedProfile(bins: Map<Int, ProfileBin>) {
    private val shape: Map<Int, Double> = bins.mapValues { exp(it.value.logShape) }
    private val norms = HashMap<Double, Double>()

    val isEmpty: Boolean get() = shape.isEmpty()

    /** The learned shape at [alongM], interpolated between the middles of the 25 m bins; 1 where unknown. */
    fun shapeAt(alongM: Double): Double {
        val pos = alongM / BIN_M - 0.5
        val i = floor(pos).toInt()
        val f = pos - i
        return (shape[i] ?: 1.0) * (1 - f) + (shape[i + 1] ?: 1.0) * f
    }

    /**
     * The factor for the expected speed at [alongM] inside the segment from [startM] to [endM]: the
     * shape, divided by its harmonic mean over the segment, so the segment's time doesn't change.
     */
    fun factorAt(alongM: Double, startM: Double, endM: Double): Double {
        val norm = norms.getOrPut(startM) {
            var n = 0
            var inverse = 0.0
            var m = startM + STEP_M / 2
            while (m < endM) {
                inverse += 1 / shapeAt(m)
                n++
                m += STEP_M
            }
            if (n == 0) 1.0 else n / inverse
        }
        return shapeAt(alongM) / norm
    }

    companion object {
        const val BIN_M = 25.0
        /** How finely a segment is sampled to rescale it. */
        private const val STEP_M = 5.0
    }
}

/**
 * Learns a route's [SpeedProfile] from rides.
 *
 * For one ride: moving speed per 25 m, divided by that ride's own average over the segment the bin
 * lies in. That leaves the *shape* of the speed inside each segment, free of the day's form and wind.
 * Folded into what the route knew as a running mean in log space, which after a few rides becomes a
 * fading average of about the last [1 / MIN_WEIGHT] rides, so it follows road works.
 */
object SpeedProfileLearner {
    /** Below this you're stopping or starting, not riding the road: those fixes don't count. */
    const val MIN_MOVING_KMH = 4.0

    /** A bin needs this many moving fixes from one ride to say anything. */
    const val MIN_FIXES = 2

    /** A segment's own average needs this many bins of the same ride. */
    const val MIN_BINS = 5

    /** One ride never claims a stretch is more than ~40% slower or ~65% faster than its segment. */
    const val MAX_LOG = 0.5

    /** A new ride always counts for at least this much, so old rides fade. */
    const val MIN_WEIGHT = 0.2

    /** One fix on the route: how far along, and how fast. */
    data class Pass(val alongM: Double, val kmh: Double)

    /**
     * One ride's shape: per bin, its median moving speed over its average across the segment the bin
     * lies in. [segments] are the route's (start, end) in metres. Bins without enough data are left out.
     */
    fun rideShape(passes: List<Pass>, segments: List<Pair<Double, Double>>): Map<Int, Double> {
        val perBin = passes.filter { it.kmh >= MIN_MOVING_KMH }
            .groupBy { floor(it.alongM / SpeedProfile.BIN_M).toInt() }
            .filterValues { it.size >= MIN_FIXES }
            .mapValues { (_, ps) -> median(ps.map { it.kmh }) }
        val out = HashMap<Int, Double>()
        for ((start, end) in segments) {
            val inSeg = perBin.filterKeys { bin -> ((bin + 0.5) * SpeedProfile.BIN_M).let { it >= start && it < end } }
            if (inSeg.size < MIN_BINS) continue
            // Distance over time: the average a segment's travel time actually follows.
            val average = inSeg.size / inSeg.values.sumOf { 1 / it }
            inSeg.forEach { (bin, kmh) -> out[bin] = kmh / average }
        }
        return out
    }

    /** Folds one ride's [ride] shape into what the route knew. */
    fun update(known: Map<Int, ProfileBin>, ride: Map<Int, Double>): Map<Int, ProfileBin> {
        val out = HashMap(known)
        ride.forEach { (bin, shape) ->
            val observed = ln(shape).coerceIn(-MAX_LOG, MAX_LOG)
            val was = known[bin]
            out[bin] = if (was == null) {
                ProfileBin(observed, 1)
            } else {
                val w = maxOf(1.0 / (was.rides + 1), MIN_WEIGHT)
                ProfileBin(was.logShape + w * (observed - was.logShape), was.rides + 1)
            }
        }
        return out
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
