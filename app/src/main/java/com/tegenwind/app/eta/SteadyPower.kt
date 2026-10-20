package com.tegenwind.app.eta

import com.tegenwind.app.data.SegmentTraversalEntity

/** One stretch of a ride: how far, how steep, the headwind against you there (m/s) and how long you moved. */
data class RiddenStretch(val lengthM: Double, val gradePct: Double, val headwindMps: Double, val movingS: Double)

/**
 * Your effort in watts, wind and slope taken out: the steady pedal power that would carry you over
 * the stretches you rode in the time you took, with the wind as forecast there (all of it: the
 * physics' own). A calm ride and a ride into a headwind both say how hard you pushed, so rides can be
 * compared, and the expected ride holds your usual effort against today's wind.
 */
object SteadyPower {
    /** Less than this much ridden says too little about effort. */
    const val MIN_M = 750.0

    /** Null on a bike that rides itself, or with too little ridden. */
    fun watts(stretches: List<RiddenStretch>, params: RiderParams): Double? {
        if (params.motorFull) return null
        val ridden = stretches.filter { it.lengthM > 0 && it.movingS > 0 }
        if (ridden.sumOf { it.lengthM } < MIN_M) return null
        val total = ridden.sumOf { it.movingS }
        val rho = Physics.airDensity(null)
        fun timeAt(powerW: Double) = ridden.sumOf { it.lengthM / Physics.speedMps(powerW, it.gradePct, it.headwindMps, rho, params) }
        // More power is less time: bisect for the power that takes exactly as long as you did.
        var lo = 1.0
        var hi = 1000.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (timeAt(mid) > total) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** The effort over [passes] of a route with [segments] (same order as the route's own); passes without a forecast don't count. */
    fun fromTraversals(passes: List<SegmentTraversalEntity>, segments: List<EtaSegment>, params: RiderParams): Double? =
        watts(
            passes.mapNotNull { p ->
                val seg = segments.getOrNull(p.segIdx) ?: return@mapNotNull null
                val wind = p.headwindMps ?: return@mapNotNull null
                RiddenStretch(seg.lengthM, seg.gradePct, wind, p.movingMs / 1000.0)
            },
            params,
        )
}
