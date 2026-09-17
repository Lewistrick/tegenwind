package com.tegenwind.app.eta

import kotlin.math.exp
import kotlin.math.ln

/** What one segment has taught us so far, on top of the physics baseline. */
data class SegmentCorrection(
    /** Posterior mean of log(actual / physics moving time). Positive = slower than physics says. */
    val logMean: Double = 0.0,
    val logVar: Double = SegmentLearner.PRIOR_VAR,
    val passes: Int = 0,
) {
    /** Multiply a physics moving time by this to get what the segment really costs. */
    val timeFactor: Double get() = exp(logMean.coerceIn(-SegmentLearner.MAX_LOG, SegmentLearner.MAX_LOG))
}

/**
 * Layer 2 of the ETA model: every clean pass over a segment teaches it how much slower or faster
 * it really is than the physics says, and the ETA leans on that instead of on physics alone.
 *
 * The update is the same conjugate Normal step as [FormEstimator], on log(actual / predicted):
 * cheap, needs no retraining, and its variance says how much the number can still be trusted.
 * Standing still is not in the input — traffic lights are modelled separately — so what is left
 * is how the road itself rides: surface, bends, junctions, cyclists in the way.
 */
object SegmentLearner {
    /** Before the first pass a segment could plausibly be ~20% off either way. */
    const val PRIOR_VAR = 0.18 * 0.18

    /** A single pass is noisy: one lorry or one bunched-up light easily costs a quarter of it. */
    const val OBS_VAR = 0.25 * 0.25

    /** Old passes fade, so the model follows road works and seasons instead of averaging over them. */
    const val FORGET = 0.98

    /** Never let a segment claim to be more than about twice as slow or fast as physics says. */
    const val MAX_LOG = 0.7

    /** Below this the pass is too short to say anything: GPS noise dominates. */
    const val MIN_MS = 5_000L

    /**
     * Folds one pass into what the segment knew. [actualMovingMs] is time spent moving,
     * [physicsMovingMs] the prediction at form 1.0 before any correction.
     * Returns the prior unchanged when the pass is too short or the prediction is nonsense.
     */
    fun update(prior: SegmentCorrection, actualMovingMs: Long, physicsMovingMs: Long): SegmentCorrection {
        if (actualMovingMs < MIN_MS || physicsMovingMs <= 0) return prior
        val observed = ln(actualMovingMs.toDouble() / physicsMovingMs).coerceIn(-MAX_LOG, MAX_LOG)
        // Letting the variance grow back a little is what makes old passes fade.
        val v = prior.logVar / FORGET
        val gain = v / (v + OBS_VAR)
        return SegmentCorrection(
            logMean = prior.logMean + gain * (observed - prior.logMean),
            logVar = v * (1 - gain),
            passes = prior.passes + 1,
        )
    }
}
