package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFeaturesTest {
    private val lat0 = 51.5
    private val lon0 = 4.2
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** Point [eastM] east and [northM] north of the origin. */
    private fun p(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon)
    private fun way(vararg corners: Pair<Double, Double>, bridge: Boolean = false) = OsmWay(corners.map { (e, n) -> p(e, n) }, bridge)

    /** The route: 1 km due east. */
    private val route = Polyline(listOf(p(0.0, 0.0), p(1000.0, 0.0)))

    @Test
    fun aCrossingStreetBecomesTwoStubsOfFortyMetres() {
        val stubs = sideRoadStubs(route, listOf(way(500.0 to -200.0, 500.0 to 200.0)))
        assertEquals(2, stubs.size)
        for (s in stubs) {
            assertEquals(0.0, route.project(s.first()).offsetM, 3.0) // starts at the route
            assertEquals(40.0, Polyline(s).lengthM, 1.0)
        }
    }

    @Test
    fun aStreetJoiningFromOneSideIsOneStub() {
        val stubs = sideRoadStubs(route, listOf(way(300.0 to 0.0, 300.0 to 150.0)))
        assertEquals(1, stubs.size)
        assertEquals(40.0, route.project(stubs[0].last()).offsetM, 1.0)
    }

    @Test
    fun theRoutesOwnRoadAndFarStreetsAreNoSideRoads() {
        // Along the route (the road it follows), and a parallel street 60 m away.
        assertTrue(sideRoadStubs(route, listOf(way(-50.0 to 2.0, 400.0 to 3.0))).isEmpty())
        assertTrue(sideRoadStubs(route, listOf(way(0.0 to 60.0, 1000.0 to 60.0))).isEmpty())
    }

    @Test
    fun aBridgeTheRouteRunsOverIsItsStretchAndOneOverheadIsNot() {
        val stretches = bridgeStretches(route, listOf(way(400.0 to 1.0, 440.0 to 1.0, bridge = true)))
        assertEquals(1, stretches.size)
        assertEquals(400.0, route.project(stretches[0].first).alongM, 1.0)
        assertEquals(440.0, route.project(stretches[0].second).alongM, 1.0)
        // A bridge crossing over the route, and a road that isn't a bridge.
        assertTrue(bridgeStretches(route, listOf(way(600.0 to -100.0, 600.0 to 100.0, bridge = true))).isEmpty())
        assertTrue(bridgeStretches(route, listOf(way(400.0 to 1.0, 440.0 to 1.0))).isEmpty())
    }

    @Test
    fun lightsAtOneJunctionAreOneAndFarOnesDontCount() {
        val lights = routeLights(route, listOf(p(500.0, 8.0), p(508.0, -8.0), p(492.0, -6.0), p(800.0, 3.0), p(300.0, 80.0)))
        assertEquals(2, lights.size)
        assertEquals(500.0, route.project(lights[0]).alongM, 6.0)
        assertEquals(800.0, route.project(lights[1]).alongM, 1.0)
    }

    @Test
    fun pointsSurviveStoring() {
        val points = listOf(p(1.0, 2.0), p(500.0, -30.0))
        val back = decodePoints(encodePoints(points))
        assertEquals(points.size, back.size)
        points.zip(back).forEach { (a, b) -> assertEquals(0.0, distanceM(a, b), 0.2) }
    }
}
