package com.tegenwind.app.ui

import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingMapTest {
    private val lat0 = 52.0
    private val lon0 = 4.5
    private val mPerDegLat = 110_574.0
    private val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))
    private fun p(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon)

    @Test
    fun theWayYouAreGoingPointsUp() {
        // Riding east: east is up, north is to the left.
        val v = HeadingView(p(0.0, 0.0), 90.0, aheadM = 100.0, widthPx = 400f, heightPx = 500f)
        val you = v.at(p(0.0, 0.0))
        assertEquals(200f, you.x, 0.5f)
        assertEquals(500f * HeadingView.YOU_AT, you.y, 0.5f)
        val ahead = v.at(p(100.0, 0.0))
        assertEquals(200f, ahead.x, 0.5f)
        assertEquals(0f, ahead.y, 0.5f) // 100 m ahead is the top edge
        val north = v.at(p(0.0, 50.0))
        assertTrue(north.x < you.x)
        assertEquals(you.y, north.y, 0.5f)
        // Riding north, the map is north up.
        val n = HeadingView(p(0.0, 0.0), 0.0, 100.0, 400f, 500f)
        assertTrue(n.at(p(30.0, 0.0)).x > 200f)
    }

    @Test
    fun turningGoesTheShortWayRound() {
        assertEquals(20.0, shortestTurn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, shortestTurn(10.0, 350.0), 1e-9)
        assertEquals(90.0, shortestTurn(0.0, 90.0), 1e-9)
        assertEquals(-90.0, shortestTurn(720.0 + 90.0, 0.0), 1e-9)
    }

    @Test
    fun theZoomFollowsSpeedWithinLimits() {
        assertEquals(MIN_AHEAD_M, aheadM(0.0, null), 1e-9)
        assertEquals(MIN_AHEAD_M, aheadM(null, null), 1e-9)
        assertEquals(20 / 3.6 * AHEAD_S, aheadM(20 / 3.6, null), 1e-6) // about 250 m at 20 km/h
        assertEquals(MAX_AHEAD_M, aheadM(30.0, null), 1e-9)
        // A ghost 300 m off widens the view to keep it in; one far away doesn't.
        assertEquals(390.0, aheadM(20 / 3.6, 300.0), 1e-6)
        assertEquals(20 / 3.6 * AHEAD_S, aheadM(20 / 3.6, -2000.0), 1e-6)
    }

    @Test
    fun theRoadsDirectionIgnoresAKink() {
        val line = Polyline(listOf(p(0.0, 0.0), p(100.0, 0.0), p(101.0, 3.0), p(102.0, 0.0), p(300.0, 0.0)))
        assertEquals(90.0, routeBearingAt(line, 100.0), 3.0)
    }

    @Test
    fun aZigzagOfAFewMetresBarelyTurnsTheMap() {
        // A straight east road with a 15 m long, 3 m sideways jog in it: the map hardly turns.
        val line = Polyline(listOf(p(0.0, 0.0), p(200.0, 0.0), p(205.0, 3.0), p(215.0, 3.0), p(220.0, 0.0), p(500.0, 0.0)))
        var worst = 0.0
        var at = 150.0
        while (at <= 280.0) {
            worst = maxOf(worst, kotlin.math.abs(shortestTurn(90.0, routeBearingAt(line, at))))
            at += 2.5
        }
        assertTrue("turned $worst°", worst < 6.0)
    }
}
