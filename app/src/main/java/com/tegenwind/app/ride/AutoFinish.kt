package com.tegenwind.app.ride

/**
 * Ends a ride by itself once you reach the end of the route, after a short countdown
 * so a pass close to the finish doesn't cut the ride short. Free rides have no known
 * destination, so they always end with the Stop button.
 */
object AutoFinish {
    /** Close enough to the end to call it arrived. */
    const val WITHIN_M = 30.0

    /** Countdown before the ride ends, long enough to tap "Keep riding". */
    const val COUNTDOWN_MS = 15_000L

    fun arrived(remainingM: Double): Boolean = remainingM <= WITHIN_M

    /** Whole seconds still to go, rounded up: 15 at arrival, 0 when the ride ends. */
    fun secondsLeft(arrivedAtMs: Long, nowMs: Long): Int {
        val leftMs = ((arrivedAtMs + COUNTDOWN_MS) - nowMs).coerceAtLeast(0L)
        return ((leftMs + 999) / 1000).toInt()
    }

    fun dueToStop(arrivedAtMs: Long, nowMs: Long): Boolean = nowMs - arrivedAtMs >= COUNTDOWN_MS
}
