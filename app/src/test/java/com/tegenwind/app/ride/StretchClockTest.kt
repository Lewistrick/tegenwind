package com.tegenwind.app.ride

import com.tegenwind.app.eta.EtaModel
import com.tegenwind.app.eta.EtaSegment
import com.tegenwind.app.eta.FormEstimator
import com.tegenwind.app.eta.SegmentCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StretchClockTest {
    /** Rides at [speed] m/s with a fix every second; returns the stretches that came out. */
    private fun ride(clock: StretchClock, fromM: Double, seconds: Int, startMs: Long = 0, speed: Double = 5.0): List<Stretch> =
        (0..seconds).mapNotNull { clock.advance(fromM + it * speed, startMs + it * 1000L) }

    @Test
    fun aStretchComesOutEveryFiveSecondsOfRiding() {
        val out = ride(StretchClock(), 0.0, 16)
        assertEquals(3, out.size)
        assertEquals(listOf(0.0, 25.0, 50.0), out.map { it.fromM })
        assertTrue(out.all { it.lengthM == 25.0 && it.movingMs == 5_000L })
    }

    @Test
    fun crawlingWaitsForEnoughRoad() {
        // 1 m/s: after 5 s only 5 m, too little to measure a speed on; the stretch runs on to 10 m.
        val out = ride(StretchClock(), 0.0, 12, speed = 1.0)
        assertEquals(1, out.size)
        assertEquals(10.0, out[0].lengthM, 1e-9)
        assertEquals(10_000L, out[0].movingMs)
    }

    @Test
    fun aGapInTheGpsStartsAfresh() {
        val clock = StretchClock()
        ride(clock, 0.0, 3) // 15 m in
        // 200 m further in one step: not ridden as far as we know.
        assertNull(clock.advance(215.0, 4_000))
        val out = ride(clock, 220.0, 4, startMs = 5_000)
        assertEquals(1, out.size)
        assertEquals(215.0, out[0].fromM, 1e-9)
    }

    @Test
    fun leavingTheRouteDropsTheStretchInProgress() {
        val clock = StretchClock()
        ride(clock, 0.0, 3)
        clock.reset()
        assertTrue(ride(clock, 20.0, 4, startMs = 4_000).isEmpty()) // 4 s since coming back
    }

    @Test
    fun expectedTimeOfAStretchFollowsTheSegmentsItCrossesAndTheirShape() {
        val segs = listOf(
            EtaSegment(0.0, 250.0, 90.0, 0.0, 1.0, 0),
            EtaSegment(250.0, 500.0, 90.0, 0.0, 1.0, 0, SegmentCorrection(logMean = kotlin.math.ln(2.0))),
        )
        val model = EtaModel(segs)
        val v = model.speedMps(segs[0], null)
        assertEquals(100 / v, model.movingTimeS(50.0, 150.0, 0, null), 1e-6)
        // Half in the first segment, half in the second, which takes twice as long.
        assertEquals(50 / v + 50 / (v / 2), model.movingTimeS(200.0, 300.0, 0, null), 1e-6)
        // A stretch the profile says is ridden at 80% of the segment's pace takes longer.
        assertEquals(100 / (v * 0.8), model.movingTimeS(50.0, 150.0, 0, null) { _, _ -> 0.8 }, 1e-6)
        // Whole segments add up to what the ETA uses for them.
        assertEquals(250 / v + 250 / (v / 2), model.movingTimeS(0.0, 500.0, 0, null), 1e-6)
    }

    @Test
    fun theStretchesOfASegmentMoveFormAboutAsMuchAsTheSegmentWould() {
        val whole = FormEstimator(prior = 0.9, priorSd = 0.09, obsSd = 0.12)
        whole.observe(1.0)
        val parts = FormEstimator(prior = 0.9, priorSd = 0.09, obsSd = 0.12)
        repeat(10) { parts.observe(1.0, lengthM = 25.0) }
        assertEquals(whole.estimate().mean, parts.estimate().mean, 0.01)
        assertEquals(whole.estimate().variance, parts.estimate().variance, 0.0005)
        // And each one only a little, so form moves in small steps.
        val one = FormEstimator(prior = 0.9, priorSd = 0.09, obsSd = 0.12)
        one.observe(1.0, lengthM = 25.0)
        assertTrue(one.estimate().mean - 0.9 < (whole.estimate().mean - 0.9) * 0.25)
    }

    @Test
    fun aWildShortStretchIsIgnored() {
        val f = FormEstimator(prior = 0.9, priorSd = 0.03, obsSd = 0.12)
        assertFalse(f.observe(1.6, lengthM = 25.0))
        assertEquals(0.9, f.estimate().mean, 1e-12)
        // What a short stretch normally strays is let through.
        assertTrue(f.observe(1.15, lengthM = 25.0))
    }
}
