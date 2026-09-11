package com.tegenwind.app.weather

import com.tegenwind.app.routes.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Wind at 10 m height, as weather models report it. [fromDeg] is where the wind comes from. */
data class WindSample(val timeMs: Long, val speedMps: Double, val fromDeg: Double, val tempC: Double?)

/** A time series of wind forecasts; ask it for the wind at any moment in (or near) its range. */
class WindForecast(val samples: List<WindSample>) {
    init {
        require(samples.isNotEmpty())
    }

    fun at(timeMs: Long): WindSample {
        if (timeMs <= samples.first().timeMs) return samples.first()
        if (timeMs >= samples.last().timeMs) return samples.last()
        val i = samples.indexOfLast { it.timeMs <= timeMs }
        val a = samples[i]
        val b = samples[i + 1]
        val f = (timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
        return WindSample(
            timeMs = timeMs,
            speedMps = a.speedMps + f * (b.speedMps - a.speedMps),
            fromDeg = lerpAngle(a.fromDeg, b.fromDeg, f),
            tempC = if (a.tempC != null && b.tempC != null) a.tempC + f * (b.tempC - a.tempC) else a.tempC ?: b.tempC,
        )
    }
}

/** Interpolates along the shortest way round: 350° → 10° passes through 0°, not 180°. */
fun lerpAngle(a: Double, b: Double, f: Double): Double {
    val diff = ((b - a + 540) % 360) - 180
    return (a + f * diff + 360) % 360
}

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
fun compassPoint(deg: Double): String = COMPASS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

/** Beaufort number from wind speed at 10 m. */
fun beaufort(speedMps: Double): Int =
    listOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7).count { speedMps >= it }

/** Fetches forecasts from Open-Meteo (free, no key; CC BY 4.0) and caches them for 15 minutes. */
class WeatherRepository {
    private val mutex = Mutex()
    private var cached: Triple<GeoPoint, Long, WindForecast>? = null

    /** The forecast near [point], or null when offline and nothing usable is cached. */
    suspend fun forecast(point: GeoPoint): WindForecast? = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { (p, at, f) ->
            val near = kotlin.math.abs(p.lat - point.lat) < 0.05 && kotlin.math.abs(p.lon - point.lon) < 0.05
            if (near && now - at < FRESH_MS) return@withLock f
        }
        try {
            fetch(point).also { cached = Triple(point, now, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cached?.takeIf { now - it.second < STALE_MS }?.third
        }
    }

    private suspend fun fetch(point: GeoPoint): WindForecast = withContext(Dispatchers.IO) {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.ROOT, point.lat, point.lon) +
                "&minutely_15=wind_speed_10m,wind_direction_10m,temperature_2m&forecast_minutely_15=24&past_minutely_15=2" +
                "&hourly=wind_speed_10m,wind_direction_10m,temperature_2m&forecast_hours=8&past_hours=1" +
                "&wind_speed_unit=ms&timezone=GMT"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Tegenwind/1.0 (personal cycling app; github.com/Lewistrick/tegenwind)")
        }
        val body = try {
            if (conn.responseCode != 200) throw java.io.IOException("Open-Meteo answered HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        // 15-minute data where the models provide it (Central Europe), hourly as a fallback.
        val samples = parse(json.optJSONObject("minutely_15")).ifEmpty { parse(json.optJSONObject("hourly")) }
        if (samples.isEmpty()) throw java.io.IOException("Open-Meteo returned no wind data")
        WindForecast(samples)
    }

    private fun parse(block: JSONObject?): List<WindSample> {
        if (block == null) return emptyList()
        val time = block.getJSONArray("time")
        val speed = block.getJSONArray("wind_speed_10m")
        val dir = block.getJSONArray("wind_direction_10m")
        val temp = block.optJSONArray("temperature_2m")
        return (0 until time.length()).mapNotNull { i ->
            if (speed.isNull(i) || dir.isNull(i)) return@mapNotNull null
            WindSample(
                timeMs = LocalDateTime.parse(time.getString(i)).toInstant(ZoneOffset.UTC).toEpochMilli(),
                speedMps = speed.getDouble(i),
                fromDeg = dir.getDouble(i),
                tempC = temp?.optDoubleOrNull(i),
            )
        }
    }

    private fun JSONArray.optDoubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private companion object {
        const val FRESH_MS = 15 * 60_000L
        const val STALE_MS = 3 * 60 * 60_000L
    }
}
