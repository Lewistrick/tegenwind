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
    fun theAxisHoldsEveryBandOnRoundTimes() {
        val points = listOf(
            ArrivalPoint(t0, 100 * 60_000L + 20_000, 5 * 60_000L),
            ArrivalPoint(t0 + 5_000, 101 * 60_000L, 30_000L),
        )
        val axis = arrivalAxis(points)!!
        for (p in points) {
            assertTrue((p.arrivalMs - p.bandMs) / 1000.0 >= axis.lo)
            assertTrue((p.arrivalMs + p.bandMs) / 1000.0 <= axis.hi)
        }
        // A band of ten minutes: minutes apart, with the hour on the labels.
        assertEquals(300.0, axis.step, 0.0)
        assertTrue(!axis.inSeconds)
        assertEquals(0.0, axis.lo % axis.step, 1e-9)
    }

    @Test
    fun aBandUnderAMinuteGetsTicksInSecondsWithoutTheHour() {
        // Arrival 07:59:55 ± 20 s: from 07:59:35 to 08:00:15, 40 s in all.
        val at = 8 * 3_600_000L - 5_000
        val axis = arrivalAxis(listOf(ArrivalPoint(t0, at, 20_000L), ArrivalPoint(t0 + 5_000, at + 4_000, 18_000L)))!!
        assertTrue(axis.inSeconds)
        assertEquals(15.0, axis.step, 0.0)
        // The axis steps over the hour: 59:30 ... 00:15 etc, on multiples of the step.
        assertEquals(0.0, axis.lo % 15.0, 1e-9)
        assertTrue(axis.hi - axis.lo >= 2 * axis.step)
    }

    @Test
    fun ticksAreRoundedToSecondsAndShowOnlyMinutesAndSeconds() {
        val utc = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            // 07:59:30 and 08:00:45 as seconds since 1970 on some day.
            val day = 20_000L * 86_400
            val axis = ArrivalAxis(lo = (day + 7 * 3600 + 59 * 60 + 30).toDouble(), hi = (day + 8 * 3600 + 45).toDouble(), step = 15.0)
            assertEquals(":59:30", axis.label(axis.lo))
            assertEquals(":00:00", axis.label(axis.lo + 30))
            assertEquals(":00:45", axis.label(axis.hi))
            // From a minute apart on, it is clock time with the hour.
            assertEquals("07:59", ArrivalAxis(axis.lo, axis.hi, 60.0).label(axis.lo - 30))
        } finally {
            java.util.TimeZone.setDefault(utc)
        }
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
