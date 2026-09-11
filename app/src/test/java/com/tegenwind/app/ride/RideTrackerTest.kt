package com.tegenwind.app.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackerTest {

    private val lat0 = 52.09
    private val lon0 = 5.12
    private val metersPerDegLat = 111_195.0

    private fun fixAt(sec: Long, northM: Double, speedMps: Double?, accuracy: Double = 5.0) =
        Fix(sec * 1000, lat0 + northM / metersPerDegLat, lon0, speedMps, accuracy)

    @Test
    fun countsDistanceAndMovingTimeWhileRiding() {
        val t = RideTracker(0)
        for (s in 0L..9L) assertTrue(t.add(fixAt(s, s * 5.0, 5.0)))
        val snap = t.snapshot()
        assertEquals(45.0, snap.distanceM, 0.5)
        assertEquals(9_000, snap.movingMs)
        assertEquals(18.0, snap.speedKmh!!, 1e-9)
        assertFalse(snap.paused)
    }

    @Test
    fun ignoresJitterAndPausesWhenStandingStill() {
        val t = RideTracker(0)
        // Standing at a traffic light: position wobbles by a few metres, GPS speed near zero.
        for (s in 0L..10L) t.add(fixAt(s, if (s % 2 == 0L) 0.0 else 3.0, 0.2))
        val snap = t.snapshot()
        assertEquals(0.0, snap.distanceM, 1e-9)
        assertTrue(snap.paused)
        // Stopped from the first fix at 0 s; seconds 1–4 still count, the pause starts at 5 s.
        assertEquals(4_000, snap.movingMs)
        assertEquals(0L, snap.pausedSinceMs) // the stop began at the first fix
        t.add(fixAt(11, 20.0, 5.0))
        assertEquals(null, t.snapshot().pausedSinceMs) // riding again
    }

    @Test
    fun rejectsInaccurateAndOutOfOrderFixes() {
        val t = RideTracker(0)
        assertTrue(t.add(fixAt(0, 0.0, 5.0)))
        assertFalse(t.add(fixAt(1, 5.0, 5.0, accuracy = 35.0)))
        assertFalse(t.add(fixAt(0, 5.0, 5.0)))
        assertEquals(1, t.snapshot().speeds.size)
    }

    @Test
    fun capsMovingTimeOverGpsGaps() {
        val t = RideTracker(0)
        t.add(fixAt(0, 0.0, 6.0))
        t.add(fixAt(60, 360.0, 6.0)) // one minute without GPS, e.g. an underpass
        assertEquals(RideTracker.MAX_GAP_MS, t.snapshot().movingMs)
    }

    @Test
    fun rollingAverageUsesTimeWindow() {
        val samples = listOf(Sample(0, 10.0), Sample(60_000, 20.0), Sample(130_000, 30.0))
        val avg = rollingAverage(samples, TWO_MINUTES_MS)
        assertEquals(10.0, avg[0], 1e-9)
        assertEquals(15.0, avg[1], 1e-9)
        assertEquals(25.0, avg[2], 1e-9) // the 0 s sample fell out of the 2-minute window

        val window = RollingWindow(TWO_MINUTES_MS)
        samples.forEachIndexed { i, s -> assertEquals(avg[i], window.add(s.timeMs, s.value), 1e-9) }
    }
}
