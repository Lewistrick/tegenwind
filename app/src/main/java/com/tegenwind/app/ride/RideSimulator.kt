package com.tegenwind.app.ride

import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Fake GPS for trying the ride screen at a desk: ~25 km/h with traffic-light stops, one fix per second.
 * Follows [route] when given (and stands still at its end), otherwise rides a straight line.
 */
object RideSimulator {
    private const val METERS_PER_DEG_LAT = 111_195.0

    suspend fun run(route: Polyline?, emit: (Fix) -> Unit) {
        val rnd = Random()
        var free = GeoPoint(52.0907, 5.1214)
        val bearing = Math.toRadians(40.0)
        var along = 0.0
        var v = 0.0
        var drift = 0.0
        var stopLeft = 0
        var nextStopIn = 90 + rnd.nextInt(120)
        while (true) {
            if (stopLeft > 0) stopLeft--
            else if (--nextStopIn <= 0) {
                stopLeft = 15 + rnd.nextInt(30)
                nextStopIn = 120 + rnd.nextInt(150)
            }
            drift += -drift * 0.1 + rnd.nextGaussian() * 0.02
            val arrived = route != null && along >= route.lengthM
            val target = if (stopLeft > 0 || arrived) 0.0 else 7.0 * (1 + drift)
            v = max(0.0, v + (target - v).coerceIn(-2.5, 0.9))
            val p = if (route != null) {
                along = (along + v).coerceAtMost(route.lengthM)
                route.pointAt(along)
            } else {
                free = GeoPoint(
                    free.lat + v * cos(bearing) / METERS_PER_DEG_LAT,
                    free.lon + v * sin(bearing) / (METERS_PER_DEG_LAT * cos(Math.toRadians(free.lat))),
                )
                free
            }
            emit(Fix(System.currentTimeMillis(), p.lat, p.lon, max(0.0, v + rnd.nextGaussian() * 0.3), 5.0))
            delay(1_000)
        }
    }
}
