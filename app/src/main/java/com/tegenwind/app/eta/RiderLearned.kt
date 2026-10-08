package com.tegenwind.app.eta

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the app has learned about how you ride, from your own rides, for the parts of the ETA that
 * used to be fixed guesses. Each value starts at the old guess and moves to what the data says as
 * rides come in (see [RiderFit]); with no rides it is the old guess.
 */
data class RiderLearned(
    /** The share of the modelled headwind that you actually ride against: 1 = as physics says. */
    val windFeel: Double = 1.0,
    /** How far your form on a ride lands from the starting guess for it, as a fraction. */
    val formSd: Double = DEFAULT_FORM_SD,
    /** How far one segment pass ends up from what was expected of it, as a fraction. Feeds form's updates. */
    val passNoise: Double = DEFAULT_PASS_NOISE,
    /** The part of [passNoise] the segment's own learned value doesn't already account for. */
    val passExtra: Double = DEFAULT_PASS_EXTRA,
    /**
     * How much your form drifts from one segment to the next, as a variance of the fraction. It
     * decides how fast the form estimate forgets what it measured earlier in the ride.
     */
    val formDrift: Double = DEFAULT_FORM_DRIFT,
    /** Time standing still per traffic light on the route, on average, and its variance. */
    val stopMeanS: Double = 12.0,
    val stopVarS2: Double = 15.0 * 15.0,
    /** How many rides these came from, for the log. */
    val rides: Int = 0,
) {
    companion object {
        const val DEFAULT_FORM_SD = 0.10
        const val DEFAULT_PASS_NOISE = 0.15
        const val DEFAULT_PASS_EXTRA = 0.10
        const val DEFAULT_FORM_DRIFT = 0.0004
    }
}

/** One segment pass, as the wind fit needs it. */
class WindPass(
    val rideId: Long,
    val segmentKey: Long,
    val lengthM: Double,
    val gradePct: Double,
    /** Forecast headwind at rider height as the pass began: positive = against you. */
    val headwindMps: Double,
    val movingS: Double,
)

/** One ride, for the fits of form spread and of time spent standing still. */
class RideFacts(
    val formFactor: Double?,
    val stoppedS: Double,
    val signals: Int,
)

/** One segment pass, for learning how much a pass strays from what was expected of it. */
class PassFacts(val movingMs: Long, val expectedMs: Long)

/**
 * The fits behind [RiderLearned]. All of them are recomputed from the stored rides after every ride,
 * so they follow how you ride now, and each is pulled toward the old guess in proportion to how
 * little data there is.
 */
object RiderFit {
    /** Before it has seen enough, the wind fit answers with 1: physics as it was. */
    const val MIN_WIND_PASSES = 150
    const val MIN_WIND_RIDES = 6

    private const val WIND_PRIOR_SD = 0.5
    private val GRID = (0..30).map { it * 0.05 }

    /**
     * The share of the modelled headwind that shows in your riding. Every pass is compared with what
     * physics predicts at each candidate share, after taking out what each segment usually costs and
     * what each ride's pace was: what is left over is how well the wind alone explains the
     * differences between passes of the same segment on the same ride. The share with the least
     * left over wins, pulled gently toward 1 so a handful of rides can't swing it.
     */
    fun windFeel(passes: List<WindPass>, params: RiderParams = RiderParams()): Double {
        val rho = Physics.airDensity(null)
        fun predictedS(p: WindPass, k: Double): Double {
            val v = Physics.speedMps(params.powerW, p.gradePct, p.headwindMps * k, rho, params)
            return p.lengthM / v.coerceAtLeast(EtaModel.MIN_SPEED_MPS)
        }
        // Passes with a stop in the middle, or a GPS slip, say nothing about the wind.
        val usable = passes.filter { it.movingS >= 8 && it.lengthM > 0 }
            .filter { it.movingS in predictedS(it, 1.0) / 3..predictedS(it, 1.0) * 3 }
        if (usable.size < MIN_WIND_PASSES || usable.map { it.rideId }.distinct().size < MIN_WIND_RIDES) return 1.0
        val rideIdx = usable.map { it.rideId }.distinct().withIndex().associate { it.value to it.index }
        val segIdx = usable.map { it.segmentKey }.distinct().withIndex().associate { it.value to it.index }
        val ride = IntArray(usable.size) { rideIdx.getValue(usable[it].rideId) }
        val seg = IntArray(usable.size) { segIdx.getValue(usable[it].segmentKey) }

        val sse = GRID.map { k ->
            val y = DoubleArray(usable.size) { i -> ln(usable[i].movingS / predictedS(usable[i], k)) }
            demean(y, ride, rideIdx.size, seg, segIdx.size)
            y.sumOf { it * it }
        }
        val sigma2 = max(sse.min() / usable.size, 1e-6)
        var best = 1.0
        var bestCost = Double.MAX_VALUE
        for ((i, k) in GRID.withIndex()) {
            val cost = sse[i] / (2 * sigma2) + (k - 1) * (k - 1) / (2 * WIND_PRIOR_SD * WIND_PRIOR_SD)
            if (cost < bestCost) {
                bestCost = cost
                best = k
            }
        }
        return best
    }

    /** Takes out each ride's mean and each segment's mean, alternately until they settle. */
    private fun demean(y: DoubleArray, a: IntArray, na: Int, b: IntArray, nb: Int) {
        repeat(25) {
            for ((key, n) in listOf(a to na, b to nb)) {
                val sum = DoubleArray(n)
                val count = IntArray(n)
                for (i in y.indices) {
                    sum[key[i]] += y[i]
                    count[key[i]]++
                }
                for (i in y.indices) y[i] -= sum[key[i]] / count[key[i]]
            }
        }
    }

    /**
     * How far a ride's form lands from the starting guess for it ([priorForm] of the rides before),
     * as a root mean square over the latest rides. Shrunk toward the old guess by the weight of a
     * couple of rides, so a short history can't claim certainty.
     */
    fun formSd(formsOldestFirst: List<Double>): Double {
        val errors = ArrayList<Double>()
        for (i in MIN_HISTORY until formsOldestFirst.size) {
            val previous = formsOldestFirst.subList(max(0, i - RECENT), i)
            errors += ln(formsOldestFirst[i] / priorForm(previous))
        }
        val latest = errors.takeLast(WINDOW)
        val v = (latest.sumOf { it * it } + PSEUDO * RiderLearned.DEFAULT_FORM_SD.sq()) / (latest.size + PSEUDO)
        return sqrt(v).coerceIn(0.02, 0.15)
    }

    /**
     * How far one segment pass lands from what was expected of it (the ride's form and the
     * segment's learned time applied), and the part of that the segments' own uncertainty
     * ([meanSegmentLogVar]) doesn't explain. Shrunk toward the old guesses by the weight of a few
     * passes.
     */
    fun passNoise(passes: List<PassFacts>, meanSegmentLogVar: Double): Pair<Double, Double> {
        val r = passes.filter { it.movingMs >= 5_000 && it.expectedMs > 0 }
            .map { ln(it.movingMs.toDouble() / it.expectedMs).coerceIn(-0.7, 0.7) }
        val v = (r.sumOf { it * it } + PASS_PSEUDO * RiderLearned.DEFAULT_PASS_NOISE.sq()) / (r.size + PASS_PSEUDO)
        val noise = sqrt(v).coerceIn(0.05, 0.4)
        val extra = sqrt(max(v - meanSegmentLogVar, 0.03 * 0.03))
        return noise to min(extra, noise)
    }

    /**
     * How much form drifts per segment within a ride. Passes are grouped in runs of [DRIFT_BLOCK];
     * if form never moved, the runs' averages would vary only by the pass noise divided by the run
     * length, and whatever they vary more than that is drift. [rides] holds each ride's passes in
     * order, as how far each strayed from what was expected (log). Shrunk toward the old guess by
     * the weight of a few dozen runs.
     */
    fun formDrift(rides: List<List<Double>>): Double {
        val all = rides.flatten()
        if (all.size < 2) return RiderLearned.DEFAULT_FORM_DRIFT
        val mean = all.average()
        val noise = all.sumOf { (it - mean).sq() } / all.size
        val blocks = rides.flatMap { r -> r.chunked(DRIFT_BLOCK).filter { it.size == DRIFT_BLOCK }.map { it.average() } }
        if (blocks.isEmpty()) return RiderLearned.DEFAULT_FORM_DRIFT
        val blockMean = blocks.average()
        val spread = blocks.sumOf { (it - blockMean).sq() } / blocks.size
        val perSegment = max(spread - noise / DRIFT_BLOCK, 0.0) / DRIFT_BLOCK
        val v = (perSegment * blocks.size + DRIFT_PSEUDO * RiderLearned.DEFAULT_FORM_DRIFT) / (blocks.size + DRIFT_PSEUDO)
        return v.coerceIn(0.00001, 0.002)
    }

    /**
     * Time standing still per traffic light on the route: total stopped time over total lights
     * passed, and the spread around that. Stopped time is a ride's length minus its moving time, so
     * it includes anything that made you stop, not just lights. Shrunk toward the old guess.
     */
    fun stops(rides: List<RideFacts>): Pair<Double, Double> {
        val used = rides.filter { it.signals > 0 && it.stoppedS >= 0 }
        val lights = used.sumOf { it.signals }.toDouble()
        val mean = (used.sumOf { it.stoppedS } + STOP_PSEUDO * 12.0) / (lights + STOP_PSEUDO)
        val spread = used.sumOf { (it.stoppedS - mean * it.signals).sq() }
        val variance = (spread + STOP_PSEUDO * 225.0) / (lights + STOP_PSEUDO)
        return mean to variance
    }

    /** [rides] oldest first. */
    fun learn(
        windPasses: List<WindPass>,
        rides: List<RideFacts>,
        passes: List<PassFacts>,
        meanSegmentLogVar: Double,
        passesByRide: List<List<Double>> = emptyList(),
        params: RiderParams = RiderParams(),
    ): RiderLearned {
        val (noise, extra) = passNoise(passes, meanSegmentLogVar)
        val (stopMean, stopVar) = stops(rides)
        return RiderLearned(
            windFeel = windFeel(windPasses, params),
            formSd = formSd(rides.mapNotNull { it.formFactor }),
            passNoise = noise,
            passExtra = extra,
            formDrift = formDrift(passesByRide),
            stopMeanS = stopMean,
            stopVarS2 = stopVar,
            rides = rides.size,
        )
    }

    private const val DRIFT_BLOCK = 9
    private const val DRIFT_PSEUDO = 20.0
    private const val MIN_HISTORY = 3
    private const val RECENT = 10
    private const val WINDOW = 20
    private const val PSEUDO = 2
    private const val PASS_PSEUDO = 20
    private const val STOP_PSEUDO = 10.0
    private fun Double.sq() = this * this
}
