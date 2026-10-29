package com.tegenwind.app.ui.rides

import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideTrackTest {
    /** About 1 m of latitude. */
    private val m = 1.0 / 111_195

    /** A route 1 km due north, in four 250 m segments. */
    private val line = Polyline(listOf(GeoPoint(52.0, 5.0), GeoPoint(52.0 + 1000 * m, 5.0)))
    private val spans = listOf(0.0 to 250.0, 250.0 to 500.0, 500.0 to 750.0, 750.0 to 1000.0)

    @Test
    fun eachFixIsOnTheSegmentItWasRiddenOn() {
        val track = listOf(10.0, 240.0, 260.0, 600.0, 990.0).map { GeoPoint(52.0 + it * m, 5.0) }
        assertEquals(listOf(0, 0, 1, 2, 3), segmentsAlong(track, line, spans))
    }

    @Test
    fun beforeTheRouteAndOffItAFixIsOnNoSegment() {
        // Riding up from 100 m south-east, then a detour 200 m to the east halfway.
        val east = 1.0 / (111_320 * kotlin.math.cos(Math.toRadians(52.0)))
        val track = listOf(
            GeoPoint(52.0 - 100 * m, 5.0 + 100 * east),
            GeoPoint(52.0 + 100 * m, 5.0),
            GeoPoint(52.0 + 400 * m, 5.0 + 200 * east),
            GeoPoint(52.0 + 800 * m, 5.0),
        )
        val along = segmentsAlong(track, line, spans)
        assertNull(along[0])
        assertEquals(0, along[1])
        assertNull(along[2])
        assertEquals(3, along[3])
    }

    @Test
    fun aRouteWithoutSegmentsHasNoneToGive() {
        assertEquals(listOf(null, null), segmentsAlong(listOf(GeoPoint(52.0, 5.0), GeoPoint(52.0 + 5 * m, 5.0)), line, emptyList()))
    }

    @Test
    fun speedIsSmoothedOverTheFixesAround() {
        val smooth = smoothSpeeds(listOf(5.0, 5.0, 10.0, 5.0, 5.0, null, 5.0), half = 1)
        assertEquals(5.0, smooth[0]!!, 1e-9)
        assertEquals(20.0 / 3, smooth[1]!!, 1e-9)
        assertEquals(20.0 / 3, smooth[2]!!, 1e-9)
        assertNull(smooth[5])
        // A fix without speed doesn't count for its neighbours.
        assertEquals(5.0, smooth[4]!!, 1e-9)
    }

    @Test
    fun speedIsColouredAgainstTheRidesOwnMedianInQuarterSteps() {
        // Median of the moving fixes: 6 m/s. A quarter is 1.5 m/s.
        val amounts = speedAmounts(listOf(6.0, 7.5, 9.0, 4.5, 0.0, 5.6, null, 6.0, 6.0))
        assertEquals(0.0, amounts[0]!!, 1e-9)
        assertEquals(1.0, amounts[1]!!, 1e-9)
        assertEquals(1.0, amounts[2]!!, 1e-9)
        assertEquals(-1.0, amounts[3]!!, 1e-9)
        // Standing still is as slow as it gets.
        assertEquals(-1.0, amounts[4]!!, 1e-9)
        // 0.4 below: just over a quarter of the way, in quarter steps.
        assertEquals(-0.25, amounts[5]!!, 1e-9)
        assertNull(amounts[6])
    }

    @Test
    fun aRideThatNeverMovedHasNoSpeedColours() {
        assertEquals(listOf(null, null), speedAmounts(listOf(0.0, 0.1)))
    }

    @Test
    fun runsJoinWhereTheColourChanges() {
        assertEquals(
            listOf(TrackRun(0, 2, "a"), TrackRun(2, 3, null), TrackRun(3, 5, "b")),
            runsOf(listOf("a", "a", null, "b", "b", "b")),
        )
        // A last fix in a colour of its own is drawn as the end of the run before.
        assertEquals(listOf(TrackRun(0, 2, "a")), runsOf(listOf("a", "a", "b")))
        assertEquals(emptyList<TrackRun<String>>(), runsOf(listOf("a")))
    }

    @Test
    fun distancesAddUp() {
        val track = listOf(0.0, 100.0, 250.0).map { GeoPoint(52.0 + it * m, 5.0) }
        val along = distancesAlong(track)
        assertEquals(0.0, along[0], 0.0)
        assertEquals(100.0, along[1], 0.5)
        assertEquals(250.0, along[2], 0.5)
    }
}
