package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    private val lat0 = 51.5
    private val lon0 = 4.2
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** Point [eastM] east and [northM] north of the origin. */
    private fun p(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon)

    @Test
    fun parsesGpxTrackPoints() {
        val gpx = """<?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><trk><name>xyz</name><trkseg>
              <trkpt lat="51.5000000" lon="4.2000000"><ele>0</ele></trkpt>
              <trkpt lat="51.5000000" lon="4.2000000"><ele>0</ele></trkpt>
              <trkpt lat="51.5002000" lon="4.2004000"><ele>0</ele></trkpt>
            </trkseg></trk></gpx>"""
        val points = Gpx.parse(gpx.byteInputStream())
        assertEquals(3, points.size)
        assertEquals(2, cleanPoints(points).size) // the repeated point is dropped
        assertEquals(4.2004, points.last().lon, 1e-9)
    }

    @Test
    fun measuresAndInterpolatesAlongTheLine() {
        val line = Polyline(listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, 500.0)))
        assertEquals(1500.0, line.lengthM, 2.0)
        val mid = line.pointAt(1250.0)
        assertEquals(p(1000.0, 250.0).lat, mid.lat, 1e-5)
        assertEquals(90.0, bearingDeg(p(0.0, 0.0), p(1000.0, 0.0)), 0.5)
    }

    @Test
    fun projectsPositionsOntoTheLine() {
        val line = Polyline(listOf(p(0.0, 0.0), p(1000.0, 0.0)))
        val proj = line.project(p(400.0, 20.0))
        assertEquals(400.0, proj.alongM, 1.0)
        assertEquals(20.0, proj.offsetM, 0.5)
    }

    @Test
    fun splitsIntoEqualSegments() {
        val bounds = segmentBounds(13_550.0)
        assertEquals(54, bounds.size)
        assertEquals(0.0, bounds.first().first, 1e-9)
        assertEquals(13_550.0, bounds.last().second, 1e-9)
        assertEquals(1, segmentBounds(80.0).size)
    }

    @Test
    fun namesTheReverseRoute() {
        assertEquals("werk-woon", reverseName("woon-werk"))
        assertEquals("Work → Home", reverseName("Home → Work"))
        assertEquals("Gym (reverse)", reverseName("Gym"))
    }

    @Test
    fun clustersTrafficLightsAtOneJunction() {
        assertEquals(listOf(100.0, 500.0), clusterAlong(listOf(500.0, 100.0, 120.0, 130.0, 515.0), withinM = 40.0))
    }

    @Test
    fun fitsGradeThroughNoisyElevation() {
        val d = listOf(0.0, 50.0, 100.0, 150.0, 200.0)
        val e = listOf(0.0, 1.0, 1.0, 3.0, 4.0) // ~2% climb with 1 m steps
        assertEquals(2.0, gradePct(d, e)!!, 0.25)
    }

    @Test
    fun tracksProgressAndDetectsLeavingTheRoute() {
        val t = RouteTracker(Polyline(listOf(p(0.0, 0.0), p(2000.0, 0.0))))
        assertFalse(t.update(p(-300.0, 0.0)).onRouteYet) // still at home, 300 m from the start
        assertEquals(100.0, t.update(p(100.0, 5.0)).progressM, 1.0)
        assertEquals(600.0, t.update(p(600.0, -8.0)).progressM, 1.0) // 500 m jump, e.g. after a GPS gap
        assertEquals(600.0, t.update(p(590.0, 3.0)).progressM, 1.0) // GPS jitter backwards doesn't undo progress
        val off = t.update(p(700.0, 150.0))
        assertTrue(off.offRoute)
        assertEquals(600.0, off.progressM, 1.0)
        val back = t.update(p(900.0, 10.0))
        assertFalse(back.offRoute)
        assertEquals(900.0, back.progressM, 1.0)
    }

    @Test
    fun doesNotJumpWhereTheRoutePassesCloseToItself() {
        // Out 1 km east, a U-turn 30 m north, and back west: the return leg runs 30 m from the outward one.
        val t = RouteTracker(Polyline(listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, 30.0), p(0.0, 30.0))))
        t.update(p(0.0, 0.0))
        // Near the start, but slightly closer to the return leg: must stay on the outward leg.
        val prog = t.update(p(200.0, 16.0))
        assertEquals(200.0, prog.progressM, 1.0)
    }
}
