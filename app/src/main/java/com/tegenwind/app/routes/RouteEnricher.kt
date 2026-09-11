package com.tegenwind.app.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.exp

data class OsmStats(val buildingCounts: List<Int>, val signalPoints: List<GeoPoint>)

/**
 * Looks up free, key-less public data for a route:
 * - elevation from Open-Meteo (Copernicus DEM, ~90 m resolution; CC BY 4.0)
 * - buildings and traffic lights from OpenStreetMap via the Overpass API (ODbL)
 */
class RouteEnricher {

    /** Elevation in metres for each point, in batches of 100 (the API's limit per request). */
    suspend fun elevations(points: List<GeoPoint>): List<Double> = withContext(Dispatchers.IO) {
        points.chunked(100).flatMap { batch ->
            val lat = batch.joinToString(",") { "%.5f".format(Locale.ROOT, it.lat) }
            val lon = batch.joinToString(",") { "%.5f".format(Locale.ROOT, it.lon) }
            val body = request(URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon"))
            val arr = JSONObject(body).getJSONArray("elevation")
            (0 until arr.length()).map { arr.getDouble(it) }
        }
    }

    /**
     * One Overpass query: the number of buildings around the middle of each segment,
     * plus all traffic signals in the route's bounding box.
     */
    suspend fun osmStats(line: Polyline, bounds: List<Pair<Double, Double>>): OsmStats = withContext(Dispatchers.IO) {
        val query = buildString {
            append("[out:json][timeout:90];\n")
            bounds.forEach { (start, end) ->
                val mid = line.pointAt((start + end) / 2)
                val radius = maxOf(60.0, (end - start) / 2).toInt()
                append("way[\"building\"](around:$radius,${fmt(mid.lat)},${fmt(mid.lon)});out count;\n")
            }
            val lats = line.points.map { it.lat }
            val lons = line.points.map { it.lon }
            val bbox = "${fmt(lats.min() - 0.001)},${fmt(lons.min() - 0.001)},${fmt(lats.max() + 0.001)},${fmt(lons.max() + 0.001)}"
            append("node[\"highway\"=\"traffic_signals\"]($bbox);out;\n")
        }
        val body = request(
            URL("https://overpass-api.de/api/interpreter"),
            post = "data=" + URLEncoder.encode(query, "UTF-8"),
        )
        val elements = JSONObject(body).getJSONArray("elements")
        val counts = ArrayList<Int>()
        val signals = ArrayList<GeoPoint>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.getString("type")) {
                "count" -> counts += e.getJSONObject("tags").getString("ways").toInt()
                "node" -> signals += GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
            }
        }
        if (counts.size != bounds.size) throw IOException("OpenStreetMap returned ${counts.size} of ${bounds.size} segments")
        OsmStats(counts, signals)
    }

    private fun fmt(v: Double) = "%.5f".format(Locale.ROOT, v)

    /** Busy public servers answer 429/5xx now and then; wait and retry a few times. */
    private suspend fun request(url: URL, post: String? = null): String {
        var attempt = 0
        while (true) {
            attempt++
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", USER_AGENT)
                if (post != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    outputStream.use { it.write(post.toByteArray()) }
                }
            }
            try {
                val code = conn.responseCode
                if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
                if (code !in RETRY_CODES || attempt >= MAX_ATTEMPTS) throw IOException("${url.host} answered HTTP $code")
            } finally {
                conn.disconnect()
            }
            delay(15_000L * attempt)
        }
    }

    companion object {
        private const val USER_AGENT = "Tegenwind/1.0 (personal cycling app; github.com/Lewistrick/tegenwind)"
        private val RETRY_CODES = setOf(429, 502, 503, 504)
        private const val MAX_ATTEMPTS = 3

        /** Wind exposure from the building count: 0 buildings -> 1.0 (open field), 75+ -> ~0.2 (town). */
        fun exposureFromBuildings(count: Int): Double = 0.15 + 0.85 * exp(-count / 25.0)
    }
}

/**
 * Least-squares slope of elevation over distance, in percent.
 * With 90 m elevation data a straight fit is far less noisy than end minus start.
 */
fun gradePct(distancesM: List<Double>, elevationsM: List<Double>): Double? {
    if (distancesM.size < 2) return null
    val mx = distancesM.average()
    val my = elevationsM.average()
    var num = 0.0
    var den = 0.0
    for (i in distancesM.indices) {
        num += (distancesM[i] - mx) * (elevationsM[i] - my)
        den += (distancesM[i] - mx) * (distancesM[i] - mx)
    }
    return if (den > 0) (num / den * 100).coerceIn(-15.0, 15.0) else null
}
