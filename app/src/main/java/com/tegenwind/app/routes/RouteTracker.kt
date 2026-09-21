package com.tegenwind.app.routes

data class RouteProgress(
    val progressM: Double,
    val lengthM: Double,
    /** False until the first fix within reach of the route. */
    val onRouteYet: Boolean,
    val offRoute: Boolean,
    /** How far the last fix was from the route's line. */
    val offsetM: Double = 0.0,
) {
    val remainingM: Double get() = (lengthM - progressM).coerceAtLeast(0.0)
}

/**
 * Follows your position along a route. Progress only moves forward, and while on the route
 * it only looks a little behind and ahead, so a route that passes close to itself can't make you jump.
 */
class RouteTracker(private val line: Polyline) {
    private var progressM = 0.0
    private var acquired = false
    private var offRoute = false
    private var offsetM = 0.0

    fun update(p: GeoPoint): RouteProgress {
        val proj = when {
            !acquired -> line.project(p)
            offRoute -> line.project(p, fromM = progressM - REJOIN_BACK_M)
            else -> line.project(p, fromM = progressM - BACK_M, toM = progressM + AHEAD_M)
        }
        if (!acquired || offRoute) {
            if (proj.offsetM <= ACQUIRE_M) {
                acquired = true
                offRoute = false
                progressM = maxOf(progressM, proj.alongM)
            }
        } else if (proj.offsetM > OFF_ROUTE_M) {
            // Not near the expected stretch: maybe we jumped ahead (GPS gap), otherwise we left the route.
            val ahead = line.project(p, fromM = progressM - REJOIN_BACK_M)
            if (ahead.offsetM <= ACQUIRE_M) progressM = maxOf(progressM, ahead.alongM) else offRoute = true
        } else {
            progressM = maxOf(progressM, proj.alongM)
        }
        offsetM = proj.offsetM
        return progress()
    }

    /** Where the route's line points at your position, or null before the first fix. */
    fun bearingAt(segments: List<com.tegenwind.app.data.RouteSegmentEntity>): Double? =
        if (!acquired) null else (segments.firstOrNull { progressM < it.endM } ?: segments.lastOrNull())?.bearingDeg

    fun progress() = RouteProgress(progressM, line.lengthM, acquired, offRoute, offsetM)

    companion object {
        const val ACQUIRE_M = 40.0
        const val OFF_ROUTE_M = 60.0
        const val BACK_M = 30.0
        const val AHEAD_M = 300.0
        const val REJOIN_BACK_M = 100.0
    }
}
