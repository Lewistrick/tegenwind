package com.tegenwind.app.ride

/** One point of the form-against-arrival trail: what the ETA and the form estimate said at [timeMs]. */
data class FormDot(val timeMs: Long, val arrivalMs: Long, val form: Double)

/**
 * The last minute of the ETA's arrival time and the form behind it, a dot every 5 s, for the ride
 * screen's scatterplot. Kept in memory only; never stored.
 */
class FormTrail {
    private val kept = ArrayList<FormDot>()

    /** Oldest first. */
    val dots: List<FormDot> get() = kept.toList()

    /** Adds a dot when 5 s have passed since the last one, and lets the ones older than a minute go. */
    fun record(nowMs: Long, arrivalMs: Long, form: Double) {
        if (kept.isNotEmpty() && nowMs - kept.last().timeMs < EVERY_MS) return
        kept += FormDot(nowMs, arrivalMs, form)
        kept.removeAll { nowMs - it.timeMs > SPAN_MS }
    }

    fun clear() = kept.clear()

    companion object {
        const val EVERY_MS = 5_000L
        const val SPAN_MS = 60_000L
    }
}
