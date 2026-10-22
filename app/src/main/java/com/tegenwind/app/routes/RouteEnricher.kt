package com.tegenwind.app.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.exp

data class OsmStats(
    val buildingCounts: List<Int>,
    val signalPoints: List<GeoPoint>,
    /** Roads near the route, for drawing side roads and bridges on the live map. */
    val ways: List<OsmWay> = emptyList(),
)

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
            // Roads near the route, along the line itself (every ~50 m): side roads and bridges.
            val along = generateSequence(0.0) { it + ROAD_STEP_M }.takeWhile { it < line.lengthM }.map(line::pointAt).toList() +
                line.points.last()
            val path = along.joinToString(",") { "${fmt(it.lat)},${fmt(it.lon)}" }
            append("way[\"highway\"~\"$ROAD_TYPES\"](around:$ROAD_REACH_M,$path);out tags geom;\n")
        }
        val body = request(
            URL("https://overpass-api.de/api/interpreter"),
            post = "data=" + URLEncoder.encode(query, "UTF-8"),
        )
        val elements = JSONObject(body).getJSONArray("elements")
        val counts = ArrayList<Int>()
        val signals = ArrayList<GeoPoint>()
        val ways = ArrayList<OsmWay>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.getString("type")) {
                "count" -> counts += e.getJSONObject("tags").getString("ways").toInt()
                "node" -> signals += GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
                "way" -> {
                    val geometry = e.optJSONArray("geometry") ?: continue
                    val points = (0 until geometry.length()).map { k ->
                        geometry.getJSONObject(k).let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
                    }
                    val bridge = e.optJSONObject("tags")?.optString("bridge", "no").let { it != null && it != "no" && it.isNotEmpty() }
                    ways += OsmWay(points, bridge)
                }
            }
        }
        if (counts.size != bounds.size) throw IOException("OpenStreetMap returned ${counts.size} of ${bounds.size} segments")
        OsmStats(counts, signals, ways)
    }

    private fun fmt(v: Double) = "%.5f".format(Locale.ROOT, v)

    /** Busy public servers answer 429/5xx now and then; back off and retry a few times. */
    private suspend fun request(url: URL, post: String? = null): String {
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
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
            delay(retryDelayMs(attempt))
        }
    }

    companion object {
        /** Roads looked for near the route: the ones you could turn into on a bike. */
        private const val ROAD_TYPES =
            "^(primary|secondary|tertiary|unclassified|residential|living_street|service|cycleway|path|footway|pedestrian)$"
        private const val ROAD_REACH_M = 20
        private const val ROAD_STEP_M = 50.0
        private const val USER_AGENT = "Tegenwind/1.0 (personal cycling app; github.com/Lewistrick/tegenwind)"
        private val RETRY_CODES = setOf(429, 502, 503, 504)
        const val MAX_ATTEMPTS = 4
        private const val RETRY_BASE_MS = 20_000L
        private const val RETRY_CAP_MS = 120_000L

        /**
         * Doubling wait after each rejected request: 20 s, 40 s, 80 s. Overpass and Open-Meteo are
         * free servers that rate-limit when busy, and a longer pause is far likelier to get through
         * than hammering them. Capped so a whole lookup still fails within a few minutes.
         */
        fun retryDelayMs(attempt: Int): Long =
            (RETRY_BASE_MS shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(RETRY_CAP_MS)

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
