package com.tegenwind.app.routes

import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.data.RouteSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import kotlin.math.cos

class RouteMatcherTest {

    private val lat0 = 52.09
    private val lon0 = 5.12

    private fun at(eastM: Double, northM: Double) = GeoPoint(
        lat0 + northM / 111_195.0,
        lon0 + eastM / (111_195.0 * cos(Math.toRadians(lat0))),
    )

    private fun route(id: Long, name: String, points: List<GeoPoint>): LoadedRoute {
        val line = Polyline(points)
        val count = (line.lengthM / 250.0).toInt().coerceAtLeast(1)
        val segments = (0 until count).map { i ->
            val startM = i * line.lengthM / count
            val endM = (i + 1) * line.lengthM / count
            RouteSegmentEntity(
                routeId = id,
                idx = i,
                startM = startM,
                endM = endM,
                bearingDeg = bearingDeg(line.pointAt(startM), line.pointAt(endM)),
            )
        }
        return LoadedRoute(RouteEntity(id, name, line.lengthM, 0), line, segments)
    }

    /** Two kilometres due east, and the same road ridden home again. */
    private fun eastAndBack(): Pair<LoadedRoute, LoadedRoute> {
        val out = listOf(at(0.0, 0.0), at(1000.0, 0.0), at(2000.0, 0.0))
        return route(1, "woon-werk", out) to route(2, "werk-woon", out.reversed())
    }

    @Test
    fun theMirrorRouteIsRuledOutByWhichEndYouStartFrom() {
        val (out, back) = eastAndBack()
        val m = RouteMatcher(listOf(out, back))
        m.onFix(at(0.0, 0.0), headingDeg = null)
        assertEquals("woon-werk", m.leader?.route?.route?.name)
        // Standing at the far end of the way back is not the same as riding it.
        assertTrue(m.locked)
    }

    @Test
    fun ridingTheOtherWayPicksTheMirrorRoute() {
        val (out, back) = eastAndBack()
        val m = RouteMatcher(listOf(out, back))
        m.onFix(at(2000.0, 0.0), headingDeg = 270.0) // at work, heading west
        assertEquals("werk-woon", m.leader?.route?.route?.name)
        assertTrue(m.locked)
    }

    @Test
    fun routesSharingTheirStartAreOnlyToldApartWhereTheyPart() {
        val shared = listOf(at(0.0, 0.0), at(1000.0, 0.0))
        val eastward = route(1, "east", shared + at(2000.0, 0.0))
        val northward = route(2, "north", shared + at(1000.0, 1000.0))
        val m = RouteMatcher(listOf(eastward, northward))

        m.onFix(at(0.0, 0.0), 90.0)
        m.onFix(at(500.0, 0.0), 90.0)
        assertFalse("both are still possible on the shared stretch", m.locked)
        assertTrue(m.leader != null) // there is always an ETA on screen

        // Turning north at the fork rules out the eastward one.
        m.onFix(at(1000.0, 200.0), 0.0)
        m.onFix(at(1000.0, 400.0), 0.0)
        assertTrue(m.locked)
        assertEquals("north", m.leader?.route?.route?.name)
    }

    @Test
    fun ridingSomewhereElseEntirelyMeansAFreeRide() {
        val (out, back) = eastAndBack()
        val m = RouteMatcher(listOf(out, back))
        m.onFix(at(0.0, 5_000.0), 0.0) // five kilometres off both
        assertNull(m.leader)
        assertTrue(m.exhausted)
        assertFalse(m.locked)
    }

    @Test
    fun leavingTheRouteMidRideFallsBackToAFreeRide() {
        val (out, back) = eastAndBack()
        val m = RouteMatcher(listOf(out, back))
        m.onFix(at(0.0, 0.0), 90.0)
        m.onFix(at(500.0, 0.0), 90.0)
        assertTrue(m.locked)
        // Turn off and keep going: the only route left stops fitting.
        for (north in 100..600 step 100) m.onFix(at(500.0, north.toDouble()), 0.0)
        assertTrue(m.exhausted)
        assertNull(m.leader)
    }

    @Test
    fun whatYouUsuallyRideAtThisHourBreaksTheTie() {
        val shared = listOf(at(0.0, 0.0), at(1000.0, 0.0))
        val eastward = route(1, "east", shared + at(2000.0, 0.0))
        val northward = route(2, "north", shared + at(1000.0, 1000.0))
        val habit = mapOf(2L to 5) // the north one is the usual 8 o'clock ride
        val m = RouteMatcher(listOf(eastward, northward), habit)
        m.onFix(at(0.0, 0.0), 90.0)
        assertFalse(m.locked)
        assertEquals("north", m.leader?.route?.route?.name)
    }

    @Test
    fun habitCountsRidesAroundThisTimeOfDay() {
        val day = 24 * 60 * 60 * 1000L
        val eightAm = 8 * 60 * 60 * 1000L
        val starts = listOf(
            RouteStart(1, eightAm),
            RouteStart(1, day + eightAm + 20 * 60_000L), // 08:20 the next day
            RouteStart(2, eightAm + 6 * 60 * 60_000L), // an afternoon ride
        )
        val habit = habitByRoute(starts, 3 * day + eightAm, ZoneOffset.UTC)
        assertEquals(2, habit[1])
        assertNull(habit[2])
    }

    @Test
    fun habitLooksAcrossMidnight() {
        val day = 24 * 60 * 60 * 1000L
        val nearMidnight = 23 * 60 * 60 * 1000L + 45 * 60_000L
        val justAfter = 15 * 60_000L
        val habit = habitByRoute(listOf(RouteStart(1, nearMidnight)), day + justAfter, ZoneOffset.UTC)
        assertEquals(1, habit[1])
    }
}
