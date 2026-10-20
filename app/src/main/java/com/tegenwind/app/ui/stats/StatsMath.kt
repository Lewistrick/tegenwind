package com.tegenwind.app.ui.stats

import com.tegenwind.app.data.LearningPass
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.eta.Physics
import com.tegenwind.app.eta.RiderParams
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

// The numbers behind the Stats page, kept apart from the screen so they can be tested.

/** The share of what you burn that becomes work at the pedals: muscle is about a quarter efficient. */
const val MUSCLE_EFFICIENCY = 0.24
private const val J_PER_KCAL = 4184.0

/** An average petrol car: 6.5 L per 100 km, and 2.31 kg of CO₂ for every litre burned. */
const val CAR_PETROL_L_PER_KM = 0.065
const val PETROL_KG_CO2_PER_L = 2.31

/** Riding more than this much into the wind, on average over the ride, makes it a ride with a headwind. */
const val HEADWIND_RIDE_MPS = 1.0 / 3.6

fun rideDurationMin(ride: RideEntity): Double = ((ride.endedAtMs ?: ride.startedAtMs) - ride.startedAtMs) / 60_000.0

/** Petrol and CO₂ that driving [distanceM] instead would have cost. */
data class Savings(val petrolL: Double, val co2Kg: Double)

fun savedVersusCar(distanceM: Double): Savings {
    val litres = distanceM / 1000 * CAR_PETROL_L_PER_KM
    return Savings(litres, litres * PETROL_KG_CO2_PER_L)
}

/**
 * What the legs burned over [rides]: the work at the pedals (effort × time moving) divided by the
 * muscle's efficiency. Rides without an effort don't count. An estimate.
 */
fun kcalBurned(rides: List<RideEntity>): Double = rides.sumOf { r ->
    val watts = r.pedalPowerW ?: return@sumOf 0.0
    watts * (r.movingMs / 1000.0) / MUSCLE_EFFICIENCY / J_PER_KCAL
}

/** Seconds the wind cost over all rides ([lostS], headwind) and gave back ([gainedS], tailwind). */
data class WindEffect(val lostS: Double, val gainedS: Double)

/**
 * What the wind did to your time, from what you rode: each pass of a segment at the effort of its
 * ride ([wattsOf], see [com.tegenwind.app.eta.SteadyPower]) takes this long in the wind that day and
 * that long without it. Into the wind it costs, with it at your back it gives time back. Passes
 * without a forecast, and rides without an effort, don't count.
 */
fun windEffect(passes: List<LearningPass>, wattsOf: (rideId: Long) -> Double?, paramsOf: (rideId: Long) -> RiderParams): WindEffect {
    val rho = Physics.airDensity(null)
    var lost = 0.0
    var gained = 0.0
    for (p in passes) {
        val head = p.headwindMps ?: continue
        val watts = wattsOf(p.rideId) ?: continue
        val length = p.endM - p.startM
        if (length <= 0) continue
        val params = paramsOf(p.rideId)
        val grade = p.gradePct ?: 0.0
        val inWind = length / Physics.speedMps(watts, grade, head, rho, params)
        val calm = length / Physics.speedMps(watts, grade, 0.0, rho, params)
        val effect = inWind - calm
        if (effect > 0) lost += effect else gained -= effect
    }
    return WindEffect(lost, gained)
}

/** How many of the [total] rides with wind data had a headwind on the whole. */
data class HeadwindShare(val withHeadwind: Int, val total: Int) {
    val pct: Double get() = if (total == 0) 0.0 else withHeadwind * 100.0 / total
}

/** A ride is a headwind ride when the wind against it, averaged over the distance, was above [HEADWIND_RIDE_MPS]. */
fun headwindShare(passes: List<LearningPass>): HeadwindShare {
    val byRide = passes.filter { it.headwindMps != null && it.endM > it.startM }.groupBy { it.rideId }
    val heads = byRide.values.map { ps ->
        val metres = ps.sumOf { it.endM - it.startM }
        ps.sumOf { it.headwindMps!! * (it.endM - it.startM) } / metres
    }
    return HeadwindShare(heads.count { it > HEADWIND_RIDE_MPS }, heads.size)
}

/** Moving speed of a ride, km/h; null without any moving time. */
fun movingSpeedKmh(ride: RideEntity): Double? =
    if (ride.movingMs > 0) ride.distanceM / (ride.movingMs / 1000.0) * 3.6 else null

/**
 * How much [points] (time in ms, value) rise per 30 days, by a straight line through them. Null
 * with fewer than 4 points or less than two weeks between the first and the last: too little to
 * call a direction.
 */
fun trendPerMonth(points: List<Pair<Long, Double>>): Double? {
    if (points.size < 4) return null
    val dayMs = 86_400_000.0
    val t0 = points.minOf { it.first }
    if ((points.maxOf { it.first } - t0) / dayMs < 14) return null
    val xs = points.map { (it.first - t0) / dayMs }
    val ys = points.map { it.second }
    val mx = xs.average()
    val my = ys.average()
    val varX = xs.sumOf { (it - mx) * (it - mx) }
    if (varX == 0.0) return null
    return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / varX * 30
}

/**
 * When the ride reached the end of its route: the moment it left the last segment, if it was timed.
 * [passes] are the ride's own.
 */
fun actualArrivalMs(passes: List<com.tegenwind.app.data.SegmentTraversalEntity>, lastSegIdx: Int): Long? =
    passes.firstOrNull { it.segIdx == lastSegIdx }?.exitMs

/** Seconds a ride arrived later (+) or earlier (−) than predicted; null when either is unknown. */
fun arrivalGapS(predictedMs: Long?, actualMs: Long?): Double? =
    if (predictedMs == null || actualMs == null) null else (actualMs - predictedMs) / 1000.0

enum class Trend { Better, Steady, Worse }

/**
 * Whether gaps (seconds, oldest first, early or late) are shrinking: the average size of the newer
 * half against the older. Null with fewer than 4 gaps.
 */
fun gapTrend(gapsS: List<Double>): Trend? {
    if (gapsS.size < 4) return null
    val half = gapsS.size / 2
    val older = gapsS.take(half).map(::abs).average()
    val newer = gapsS.takeLast(half).map(::abs).average()
    return when {
        newer < older * 0.8 -> Trend.Better
        newer > older * 1.25 -> Trend.Worse
        else -> Trend.Steady
    }
}

/** Hour of the day a ride set off, 0 up to 24 (7.5 is half past seven), in the local time zone. */
fun startHour(ride: RideEntity, zone: ZoneId = ZoneId.systemDefault()): Double {
    val t = Instant.ofEpochMilli(ride.startedAtMs).atZone(zone)
    return t.hour + t.minute / 60.0
}

/** Average duration in minutes for each weekday, Monday first; null for a day without rides. */
fun weekdayAverages(rides: List<RideEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Double, Int>?> {
    val byDay = rides.groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).dayOfWeek }
    return DayOfWeek.values().toList().map { d -> byDay[d]?.let { rs -> rs.map(::rideDurationMin).average() to rs.size } }
}

/** "42 h 10 min", or "35 min" under an hour. */
fun hoursMinutes(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
}
