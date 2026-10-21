package com.tegenwind.app.weather

// TODO(remove after backfill): the one-off weather lookup for rides recorded before their weather was
// kept. Once the old rides have their weather (or you accept leaving the rest empty), delete:
//   weather/WeatherHistory.kt, ride/WeatherBackfill.kt, weatherOverRide + HourlyWeather in
//   weather/RideWeather.kt (and their test in StatsMathTest.kt), the backfillWeather launch in
//   TegenwindApp.kt, and RideDao.firstPoint / ridesWithoutWeather.
// Keep summarizeWeather, avgTempC and rainMm: new rides record their own weather.

import com.tegenwind.app.routes.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The weather as it was, from Open-Meteo: for rides recorded before their weather was kept. The
 * archive runs about a week behind, so recent days come from the forecast service's past days.
 */
class WeatherHistory {
    /** Hourly temperature and rain at [point] from [startMs] to [endMs], or null when it couldn't be read. */
    suspend fun hourly(point: GeoPoint, startMs: Long, endMs: Long): List<HourlyWeather>? = withContext(Dispatchers.IO) {
        val old = System.currentTimeMillis() - endMs > ARCHIVE_LAG_MS
        val day = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() }
        val where = "latitude=${"%.3f".format(Locale.ROOT, point.lat)}&longitude=${"%.3f".format(Locale.ROOT, point.lon)}"
        val url = if (old) {
            "https://archive-api.open-meteo.com/v1/archive?$where&start_date=${day(startMs - HOUR_MS)}&end_date=${day(endMs)}" +
                "&hourly=temperature_2m,precipitation&timezone=GMT"
        } else {
            "https://api.open-meteo.com/v1/forecast?$where&past_days=10&forecast_days=1&hourly=temperature_2m,precipitation&timezone=GMT"
        }
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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
            parseHourly(JSONObject(body))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("Tegenwind", "Weather history failed", e)
            null
        }
    }

    private companion object {
        const val ARCHIVE_LAG_MS = 7 * 24 * 3_600_000L
        const val HOUR_MS = 3_600_000L
    }
}

internal fun parseHourly(json: JSONObject): List<HourlyWeather>? {
    val block = json.optJSONObject("hourly") ?: return null
    val time = block.getJSONArray("time")
    val temp = block.optJSONArray("temperature_2m")
    val rain = block.optJSONArray("precipitation")
    return (0 until time.length()).map { i ->
        HourlyWeather(
            timeMs = LocalDateTime.parse(time.getString(i)).toInstant(ZoneOffset.UTC).toEpochMilli(),
            tempC = temp?.takeIf { !it.isNull(i) }?.getDouble(i),
            rainMm = rain?.takeIf { !it.isNull(i) }?.getDouble(i),
        )
    }
}
