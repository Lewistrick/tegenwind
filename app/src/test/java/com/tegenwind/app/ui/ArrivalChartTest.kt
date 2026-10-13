package com.tegenwind.app.ui

import com.tegenwind.app.ride.ArrivalPoint
import com.tegenwind.app.ride.ArrivalTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalChartTest {
    private val t0 = 1_000_000_000L

    @Test
    fun noPointsNoAxis() = assertNull(arrivalAxis(emptyList()))

    @Test
    fun theAxisHoldsEveryBandOnWholeMinutes() {
        val points = listOf(
            ArrivalPoint(t0, 100 * 60_000L + 20_000, 5 * 60_000L),
            ArrivalPoint(t0 + 5_000, 101 * 60_000L, 30_000L),
        )
        val axis = arrivalAxis(points)!!
        for (p in points) {
            assertTrue((p.arrivalMs - p.bandMs) / 60_000.0 >= axis.lo)
            assertTrue((p.arrivalMs + p.bandMs) / 60_000.0 <= axis.hi)
        }
        assertEquals(Math.rint(axis.lo), axis.lo, 1e-9)
        assertEquals(Math.rint(axis.step), axis.step, 1e-9)
        assertTrue(axis.step >= 1)
    }

    @Test
    fun theTrailKeepsTheWholeRideFiveSecondsApart() {
        val trail = ArrivalTrail()
        for (s in 0..200) trail.record(t0 + s * 1_000L, 0, 60_000)
        val points = trail.points
        assertEquals(41, points.size)
        assertTrue(points.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 5_000 })
        trail.clear()
        assertTrue(trail.points.isEmpty())
    }
}
