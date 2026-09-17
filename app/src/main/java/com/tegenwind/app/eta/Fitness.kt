package com.tegenwind.app.eta

import kotlin.math.exp

/** One ride's training load, as the fitness model sees it. */
data class RideLoad(val startedAtMs: Long, val loadTss: Double)

/** Where weeks of riding leave you today. Both numbers are in "load per day". */
data class RiderState(val fitness: Double, val fatigue: Double, val rideDays: Int = 0) {
    /** Positive = fresher than your recent average, negative = still carrying last week's rides. */
    val freshness: Double get() = fitness - fatigue
}

/**
 * Layer 3, long term: fitness and fatigue from the Banister model, as 42-day and 7-day
 * impulse responses to the load of every ride.
 *
 * Ride to ride this barely moves, which is the point: it is the slow background against which
 * "today's form" is measured. Ride a lot in one week and fatigue outruns fitness, so today starts
 * a little slower than your recent median; come back from a quiet week and it starts a little faster.
 */
object Banister {
    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0

    /** Loads older than this have decayed away; no point reading them. */
    const val WINDOW_DAYS = 120L

    /** Pace that counts as a solid hour's work, km/h: one such hour is a load of 100. */
    private const val REF_KMH = 25.0

    /** Rough defaults; only the ratio between rides matters, not the absolute level. */
    private const val HR_REST = 55.0
    private const val HR_MAX = 185.0

    private const val DAY_MS = 24 * 60 * 60 * 1000.0

    /** Freshness is only believable once there is a few weeks of riding behind it. */
    private const val MIN_FITNESS = 8.0
    private const val MIN_RIDE_DAYS = 10

    /** How much of the freshness swing reaches your legs, and the most it may ever claim. */
    private const val EFFECT = 0.15
    private const val MAX_EFFECT = 0.05

    /** Load from how long and how briskly you rode: the fallback when heart rate is unknown. */
    fun loadFromRide(movingMs: Long, distanceM: Double): Double {
        if (movingMs <= 0 || distanceM <= 0) return 0.0
        val hours = movingMs / 3_600_000.0
        val kmh = distanceM / 1000 / hours
        val intensity = (kmh / REF_KMH).coerceIn(0.0, 1.5)
        return hours * intensity * intensity * 100
    }

    /**
     * TRIMP from the ride's heart rate: minutes weighted by how hard they were, so an hour spent
     * near threshold counts for far more than an easy hour. Used once Health Connect has the ride.
     */
    fun loadFromHeartRate(bpm: List<Double>, movingMs: Long): Double {
        if (bpm.isEmpty() || movingMs <= 0) return 0.0
        val minutes = movingMs / 60_000.0
        val reserve = ((bpm.average() - HR_REST) / (HR_MAX - HR_REST)).coerceIn(0.0, 1.0)
        return minutes * reserve * 0.64 * exp(1.92 * reserve)
    }

    /**
     * Walks day by day from the first ride to today, letting each day's total work into both
     * averages. Rest days count as zero, which is what pulls fatigue back down.
     *
     * Both start at the average day rather than at zero. Starting from zero, the 42-day average
     * would spend its first months climbing while the 7-day one was already up to speed, and every
     * rider would read as permanently tired during their first season of riding.
     */
    fun evaluate(loads: List<RideLoad>, nowMs: Long): RiderState {
        if (loads.isEmpty()) return RiderState(0.0, 0.0)
        val perDay = loads.groupBy { it.startedAtMs.toDay() }.mapValues { (_, l) -> l.sumOf { it.loadTss } }
        val firstDay = perDay.keys.min()
        val today = nowMs.toDay().coerceAtLeast(firstDay)

        val seed = perDay.values.sum() / (today - firstDay + 1)
        var fitness = seed
        var fatigue = seed
        val fitnessDecay = exp(-1 / FITNESS_DAYS)
        val fatigueDecay = exp(-1 / FATIGUE_DAYS)
        for (day in firstDay..today) {
            val load = perDay[day] ?: 0.0
            fitness = fitness * fitnessDecay + (1 - fitnessDecay) * load
            fatigue = fatigue * fatigueDecay + (1 - fatigueDecay) * load
        }
        return RiderState(fitness, fatigue, perDay.size)
    }

    private fun Long.toDay(): Long = Math.floorDiv(this, DAY_MS.toLong())

    /**
     * Speed multiplier for how fresh you are, centred on 1.0 so it does not double-count the recent
     * form that [priorForm] already carries: it only says how today differs from your recent normal.
     */
    fun freshnessFactor(state: RiderState): Double {
        if (state.fitness < MIN_FITNESS || state.rideDays < MIN_RIDE_DAYS) return 1.0
        val relative = state.freshness / state.fitness
        return 1 + (EFFECT * relative).coerceIn(-MAX_EFFECT, MAX_EFFECT)
    }
}

/** Today's starting guess for form: your recent median, nudged by how fresh the last weeks leave you. */
fun startingForm(recentForms: List<Double>, loads: List<RideLoad>, nowMs: Long): Double =
    priorForm(recentForms) * Banister.freshnessFactor(Banister.evaluate(loads, nowMs))
