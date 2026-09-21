package com.tegenwind.app.routes

import kotlin.math.abs
import kotlin.math.cos

/** When a ride on a route started, for working out which route you usually ride at this hour. */
data class RouteStart(val routeId: Long, val startedAtMs: Long)

/**
 * Works out which saved route you're riding, without being told.
 *
 * Two routes that share their first kilometre are the same thing until they part, so nothing is
 * decided up front: every plausible route stays in the running, each one is dropped the moment you
 * leave its line, and the ride locks on when one is left. Meanwhile the likeliest one carries the
 * ETA, so there's always an arrival time on screen.
 *
 * A route and its mirror (there and back) are the case position alone can never settle: it's the
 * same line either way. Two things do settle it. Riding home-to-work you are at the *start* of one
 * and at the *finish* of the other, and once you've covered enough ground to know your heading, you
 * are pointing along one and against the other.
 */
class RouteMatcher(routes: List<LoadedRoute>, private val habit: Map<Long, Int> = emptyMap()) {

    inner class Candidate(val route: LoadedRoute) {
        val tracker = RouteTracker(route.line)
        var progress: RouteProgress = tracker.progress()
        var alive = true

        /** Lower is a better fit. Metres from the line, plus what pointing the wrong way is worth. */
        fun penalty(headingDeg: Double?): Double {
            val wrongWay = headingDeg?.let { h ->
                tracker.bearingAt(route.segments)?.let { b -> (1 - cos(Math.toRadians(h - b))) / 2 }
            } ?: 0.0
            val habitBonus = (habit[route.route.id] ?: 0).coerceAtMost(HABIT_CAP) * HABIT_WORTH_M
            return progress.offsetM + wrongWay * WRONG_WAY_WORTH_M - habitBonus
        }
    }

    private val candidates = routes.map { Candidate(it) }

    val alive: List<Candidate> get() = candidates.filter { it.alive }

    /** True once only one route is still possible; from here on the ride belongs to it. */
    val locked: Boolean get() = alive.size == 1

    /** Every route has been ruled out: you're riding somewhere else, so this is a free ride. */
    val exhausted: Boolean get() = alive.isEmpty()

    /** The route carrying the ETA right now: the best fit of those still possible. */
    var leader: Candidate? = null
        private set

    fun onFix(p: GeoPoint, headingDeg: Double?) {
        candidates.forEach { c ->
            if (!c.alive) return@forEach
            c.progress = c.tracker.update(p)
            if (!fits(c, headingDeg)) c.alive = false
        }
        // With everything ruled out there's no honest ETA to show, and the ride is a free one.
        leader = alive.minByOrNull { it.penalty(headingDeg) }
    }

    private fun fits(c: Candidate, headingDeg: Double?): Boolean {
        if (c.progress.offRoute) return false
        // Never got near this one in the first place.
        if (!c.progress.onRouteYet && c.progress.offsetM > RouteTracker.ACQUIRE_M) return false
        // Riding it backwards: the mirror route, which fits the line perfectly but not the direction.
        val bearing = c.tracker.bearingAt(c.route.segments)
        if (headingDeg != null && bearing != null && cos(Math.toRadians(headingDeg - bearing)) < 0) return false
        // Already at the finish before starting: again the mirror, seen from the other end.
        if (c.progress.onRouteYet && c.progress.remainingM < FINISHED_M && c.progress.progressM > FINISHED_M) return false
        return true
    }

    private companion object {
        /** Pointing straight the wrong way is as bad a fit as being this far off the line. */
        const val WRONG_WAY_WORTH_M = 200.0

        /** Each past ride at this hour makes a route this much more attractive, up to [HABIT_CAP]. */
        const val HABIT_WORTH_M = 5.0
        const val HABIT_CAP = 6

        /** Closer than this to a route's end means you're at that end, not riding towards it. */
        const val FINISHED_M = 100.0
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
