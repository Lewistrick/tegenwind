package com.tegenwind.app.ride

/** A short piece of the route just ridden: from [fromM] to [toM] along it, in [movingMs] of moving time. */
data class Stretch(val fromM: Double, val toM: Double, val movingMs: Long) {
    val lengthM: Double get() = toM - fromM
}

/**
 * Cuts a ride along a route into stretches of about [everyMs] of riding, so today's form can be
 * measured every few seconds instead of once per segment. Cut by time rather than by distance: the
 * GPS's error is in where you are, so over a fixed time it lands evenly on either side of the speed
 * measured, where over a fixed distance it would favour "slow".
 *
 * A stretch only counts when it was ridden in one go along the route: after a gap in the GPS, a
 * step backwards or leaving the route, the next one starts afresh from where you are.
 */
class StretchClock(private val everyMs: Long = EVERY_MS) {
    private var fromM = Double.NaN
    private var fromMovingMs = 0L
    private var lastM = 0.0

    /** Forget the stretch being measured: off the route, or on another route. */
    fun reset() {
        fromM = Double.NaN
    }

    /** Give every on-route fix. Returns the stretch this fix completes, if it does. */
    fun advance(progressM: Double, movingMs: Long): Stretch? {
        val broken = fromM.isNaN() || progressM < lastM - BACK_M || progressM - lastM > JUMP_M
        lastM = progressM
        if (broken) {
            fromM = progressM
            fromMovingMs = movingMs
            return null
        }
        // Crawling up to a light: wait until there's enough road to measure a speed on.
        if (movingMs - fromMovingMs < everyMs || progressM - fromM < MIN_M) return null
        val done = Stretch(fromM, progressM, movingMs - fromMovingMs)
        fromM = progressM
        fromMovingMs = movingMs
        return done
    }

    companion object {
        const val EVERY_MS = 5_000L
        const val MIN_M = 10.0
        /** Further than this between two fixes is a gap in the GPS, not riding. */
        const val JUMP_M = 60.0
        /** GPS noise can step back a little; more than this and the stretch isn't one ride along the route. */
        const val BACK_M = 15.0
    }
}
