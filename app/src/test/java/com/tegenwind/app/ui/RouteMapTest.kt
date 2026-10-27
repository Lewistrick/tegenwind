package com.tegenwind.app.ui

import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.ride.Ghost
import com.tegenwind.app.ride.GhostChoice
import com.tegenwind.app.ride.ghostOf
import com.tegenwind.app.ride.pickGhostRide
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.distanceM
import com.tegenwind.app.ui.ride.ghostNote
import com.tegenwind.app.ui.ride.gapSign
import com.tegenwind.app.ui.ride.signedGapText
import com.tegenwind.app.ui.ride.timeGapText
import com.tegenwind.app.ui.rides.gained
import com.tegenwind.app.ui.rides.standingSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteMapTest {
    private val lat0 = 52.0
    private val lon0 = 4.5
    private val mPerDegLat = 110_574.0
    private val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** Point [eastM] east and [northM] north of the origin. */
    private fun p(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon)

    /** 2 km east, then 500 m north. */
    private val line = Polyline(listOf(p(0.0, 0.0), p(2000.0, 0.0), p(2000.0, 500.0)))
    private val spans = listOf(0.0 to 1000.0, 1000.0 to 2000.0, 2000.0 to 2500.0)

    @Test
    fun theRouteFitsTheBoxAtOneScaleWithNorthUp() {
        val m = MapProjection(line.points, 400f, 200f, 20f)
        val a = line.points[0]
        val b = line.points[1]
        val c = line.points[2]
        // East is right, north is up.
        assertTrue(m.x(b) > m.x(a))
        assertTrue(m.y(c) < m.y(b))
        // The same scale both ways: 2000 m across and 500 m up keep their 4:1.
        assertEquals(4.0, ((m.x(b) - m.x(a)) / (m.y(b) - m.y(c))).toDouble(), 0.05)
        // Inside the padding, and filling the width that limits it.
        for (g in line.points) {
            assertTrue(m.x(g) >= 20f - 0.5f && m.x(g) <= 380f + 0.5f)
            assertTrue(m.y(g) >= 20f - 0.5f && m.y(g) <= 180f + 0.5f)
        }
        assertEquals(360.0, (m.x(b) - m.x(a)).toDouble(), 1.0)
    }

    @Test
    fun theMapIsAsTallAsTheRouteNeedsWithinLimits() {
        // 2000 m by 500 m is 4:1: flatter than a card should be.
        assertEquals(2.8f, mapAspect(line.points), 1e-6f)
        assertEquals(2.0f, mapAspect(listOf(p(0.0, 0.0), p(1000.0, 500.0))), 0.01f)
        assertEquals(1.3f, mapAspect(listOf(p(0.0, 0.0), p(100.0, 900.0))), 1e-6f)
    }

    @Test
    fun aTapFindsTheStretchUnderItAndNothingFarAway() {
        val m = MapProjection(line.points, 400f, 200f, 20f)
        fun tapAt(g: GeoPoint, dy: Float = 0f) = nearestStretch(line, spans, m, m.x(g), m.y(g) + dy, 28f)
        assertEquals(0, tapAt(p(400.0, 0.0), dy = 10f))
        assertEquals(1, tapAt(p(1600.0, 0.0)))
        assertEquals(2, tapAt(p(2000.0, 300.0)))
        assertNull(tapAt(p(500.0, 0.0), dy = -80f))
    }

    @Test
    fun aStretchHoldsTheCornersInsideIt() {
        val pts = stretchOf(line, 1500.0, 2250.0)
        assertEquals(3, pts.size)
        assertEquals(0.0, distanceM(pts[1], p(2000.0, 0.0)), 0.5)
        assertEquals(750.0, Polyline(pts).lengthM, 1.0)
    }

    private fun fix(id: Long, t: Long, eastM: Double, speed: Double) =
        p(eastM, 0.0).let { TrackPointEntity(id = id, rideId = 1, timeMs = t, lat = it.lat, lon = it.lon, speedMps = speed, accuracyM = 5.0, altitudeM = null) }

    @Test
    fun aGhostIsWhereTheEarlierRideWasThatLongAfterSettingOff() {
        // 5 m/s east along the first leg, standing for 20 s at 500 m.
        val pts = ArrayList<TrackPointEntity>()
        for (t in 0L..100L) pts += fix(t, t * 1000, 5.0 * t, 5.0)
        for (t in 101L..120L) pts += fix(t, t * 1000, 500.0, 0.0)
        for (t in 121L..420L) pts += fix(t, t * 1000, 500.0 + 5.0 * (t - 120), 5.0)
        // And up the second leg, so the whole route is covered.
        for (t in 421L..520L) {
            val g = p(2000.0, 5.0 * (t - 420))
            pts += TrackPointEntity(id = t, rideId = 1, timeMs = t * 1000, lat = g.lat, lon = g.lon, speedMps = 5.0, accuracyM = 5.0, altitudeM = null)
        }
        val ghost = ghostOf(line, 0, pts)!!
        assertEquals(250.0, ghost.alongAt(50.0), 6.0)
        // Standing counts: 110 s in, it has only just left the 500 m mark.
        assertEquals(500.0, ghost.alongAt(110.0), 30.0)
        assertEquals(line.lengthM, ghost.alongAt(1e6), 6.0)
        assertEquals(1, standingSpots(pts).size)
        // A ride that left the route early is no ghost.
        assertNull(ghostOf(line, 0, pts.take(100)))
    }

    private fun ride(id: Long, startMin: Long, minutes: Long, km: Double = 2.5) =
        RideEntity(id = id, startedAtMs = startMin * 60_000, endedAtMs = (startMin + minutes) * 60_000, distanceM = km * 1000, movingMs = minutes * 60_000)

    @Test
    fun theGhostIsTheBestTheMiddleOrTheLatestWholeRide() {
        val rides = listOf(ride(1, 0, 40), ride(2, 100, 35), ride(3, 200, 38), ride(4, 300, 5, km = 0.4))
        assertEquals(2L, pickGhostRide(rides, GhostChoice.BEST, 2500.0)!!.id)
        assertEquals(3L, pickGhostRide(rides, GhostChoice.MEDIAN, 2500.0)!!.id)
        // The 5-minute ride was broken off: neither the best nor the last.
        assertEquals(3L, pickGhostRide(rides, GhostChoice.LAST, 2500.0)!!.id)
        assertNull(pickGhostRide(emptyList(), GhostChoice.BEST, 2500.0))
    }

    @Test
    fun theExpectedRideIsNoStoredRide() {
        assertNull(pickGhostRide(listOf(ride(1, 0, 40)), GhostChoice.EXPECTED, 2500.0))
    }

    @Test
    fun theGapIsShownAsSignedNumbers() {
        assertEquals("+40 m", signedGapText(42.0))
        assertEquals("−1.2 km", signedGapText(-1234.0))
        assertEquals("+0:08", timeGapText(8.4))
        assertEquals("−1:12", timeGapText(-72.0))
        assertEquals(1, gapSign(30.0, 25.0))
        assertEquals(-1, gapSign(-30.0, 25.0))
        assertEquals(0, gapSign(-25.0, 25.0))
        assertEquals(0, gapSign(5.0, 5.0))
        assertEquals(1, gapSign(5.1, 5.0))
    }

    @Test
    fun theSubtitleOnlySpeaksWhenThereAreNoNumbers() {
        assertNull(ghostNote(hasGhost = true, hasGap = true, choice = GhostChoice.MEDIAN))
        assertEquals("The light arrow is your best ride.", ghostNote(true, false, GhostChoice.BEST))
        assertTrue(ghostNote(false, false, GhostChoice.MEDIAN)!!.startsWith("No earlier ride"))
        assertNull(ghostNote(false, false, GhostChoice.EXPECTED))
    }

    @Test
    fun aGhostSaysWhenItFirstReachedASpot() {
        // 10 m/s to 100 m, standing for 10 s, then 10 m/s on to 200 m.
        val ghost = Ghost(doubleArrayOf(0.0, 10.0, 20.0, 30.0), doubleArrayOf(0.0, 100.0, 100.0, 200.0))
        assertEquals(5.0, ghost.elapsedAt(50.0)!!, 1e-9)
        // It stood at 100 m from 10 s to 20 s: the first moment is the one that counts.
        assertEquals(10.0, ghost.elapsedAt(100.0)!!, 1e-9)
        assertEquals(25.0, ghost.elapsedAt(150.0)!!, 1e-9)
        assertEquals(0.0, ghost.elapsedAt(-5.0)!!, 1e-9)
        assertNull(ghost.elapsedAt(200.5))
    }

    @Test
    fun aPassGainsOrLosesAShareOfWhatWasExpected() {
        fun pass(moving: Long, expected: Long?) = SegmentTraversalEntity(1, 1, 0, 0, moving, moving, null, expected ?: 0, expected)
        assertEquals(0.1, gained(pass(36_000, 40_000))!!, 1e-9)
        assertEquals(-0.25, gained(pass(50_000, 40_000))!!, 1e-9)
        assertNull(gained(pass(50_000, null)))
        assertTrue(abs(gained(pass(40_000, 40_000))!!) < 1e-9)
    }
}
