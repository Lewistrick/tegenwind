package com.tegenwind.app.routes

import kotlin.math.abs
import kotlin.math.cos

/** When a ride on a route started, for working out which route you usually ride at this hour. */
data class RouteStart(val routeId: Long, val startedAtMs: Long)

/**
 * Works out which saved route you're riding, without being told.
 *
 * Two routes that share their first kilometre are the same thing until they part, so nothing is
 * decided up front: every plausible route stays in the running, each one falls away as riding
 * contradicts it, and the ride locks on when one is left. Meanwhile the likeliest one carries the
 * ETA, so there's always an arrival time on screen.
 *
 * A route and its mirror (there and back) are the case position alone can never settle: it's the
 * same line either way. Two things do settle it. Setting off, you are at the *start* of one and at
 * the *finish* of the other, and once you've covered enough ground to know your heading, you are
 * pointing along one and against the other.
 *
 * Nothing is ruled out on a single fix. A GPS wobble between buildings, an underpass, a sharp bend
 * inside a segment: any of those can look wrong for a moment, so a route is only dropped after
 * [STRIKES_OUT_M] of riding that keeps contradicting it, and it earns its way back if the ride fits
 * again. Only the "standing at the wrong end" test is instant, and only while you're setting off,
 * where it can't be anything else.
 */
class RouteMatcher(routes: List<LoadedRoute>, private val habit: Map<Long, Int> = emptyMap()) {

    inner class Candidate(val route: LoadedRoute) {
        val tracker = RouteTracker(route.line)
        var progress: RouteProgress = tracker.progress()

        /** Metres ridden that argue against this route; it's out once they pass [STRIKES_OUT_M]. */
        var strikesM = 0.0
        var out = false

        /** Lower is a better fit. Metres from the line, plus what pointing the wrong way is worth. */
        fun penalty(headingDeg: Double?): Double {
            val wrongWay = headingDeg?.let { h ->
                bearing()?.let { b -> (1 - cos(Math.toRadians(h - b))) / 2 }
            } ?: 0.0
            val habitBonus = (habit[route.route.id] ?: 0).coerceAtMost(HABIT_CAP) * HABIT_WORTH_M
            return progress.offsetM + wrongWay * WRONG_WAY_WORTH_M + strikesM - habitBonus
        }

        fun bearing(): Double? = tracker.bearingAt(route.segments)

        /** True when this fix argues against the route: off its line, or riding it backwards. */
        fun contradicted(headingDeg: Double?): Boolean {
            if (progress.offRoute) return true
            if (!progress.onRouteYet && progress.offsetM > RouteTracker.ACQUIRE_M) return true
            val bearing = bearing() ?: return false
            // Well past sideways before it counts, so a bend within a segment isn't "backwards".
            return headingDeg != null && cos(Math.toRadians(headingDeg - bearing)) < WRONG_WAY_COS
        }
    }

    private val candidates = routes.map { Candidate(it) }
    private var lastPoint: GeoPoint? = null
    private var riddenM = 0.0

    val alive: List<Candidate> get() = candidates.filter { !it.out }

    /** True once only one route is still possible; from here on the ride belongs to it. */
    val locked: Boolean get() = alive.size == 1

    /** Every route has been ruled out: you're riding somewhere else, so this is a free ride. */
    val exhausted: Boolean get() = alive.isEmpty()

    /** The route carrying the ETA right now: the best fit of those still possible. */
    var leader: Candidate? = null
        private set

    fun onFix(p: GeoPoint, headingDeg: Double?) {
        val stepM = lastPoint?.let { distanceM(it, p) } ?: 0.0
        lastPoint = p
        riddenM += stepM
        val settingOff = riddenM <= SETTING_OFF_M

        candidates.forEach { c ->
            // Every candidate keeps following along, even one that's out: a route dropped over a
            // detour has to be able to come back when the ride rejoins it.
            c.progress = c.tracker.update(p)
            if (c.contradicted(headingDeg)) {
                // A route you've been riding deserves the benefit of the doubt. One you have never
                // been anywhere near does not, so it goes quickly rather than holding the ETA.
                val neverNear = !c.progress.onRouteYet && c.progress.offsetM > FAR_M
                c.strikesM += maxOf(stepM, MIN_STEP_M) * (if (neverNear) NEVER_NEAR_WEIGHT else 1.0)
            } else {
                c.strikesM = (c.strikesM - maxOf(stepM, MIN_STEP_M) * FORGIVE).coerceAtLeast(0.0)
            }
            // Setting off at a route's far end means you're riding its mirror, and nothing else.
            if (settingOff && c.progress.onRouteYet && c.progress.remainingM < AT_THE_END_M) {
                c.strikesM = STRIKES_OUT_M * 2
            }
            c.out = if (c.out) c.strikesM > STRIKES_BACK_M else c.strikesM > STRIKES_OUT_M
        }
        // With everything ruled out there's no honest ETA to show, and the ride is a free one.
        leader = alive.minByOrNull { it.penalty(headingDeg) }
    }

    private companion object {
        /** Riding that contradicts a route for this far drops it. About a block and a half. */
        const val STRIKES_OUT_M = 300.0

        /** A dropped route is back once the evidence against it has decayed to this. */
        const val STRIKES_BACK_M = 100.0

        /** Riding that fits pays off mismatch this much faster than mismatch builds it up. */
        const val FORGIVE = 0.5

        /** Standing still still counts a little, so a route can't be contradicted for free. */
        const val MIN_STEP_M = 2.0

        /** Beyond this from a route you've never been on, it isn't the one you're riding. */
        const val FAR_M = 150.0
        const val NEVER_NEAR_WEIGHT = 10.0

        /** How far into the ride the "standing at the wrong end" test still means anything. */
        const val SETTING_OFF_M = 150.0

        /** This close to a route's end, setting off, you're at that end rather than riding to it. */
        const val AT_THE_END_M = 100.0

        /** Pointing more than this far from the route's own direction counts as riding it backwards. */
        const val WRONG_WAY_COS = -0.5 // 120° off

        /** Pointing straight the wrong way is as bad a fit as being this far off the line. */
        const val WRONG_WAY_WORTH_M = 200.0

        /** Each past ride at this hour makes a route this much more attractive, up to [HABIT_CAP]. */
        const val HABIT_WORTH_M = 5.0
        const val HABIT_CAP = 6
    }
}

/** How often each route was ridden around this time of day: the tiebreaker while routes still overlap. */
fun habitByRoute(starts: List<RouteStart>, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Map<Long, Int> {
    fun minutesOfDay(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toSecondOfDay() / 60
    val now = minutesOfDay(nowMs)
    return starts
        .filter { start ->
            val diff = abs(minutesOfDay(start.startedAtMs) - now)
            minOf(diff, 24 * 60 - diff) <= SAME_HOUR_MINUTES
        }
        .groupingBy { it.routeId }
        .eachCount()
}

private const val SAME_HOUR_MINUTES = 90
