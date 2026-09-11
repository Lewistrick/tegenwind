package com.tegenwind.app.hrprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ProbeMathTest {

    private val t0 = Instant.parse("2026-09-12T06:17:00Z")
    private fun at(sec: Long) = t0.plusSeconds(sec)

    @Test
    fun percentileInterpolates() {
        val v = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(25.0, ProbeMath.percentile(v, 0.5)!!, 1e-9)
        assertEquals(37.0, ProbeMath.percentile(v, 0.9)!!, 1e-9)
        assertNull(ProbeMath.percentile(emptyList(), 0.5))
    }

    @Test
    fun delaysAreWriteTimeMinusReadingTime() {
        // Readings every 5 s from 0..55 s, written to Health Connect at 180 s.
        val batch = HrBatch("r1", "com.withings.wiscale2", at(180), (0L..55L step 5).map { HrPoint(at(it), 140) })
        val stats = ProbeMath.stats(listOf(batch))
        assertEquals(12, stats.sampleCount)
        assertEquals(5.0, stats.medianIntervalSec!!, 1e-9)
        assertEquals(180.0, stats.delayMaxSec!!, 1e-9)
        assertEquals(152.5, stats.delayMedianSec!!, 1e-9)
        assertEquals(Verdict.NEAR_LIVE, ProbeMath.verdict(stats))
    }

    @Test
    fun keepsNegativeDelaysVisible() {
        // Stored 12 s before the reading's own timestamp, as seen from the Steel HR.
        val batch = HrBatch("r1", "com.withings.wiscale2", at(0), listOf(HrPoint(at(12), 77)))
        assertEquals(-12.0, batch.delaysSec.single(), 1e-9)
    }

    @Test
    fun verdictThresholds() {
        fun verdictFor(delay: Long) = ProbeMath.verdict(
            ProbeMath.stats(listOf(HrBatch("r", "s", at(delay), listOf(HrPoint(at(0), 120)))))
        )
        assertEquals(Verdict.LIVE_ENOUGH, verdictFor(90))
        assertEquals(Verdict.NEAR_LIVE, verdictFor(400))
        assertEquals(Verdict.TOO_LATE, verdictFor(900))
        assertEquals(Verdict.NO_DATA, ProbeMath.verdict(ProbeMath.stats(emptyList())))
    }

    @Test
    fun formatsDurations() {
        assertEquals("1:15", ProbeMath.formatDuration(75.0))
        assertEquals("1:02:05", ProbeMath.formatDuration(3725.0))
        assertEquals("−0:12", ProbeMath.formatDuration(-12.0))
        assertEquals("–", ProbeMath.formatDuration(null))
    }
}
