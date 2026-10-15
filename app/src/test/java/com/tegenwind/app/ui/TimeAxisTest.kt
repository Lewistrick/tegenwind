package com.tegenwind.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeAxisTest {

    private val start = 1_000_000L
    private val end = start + 20 * 60_000L

    @Test
    fun theLastTwoMinutesRunLinearlyUpToNow() {
        val axis = TimeAxis(start, end, windowMs = 120_000L, endMs = end)
        assertEquals(0f, axis.fraction(end - 120_000L), 1e-6f)
        assertEquals(0.5f, axis.fraction(end - 60_000L), 1e-6f)
        assertEquals(1f, axis.fraction(end), 1e-6f)
    }

    @Test
    fun theWholeRideOnALogScaleGivesTheLatestStretchTheMostRoom() {
        val axis = TimeAxis(start, end, logXWhenFull = true)
        assertEquals(0f, axis.fraction(start), 1e-6f)
        assertEquals(1f, axis.fraction(end), 1e-6f)
        // The last minute takes far more than its 5% share of the width.
        assertTrue(axis.fraction(end - 60_000L) < 0.8f)
        assertTrue(axis.fraction(end - 60_000L) > axis.fraction(end - 10 * 60_000L))
    }

    @Test
    fun twoChartsBuiltFromTheSameThingsAgreeOnEveryMoment() {
        val speed = TimeAxis(start, end, null, null, logXWhenFull = true)
        val arrival = TimeAxis(start, end, null, null, logXWhenFull = true)
        for (t in start..end step 37_000L) assertEquals(speed.fraction(t), arrival.fraction(t), 0f)
    }
}
