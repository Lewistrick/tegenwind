package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackToRouteTest {

    private val lat0 = 51.5
    private val lon0 = 4.2
    private val metersPerDegLat = 111_195.0

    private fun north(m: Double) = GeoPoint(lat0 + m / metersPerDegLat, lon0)

    @Test
    fun keepsTheShapeAndDropsTheWobble() {
        // A straight kilometre, one fix per second, with a metre of GPS noise on each.
        val track = (0..1000).map { m ->
            GeoPoint(north(m.toDouble()).lat, lon0 + (if (m % 2 == 0) 1e-5 else -1e-5))
        }
        val line = lineFromTrack(track)
        assertEquals(1000.0, line.lengthM, 20.0)
        assertTrue("kept ${line.points.size} of ${track.size} points", line.points.size < track.size / 10)
    }

    @Test
    fun refusesATrackTooShortToBeARoute() {
        val standingStill = List(60) { north(0.0) }
        assertThrows(IllegalArgumentException::class.java) { lineFromTrack(standingStill) }
        assertThrows(IllegalArgumentException::class.java) { lineFromTrack(emptyList()) }

        val aroundTheBlock = (0..(MIN_ROUTE_LENGTH_M.toInt() - 20)).map { north(it.toDouble()) }
        assertThrows(IllegalArgumentException::class.java) { lineFromTrack(aroundTheBlock) }
    }
}
