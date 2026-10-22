package com.tegenwind.app.weather

/** The weather over one stretch of a ride: how long it lasted ([hours]) and what it was like. */
data class WeatherPart(val hours: Double, val tempC: Double?, val rainMmH: Double?)

/** The weather over a whole ride: its average temperature, and the rain that fell on it. */
data class RideWeather(val avgTempC: Double?, val rainMm: Double?)

/**
 * Puts the [parts] of a ride together: temperature averaged over the time, rain added up
 * (mm/h × hours). Null without anything to go by.
 */
fun summarizeWeather(parts: List<WeatherPart>): RideWeather? {
    val withTemp = parts.filter { it.tempC != null && it.hours > 0 }
    val temp = withTemp.sumOf { it.hours }.takeIf { it > 0 }?.let { total -> withTemp.sumOf { it.tempC!! * it.hours } / total }
    val withRain = parts.filter { it.rainMmH != null && it.hours > 0 }
    val rain = withRain.takeIf { it.isNotEmpty() }?.sumOf { it.rainMmH!! * it.hours }
    return if (temp == null && rain == null) null else RideWeather(temp, rain)
}

/** One hour of recorded weather: [timeMs] is the stamp Open-Meteo gives (rain is the hour before it). */
data class HourlyWeather(val timeMs: Long, val tempC: Double?, val rainMm: Double?)

private const val HOUR_MS = 3_600_000L

/**
 * The weather over a ride from [hours] of record: each hour counts for as long as it overlaps the
 * ride. Rain is the total of the hour before its stamp; the temperature is the one at the stamp, for
 * half an hour either side. Null when the record doesn't reach the ride.
 */
fun weatherOverRide(startMs: Long, endMs: Long, hours: List<HourlyWeather>): RideWeather? {
    fun overlapH(from: Long, to: Long) = (minOf(to, endMs) - maxOf(from, startMs)).coerceAtLeast(0) / HOUR_MS.toDouble()
    val parts = hours.flatMap { h ->
        listOf(
            WeatherPart(overlapH(h.timeMs - HOUR_MS / 2, h.timeMs + HOUR_MS / 2), h.tempC, null),
            WeatherPart(overlapH(h.timeMs - HOUR_MS, h.timeMs), null, h.rainMm),
        )
    }
    return summarizeWeather(parts)
}
