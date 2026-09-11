package com.tegenwind.app.ride

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS fix, independent of Android's Location class so the logic is unit-testable. */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val accuracyM: Double?,
    val altitudeM: Double? = null,
)

data class RideSnapshot(
    val startedAtMs: Long,
    val lastFixMs: Long?,
    val distanceM: Double,
    val movingMs: Long,
    val paused: Boolean,
    val speedKmh: Double?,
    val avg2MinKmh: Double?,
    val speeds: List<Sample>,
)

/**
 * Turns GPS fixes into ride stats: drops inaccurate fixes, auto-pauses when you stand still
 * (e.g. at traffic lights), and ignores GPS jitter while stopped.
 */
class RideTracker(private val startedAtMs: Long) {
    private val speeds = ArrayList<Sample>()
    private val window = RollingWindow(TWO_MINUTES_MS)
    private var last: Fix? = null
    private var stoppedSinceMs: Long? = null
    private var distanceM = 0.0
    private var movingMs = 0L
    private var paused = false
    private var speedKmh: Double? = null
    private var avgKmh: Double? = null

    /** Returns false when the fix was too inaccurate or out of order to use. */
    fun add(fix: Fix): Boolean {
        if ((fix.accuracyM ?: 0.0) > MAX_ACCURACY_M) return false
        val prev = last
        val dtMs = if (prev != null) fix.timeMs - prev.timeMs else 0L
        if (prev != null && dtMs <= 0) return false

        val step = if (prev != null) haversineM(prev.lat, prev.lon, fix.lat, fix.lon) else 0.0
        // Doppler speed from the GPS chip is far less noisy than differencing positions.
        val speedMps = fix.speedMps ?: if (dtMs > 0) step / (dtMs / 1000.0) else 0.0
        val moving = speedMps >= STOP_SPEED_MPS

        if (moving) {
            stoppedSinceMs = null
            paused = false
        } else {
            val since = stoppedSinceMs ?: fix.timeMs.also { stoppedSinceMs = it }
            paused = fix.timeMs - since >= PAUSE_AFTER_MS
        }
        if (prev != null && !paused) {
            movingMs += minOf(dtMs, MAX_GAP_MS)
            if (moving) distanceM += step
        }

        val kmh = speedMps * 3.6
        speeds += Sample(fix.timeMs, kmh)
        speedKmh = kmh
        avgKmh = window.add(fix.timeMs, kmh)
        last = fix
        return true
    }

    fun snapshot() = RideSnapshot(
        startedAtMs = startedAtMs,
        lastFixMs = last?.timeMs,
        distanceM = distanceM,
        movingMs = movingMs,
        paused = paused,
        speedKmh = speedKmh,
        avg2MinKmh = avgKmh,
        speeds = speeds.toList(),
    )

    companion object {
        const val MAX_ACCURACY_M = 20.0
        const val STOP_SPEED_MPS = 1.5 / 3.6
        const val PAUSE_AFTER_MS = 5_000L
        /** A longer GPS gap (tunnel, underpass) adds at most this much moving time. */
        const val MAX_GAP_MS = 10_000L
    }
}

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
