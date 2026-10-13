package com.tegenwind.app.ride

/** What the ETA said at [timeMs]: the arrival time, and the "±" around it ([bandMs], see Eta.bandS). */
data class ArrivalPoint(val timeMs: Long, val arrivalMs: Long, val bandMs: Long)

/**
 * How the predicted arrival and its band moved over the ride, a point every 5 s, for the ride
 * screen's arrival chart. Kept in memory only; never stored.
 */
class ArrivalTrail {
    private val kept = ArrayList<ArrivalPoint>()

    /** Oldest first. */
    val points: List<ArrivalPoint> get() = kept.toList()

    /** Adds a point when 5 s have passed since the last one. */
    fun record(nowMs: Long, arrivalMs: Long, bandMs: Long) {
        if (kept.isNotEmpty() && nowMs - kept.last().timeMs < EVERY_MS) return
        kept += ArrivalPoint(nowMs, arrivalMs, bandMs)
    }

    fun clear() = kept.clear()

    companion object {
        const val EVERY_MS = 5_000L
    }
}
