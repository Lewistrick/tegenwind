package com.tegenwind.app.weather

import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
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
import kotlin.math.abs
import kotlin.math.ceil

/** Wind at 10 m height, as weather models report it. [fromDeg] is where the wind comes from. */
data class WindSample(
    val timeMs: Long,
    val speedMps: Double,
    val fromDeg: Double,
    val tempC: Double?,
    /** Share of the sky covered by cloud, 0–100 %. */
    val cloudPct: Double? = null,
    /** Rain, mm per hour. */
    val rainMmH: Double? = null,
) {
    val sky: Sky? get() = if (cloudPct != null && rainMmH != null) Sky(cloudPct, rainMmH) else null
}

/** What the sky does over one stretch of the ride. */
data class Sky(val cloudPct: Double, val rainMmH: Double)

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
            tempC = lerpOrEither(a.tempC, b.tempC, f),
            cloudPct = lerpOrEither(a.cloudPct, b.cloudPct, f),
            rainMmH = lerpOrEither(a.rainMmH, b.rainMmH, f),
        )
    }

    private fun lerpOrEither(a: Double?, b: Double?, f: Double): Double? =
        if (a != null && b != null) a + f * (b - a) else a ?: b
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

/**
 * Forecasts for a few points along a route: each place on it takes the forecast of the nearest
 * point. Weather models work on a grid of a couple of kilometres, so that's all the detail there is.
 */
class RouteWeather(points: List<Pair<Double, WindForecast>>) {
    private val points = points.sortedBy { it.first }

    init {
        require(points.isNotEmpty())
    }

    /** The weather [alongM] metres along the route at [timeMs]. */
    fun at(alongM: Double, timeMs: Long): WindSample = points.minBy { abs(it.first - alongM) }.second.at(timeMs)

    companion object {
        /** About the size of the weather model's grid; closer points would only return duplicates. */
        const val SPACING_M = 2_000.0

        /** One forecast for everywhere: a free ride, where there's no route to spread points along. */
        fun everywhere(f: WindForecast) = RouteWeather(listOf(0.0 to f))

        /** Where to sample a route of [lengthM]: the middles of equal stretches of at most [SPACING_M]. */
        fun sampleDistances(lengthM: Double): List<Double> {
            val n = ceil(lengthM / SPACING_M).toInt().coerceAtLeast(1)
            return List(n) { (it + 0.5) * lengthM / n }
        }
    }
}

/** Fetches forecasts from Open-Meteo (free, no key; CC BY 4.0) and caches them for 15 minutes. */
class WeatherRepository {
    private val mutex = Mutex()
    private var cached: Triple<List<GeoPoint>, Long, List<WindForecast>>? = null

    /** The forecast near [point], or null when offline and nothing usable is cached. */
    suspend fun forecast(point: GeoPoint): WindForecast? = forecasts(listOf(point))?.first()

    /** Forecasts every ~2 km along [line], fetched in one request. */
    suspend fun alongRoute(line: Polyline): RouteWeather? {
        val distances = RouteWeather.sampleDistances(line.lengthM)
        val forecasts = forecasts(distances.map { line.pointAt(it) }) ?: return null
        return RouteWeather(distances.zip(forecasts))
    }

    private suspend fun forecasts(points: List<GeoPoint>): List<WindForecast>? = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { (ps, at, fs) ->
            val same = ps.size == points.size && ps.zip(points).all { (a, b) ->
                abs(a.lat - b.lat) < 0.05 && abs(a.lon - b.lon) < 0.05
            }
            if (same && now - at < FRESH_MS) return@withLock fs
        }
        try {
            fetch(points).also { cached = Triple(points, now, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("Tegenwind", "Weather forecast for ${points.size} points failed", e)
            cached?.takeIf { it.first.size == points.size && now - it.second < STALE_MS }?.third
        }
    }

    private suspend fun fetch(points: List<GeoPoint>): List<WindForecast> = withContext(Dispatchers.IO) {
        val lats = points.joinToString(",") { "%.3f".format(Locale.ROOT, it.lat) }
        val lons = points.joinToString(",") { "%.3f".format(Locale.ROOT, it.lon) }
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                "&minutely_15=$FIELDS&forecast_minutely_15=24&past_minutely_15=2" +
                "&hourly=$FIELDS&forecast_hours=8&past_hours=1" +
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
        // One location comes back as an object, several as an array of them in the order asked.
        val locations = if (body.trimStart().startsWith("[")) {
            JSONArray(body).let { a -> List(a.length()) { a.getJSONObject(it) } }
        } else listOf(JSONObject(body))
        if (locations.size != points.size) throw java.io.IOException("Open-Meteo returned ${locations.size} of ${points.size} places")
        locations.map { json ->
            // 15-minute data where the models provide it (Central Europe), hourly as a fallback.
            // Precipitation is the total over each step, so a 15-minute step times 4 is mm per hour.
            val samples = parse(json.optJSONObject("minutely_15"), stepsPerHour = 4.0)
                .ifEmpty { parse(json.optJSONObject("hourly"), stepsPerHour = 1.0) }
            if (samples.isEmpty()) throw java.io.IOException("Open-Meteo returned no wind data")
            WindForecast(samples)
        }
    }

    private fun parse(block: JSONObject?, stepsPerHour: Double): List<WindSample> {
        if (block == null) return emptyList()
        val time = block.getJSONArray("time")
        val speed = block.getJSONArray("wind_speed_10m")
        val dir = block.getJSONArray("wind_direction_10m")
        val temp = block.optJSONArray("temperature_2m")
        val cloud = block.optJSONArray("cloud_cover")
        val rain = block.optJSONArray("precipitation")
        return (0 until time.length()).mapNotNull { i ->
            if (speed.isNull(i) || dir.isNull(i)) return@mapNotNull null
            WindSample(
                timeMs = LocalDateTime.parse(time.getString(i)).toInstant(ZoneOffset.UTC).toEpochMilli(),
                speedMps = speed.getDouble(i),
                fromDeg = dir.getDouble(i),
                tempC = temp?.optDoubleOrNull(i),
                cloudPct = cloud?.optDoubleOrNull(i),
                rainMmH = rain?.optDoubleOrNull(i)?.let { it * stepsPerHour },
            )
        }
    }

    private fun JSONArray.optDoubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private companion object {
        const val FIELDS = "wind_speed_10m,wind_direction_10m,temperature_2m,cloud_cover,precipitation"
        const val FRESH_MS = 15 * 60_000L
        const val STALE_MS = 3 * 60 * 60_000L
    }
}
