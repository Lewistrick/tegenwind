package com.tegenwind.app.ride

import kotlin.math.ceil

/** Where a ride's time falls among the rides on its route. */
sealed interface RideRank {
    /** Among the fastest [percent]% of the rides. */
    data class Top(val percent: Int) : RideRank

    /** Among the slowest [percent]%. */
    data class Bottom(val percent: Int) : RideRank

    /** In the middle: neither the fastest nor the slowest half says anything. */
    data object Middle : RideRank

    companion object {
        /** With fewer rides on a route than this, a ranking says too little. */
        const val MIN_RIDES = 5

        /**
         * Where [durationMs] falls among [all] the times on its route, this ride's own included: in
         * the fastest x% or the slowest x%, whichever is under half. Ties count in the ride's favour.
         * Null with too few rides to say.
         */
        fun of(durationMs: Long, all: List<Long>): RideRank? {
            if (all.size < MIN_RIDES) return null
            val top = ceil((all.count { it < durationMs } + 1) * 100.0 / all.size).toInt()
            val bottom = ceil((all.count { it > durationMs } + 1) * 100.0 / all.size).toInt()
            return when {
                top < 50 -> Top(top)
                bottom < 50 -> Bottom(bottom)
                else -> Middle
            }
        }
    }
}
