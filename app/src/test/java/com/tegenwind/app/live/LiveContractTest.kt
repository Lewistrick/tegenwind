package com.tegenwind.app.live

import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.eta.Eta
import com.tegenwind.app.eta.LiveEta
import com.tegenwind.app.eta.WindNow
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.ride.RideRoute
import com.tegenwind.app.ride.RideSnapshot
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.RouteProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agreement with the meewind server. The fixtures in src/test/resources/contract are copies of
 * docs/fixtures in that repo, where an identical test posts them; change the JSON and both fail.
 */
class LiveContractTest {

    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.getResource("/contract/$name.json")!!.readText())

    private fun route() = LoadedRoute(
        route = RouteEntity(id = 7, name = "woon-werk", lengthM = 500.0, createdAtMs = 0),
        line = Polyline(listOf(GeoPoint(52.09123, 5.11034), GeoPoint(52.09123, 5.11400), GeoPoint(52.09123, 5.11766))),
        segments = listOf(
            // log(0.94) and log(1.18): what the learner stores, what the server is told.
            RouteSegmentEntity(7, 0, 0.0, 250.0, 90.0, learnedLogMean = -0.061875, learnedPasses = 12),
            RouteSegmentEntity(7, 1, 250.0, 500.0, 90.0, learnedLogMean = 0.165514, learnedPasses = 3),
        ),
    )

    private fun ride(started: Boolean = true, arrivedAtMs: Long? = null) = LiveRide(
        rideId = 1,
        simulated = false,
        started = started,
        arrivedAtMs = arrivedAtMs,
        snapshot = RideSnapshot(
            startedAtMs = 0,
            lastFixMs = 1758531600000,
            lastLat = 52.09512,
            lastLon = 5.11881,
            distanceM = 8120.0,
            movingMs = 1284000,
            paused = false,
            pausedSinceMs = null,
            speedKmh = 24.3,
            median2MinKmh = 23.0,
            speeds = emptyList(),
            headingDeg = 118.0,
        ),
        route = RideRoute(
            routeId = 7,
            name = "woon-werk",
            segmentStartsM = listOf(0.0, 250.0),
            progress = RouteProgress(8210.0, 13500.0, onRouteYet = true, offRoute = false),
        ),
        eta = LiveEta(
            eta = Eta(arrivalMs = 1758533040000, remainingS = 1440.0, sigmaS = 95.0 / 1.28, windCostS = 140.0),
            form = 1.0,
            // Riding at 118° with the wind from 240° is a tailwind: the numbers agree with the label.
            wind = WindNow(6.2, 240.0, -2.1, 302.0, 0.6),
        ),
    )

    @Test
    fun theManifestMatchesTheAgreedShape() {
        val built = manifestJson(routeVersion = 3, simulated = false, route = route())
        val expected = fixture("manifest")

        assertEquals(expected.getInt("v"), built.getInt("v"))
        assertEquals(3, built.getInt("routeVersion"))
        assertFalse(built.getBoolean("simulated"))

        val a = expected.getJSONObject("route")
        val b = built.getJSONObject("route")
        assertEquals(a.getLong("routeId"), b.getLong("routeId"))
        assertEquals(a.getString("name"), b.getString("name"))
        assertEquals(a.getDouble("lengthM"), b.getDouble("lengthM"), 1.0)
        assertEquals(a.getJSONArray("points").length(), b.getJSONArray("points").length())
        // [lat, lon], five decimals.
        assertEquals(52.09123, b.getJSONArray("points").getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(5.11034, b.getJSONArray("points").getJSONArray(0).getDouble(1), 1e-9)
        assertEquals(500.0, b.getDouble("lengthM"), 1.0)

        val expectedSegments = a.getJSONArray("segments")
        val builtSegments = b.getJSONArray("segments")
        assertEquals(expectedSegments.length(), builtSegments.length())
        for (i in 0 until expectedSegments.length()) {
            val want = expectedSegments.getJSONObject(i)
            val got = builtSegments.getJSONObject(i)
            assertEquals(want.getInt("i"), got.getInt("i"))
            assertEquals(want.getDouble("startM"), got.getDouble("startM"), 1e-9)
            assertEquals(want.getDouble("endM"), got.getDouble("endM"), 1e-9)
            assertEquals(want.getDouble("timeFactor"), got.getDouble("timeFactor"), 1e-3)
            assertEquals(want.getInt("passes"), got.getInt("passes"))
        }
    }

    @Test
    fun aTickMatchesTheAgreedShape() {
        val built = tickJson(routeVersion = 3, ride = ride(), nowMs = 1758531600000)
        val expected = fixture("tick")

        assertEquals(expected.getInt("v"), built.getInt("v"))
        assertEquals(expected.getInt("routeVersion"), built.getInt("routeVersion"))
        assertEquals(expected.getLong("tMs"), built.getLong("tMs"))

        for (key in listOf("lat", "lon", "headingDeg", "speedKmh")) {
            assertEquals(
                "pos.$key",
                expected.getJSONObject("pos").getDouble(key),
                built.getJSONObject("pos").getDouble(key),
                1e-9,
            )
        }
        assertEquals(true, built.getJSONObject("ride").getBoolean("started"))
        assertEquals(false, built.getJSONObject("ride").getBoolean("finished"))
        assertEquals(8120.0, built.getJSONObject("ride").getDouble("distanceM"), 1e-9)
        assertEquals(8210.0, built.getJSONObject("progress").getDouble("progressM"), 1e-9)
        assertEquals(1758533040000, built.getJSONObject("eta").getLong("arrivalMs"))
        assertEquals(95.0, built.getJSONObject("eta").getDouble("bandS"), 0.5)
        assertEquals("Tailwind", built.getJSONObject("wind").getString("label"))
        assertEquals(240.0, built.getJSONObject("wind").getDouble("fromDeg"), 1e-9)
    }

    @Test
    fun aFreeRideHasNoRouteAndNoEta() {
        val manifest = manifestJson(routeVersion = 4, simulated = false, route = null)
        assertTrue(manifest.isNull("route"))

        val freeRide = ride().copy(
            route = null,
            eta = null,
            freeWind = WindNow(5.0, 90.0, 1.0, 10.0, 0.6),
        )
        val tick = tickJson(routeVersion = 4, ride = freeRide, nowMs = 1758531600000)
        assertFalse(tick.has("progress"))
        assertFalse(tick.has("eta"))
        // The wind still means something without a route: your heading is enough.
        assertEquals(5.0, tick.getJSONObject("wind").getDouble("speed10Mps"), 1e-9)
    }

    @Test
    fun arrivingIsSaidOutLoud() {
        val arrived = tickJson(3, ride(arrivedAtMs = 1758533040000), 1758533040000)
        assertTrue(arrived.getJSONObject("ride").getBoolean("finished"))
        assertTrue(tickJson(3, ride(), 1L).markFinished().getJSONObject("ride").getBoolean("finished"))
    }

    @Test
    fun nothingIsSentBeforeTheRideSetsOff() {
        // The recorder reports started=false while you're still at the door; the viewer must know.
        val waiting = tickJson(3, ride(started = false), 1L)
        assertFalse(waiting.getJSONObject("ride").getBoolean("started"))
    }
}
