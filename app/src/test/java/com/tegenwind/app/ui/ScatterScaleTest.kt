package com.tegenwind.app.ui

import com.tegenwind.app.ride.FormDot
import com.tegenwind.app.ride.FormTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScatterScaleTest {
    private val t0 = 1_000_000_000L
    private fun dot(i: Int, arrivalMin: Double, form: Double) = FormDot(t0 + i * 5_000L, (arrivalMin * 60_000).toLong(), form)

    @Test
    fun noDotsNoAxes() = assertNull(ScatterScale.fit(emptyList()))

    @Test
    fun everyDotFitsAndTheNewestSitsInTheMiddle() {
        val dots = listOf(dot(0, 100.0, 0.95), dot(1, 103.5, 0.90), dot(2, 101.0, 0.86), dot(3, 102.0, 0.88))
        val (x, y) = ScatterScale.fit(dots)!!
        for (d in dots) {
            assertTrue(ScatterScale.minutes(d) in x.lo..x.hi)
            assertTrue(ScatterScale.percent(d) in y.lo..y.hi)
        }
        val newest = dots.last()
        assertEquals(ScatterScale.minutes(newest), (x.lo + x.hi) / 2, 1e-9)
        assertEquals(ScatterScale.percent(newest), (y.lo + y.hi) / 2, 1e-9)
    }

    @Test
    fun aSteadyRiderKeepsAMinimumRange() {
        val dots = List(5) { dot(it, 100.0, 0.9) }
        val (x, y) = ScatterScale.fit(dots)!!
        assertTrue(x.hi - x.lo >= 2 * ScatterScale.MIN_HALF_MINUTES - 1e-9)
        assertTrue(y.hi - y.lo >= 2 * ScatterScale.MIN_HALF_PERCENT - 1e-9)
    }

    @Test
    fun ticksAreWholeNumbersInsideTheRange() {
        val (x, y) = ScatterScale.fit(listOf(dot(0, 100.2, 0.93), dot(1, 102.9, 0.88)))!!
        for (axis in listOf(x, y)) {
            assertTrue(axis.ticks().isNotEmpty())
            for (v in axis.ticks()) {
                assertEquals(Math.rint(v), v, 1e-9)
                assertTrue(v >= axis.lo - 1e-9 && v <= axis.hi + 1e-9)
            }
        }
    }

    @Test
    fun theTrailKeepsAMinuteOfDotsFiveSecondsApart() {
        val trail = FormTrail()
        for (s in 0..200) trail.record(t0 + s * 1_000L, 0, 0.9)
        val dots = trail.dots
        assertTrue(dots.size <= 13)
        assertTrue(dots.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 5_000 })
        assertTrue(dots.last().timeMs - dots.first().timeMs <= 60_000)
        trail.clear()
        assertTrue(trail.dots.isEmpty())
    }
}
