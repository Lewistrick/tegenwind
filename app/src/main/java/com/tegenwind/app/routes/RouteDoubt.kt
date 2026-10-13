package com.tegenwind.app.routes

/**
 * Why the route chosen for a ride looks like the wrong one for here and now: you are far from where
 * it starts ([farFromStartM]), or you never ride it around this hour while you do ride another
 * ([unusualHour]). [better] is the route that fits instead, when there is one.
 */
data class RouteDoubt(val farFromStartM: Double?, val unusualHour: Boolean, val better: Long?)

object RouteFit {
    /** Further than this from a route's start and you're probably not about to ride it. */
    const val FAR_M = 500.0

    /** Another route needs this many rides around this hour before the hour counts against the chosen one. */
    const val HABIT_RIDES = 2

    /**
     * The route to offer for a ride from [here] at this hour: one that starts here, and among those
     * the one ridden most around now ([habit]). Without a position, the one that is the habit now.
     * Null when nothing stands out: no route starts here, or several do and none is the habit.
     */
    fun best(starts: Map<Long, GeoPoint>, here: GeoPoint?, habit: Map<Long, Int>): Long? {
        if (here == null) {
            return starts.keys.filter { (habit[it] ?: 0) >= HABIT_RIDES }.maxByOrNull { habit[it] ?: 0 }
        }
        val near = starts.filter { distanceM(here, it.value) <= FAR_M }.keys
        if (near.size <= 1) return near.firstOrNull()
        val ranked = near.sortedByDescending { habit[it] ?: 0 }
        return ranked.first().takeIf { (habit[it] ?: 0) > (habit[ranked[1]] ?: 0) }
    }

    /**
     * Null when [selected] fits. [starts] is where each route begins, [here] where you are (null when
     * unknown, and then only the hour is judged), [habit] how often each route was ridden around
     * this time of day ([habitByRoute]).
     */
    fun doubt(selected: Long, starts: Map<Long, GeoPoint>, here: GeoPoint?, habit: Map<Long, Int>): RouteDoubt? {
        val start = starts[selected]
        val away = if (here != null && start != null) distanceM(here, start) else null
        val far = away?.takeIf { it > FAR_M }
        val others = starts.keys.filter { it != selected }
        val unusual = (habit[selected] ?: 0) == 0 && others.any { (habit[it] ?: 0) >= HABIT_RIDES }
        if (far == null && !unusual) return null

        // A route that starts here; among those, the one you ride most at this hour, then the nearest.
        // Without a position, the one you usually ride now.
        val better = if (here != null) {
            others.filter { distanceM(here, starts.getValue(it)) <= FAR_M }
                .sortedWith(compareByDescending<Long> { habit[it] ?: 0 }.thenBy { distanceM(here, starts.getValue(it)) })
                .firstOrNull()
        } else {
            others.filter { (habit[it] ?: 0) >= HABIT_RIDES }.maxByOrNull { habit[it] ?: 0 }
        }
        // At the chosen route's own start, another route that starts here too is only better if it
        // is the habit at this hour.
        return RouteDoubt(far, unusual, better?.takeIf { far != null || (habit[it] ?: 0) >= HABIT_RIDES })
    }
}
