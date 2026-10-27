package com.tegenwind.app.ride

/**
 * When you really leave after tapping Start. Standing at the door, GPS positions wander and its
 * speed reads a few km/h, so neither one far-off position nor GPS speed can say you left.
 *
 * You have set off once an accurate position lies further from where you stood than both
 * positions' accuracy plus [AWAY_M]. The ride then begins at the last position where you were
 * still standing, and every position since belongs to it, so the first metres aren't lost.
 */
class SetOff {
    /** Where you stand: the sharpest position seen while standing. */
    private var anchor: Fix? = null

    /** The accurate positions since tapping Start, oldest first. */
    private val seen = ArrayList<Fix>()

    /** Null while you're still at the start. Once you've left: the positions the ride is made of so far, oldest first. */
    fun add(fix: Fix): List<Fix>? {
        val accuracy = fix.accuracyM ?: 0.0
        if (accuracy > RideTracker.MAX_ACCURACY_M) return null
        seen += fix
        // Pacing about near the start for a long time: forget the oldest.
        if (seen.size > MAX_KEPT) seen.removeAt(0)
        val a = anchor ?: run {
            anchor = fix
            return null
        }
        val awayM = distanceM(a, fix)
        val blurM = (a.accuracyM ?: 0.0) + accuracy
        if (awayM >= blurM + AWAY_M) {
            // The ride begins at the last position at the spot you stood on, as far as GPS can tell:
            // a tighter circle would let a wandering position put the start minutes too early.
            val from = seen.indexOfLast { distanceM(a, it) <= maxOf(STILL_M, (a.accuracyM ?: 0.0) + (it.accuracyM ?: 0.0)) }
                .coerceAtLeast(0)
            return seen.subList(from, seen.size).toList()
        }
        // Still where you stood. A sharper position becomes the spot to measure from.
        if (awayM <= blurM && accuracy < (a.accuracyM ?: 0.0)) anchor = fix
        return null
    }

    fun reset() {
        anchor = null
        seen.clear()
    }

    private fun distanceM(a: Fix, b: Fix) = haversineM(a.lat, a.lon, b.lat, b.lon)

    companion object {
        /** Within this of where you stood (or within the positions' accuracy), you're still there. */
        const val STILL_M = 5.0

        /** This far (beyond the positions' accuracy) from where you stood, you have left. */
        const val AWAY_M = 15.0

        /** About ten minutes of positions. */
        const val MAX_KEPT = 600
    }
}
