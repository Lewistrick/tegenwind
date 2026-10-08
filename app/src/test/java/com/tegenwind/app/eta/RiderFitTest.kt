package com.tegenwind.app.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RiderFitTest {
    private val params = RiderParams()
    private val rho = Physics.airDensity(null)

    /** Passes of a rider who feels [feel] of the modelled headwind, with a bit of noise. */
    private fun passes(feel: Double, rides: Int = 12, segments: Int = 14): List<WindPass> {
        val rnd = Random(7)
        val out = ArrayList<WindPass>()
        for (r in 0 until rides) {
            val pace = 1 + 0.1 * rnd.nextGaussian()
            val windToday = rnd.nextDouble() * 8
            for (s in 0 until segments) {
                val head = windToday * Math.cos(s * 0.9)
                val grade = (s % 3 - 1) * 0.6
                val v = Physics.speedMps(params.powerW, grade, head * feel, rho, params) * pace
                out += WindPass(r.toLong(), s.toLong(), 250.0, grade, head, 250.0 / v * Math.exp(0.03 * rnd.nextGaussian()))
            }
        }
        return out
    }

    @Test
    fun windFeelFindsHowMuchOfTheWindARiderFeels() {
        assertEquals(0.3, RiderFit.windFeel(passes(0.3)), 0.15)
        assertEquals(1.0, RiderFit.windFeel(passes(1.0)), 0.15)
    }

    @Test
    fun windFeelStaysAtPhysicsWithoutEnoughRides() {
        assertEquals(1.0, RiderFit.windFeel(passes(0.3, rides = 3)), 0.0)
        assertEquals(1.0, RiderFit.windFeel(emptyList()), 0.0)
    }

    @Test
    fun formSpreadFollowsTheRidesAndKeepsTheOldGuessWhenThereAreNone() {
        assertEquals(RiderLearned.DEFAULT_FORM_SD, RiderFit.formSd(emptyList()), 1e-9)
        val steady = List(25) { 0.9 }
        val jumpy = List(25) { if (it % 2 == 0) 0.7 else 1.1 }
        assertTrue(RiderFit.formSd(steady) < 0.06)
        assertTrue(RiderFit.formSd(jumpy) > 0.14)
    }

    @Test
    fun passNoiseIsWhatPassesActuallyStrayFromExpectation() {
        val exact = List(200) { PassFacts(40_000, 40_000) }
        assertTrue(RiderFit.passNoise(exact, 0.0).first < 0.07)
        val off = List(200) { PassFacts(if (it % 2 == 0) 50_000 else 32_000, 40_000) }
        assertTrue(RiderFit.passNoise(off, 0.0).first > 0.19)
    }

    @Test
    fun formDriftIsWhatAverageOfRunsVaryBeyondNoise() {
        val rnd = Random(3)
        // Form that never moves: only noise on each pass.
        val still = List(40) { List(36) { 0.13 * rnd.nextGaussian() } }
        // Form that wanders: a random walk on top of the same noise.
        val wandering = List(40) {
            var f = 0.0
            List(36) { f += 0.04 * rnd.nextGaussian(); f + 0.13 * rnd.nextGaussian() }
        }
        assertTrue(RiderFit.formDrift(still) < 0.0003)
        assertTrue(RiderFit.formDrift(wandering) > 0.0008)
        assertEquals(RiderLearned.DEFAULT_FORM_DRIFT, RiderFit.formDrift(emptyList()), 1e-12)
    }

    @Test
    fun aSteadierFormGetsSureFasterWithinARide() {
        fun sdAfter(drift: Double): Double {
            val f = FormEstimator(prior = 0.9, priorSd = 0.09, obsSd = 0.12, drift = drift)
            repeat(12) { f.observe(0.9) }
            return Math.sqrt(f.estimate().variance)
        }
        assertTrue(sdAfter(0.00005) < sdAfter(0.0004))
    }

    @Test
    fun stopsAreLearnedPerLight() {
        val rides = List(30) { RideFacts(0.9, stoppedS = 20.0, signals = 4) } // 5 s per light
        val (mean, variance) = RiderFit.stops(rides)
        assertEquals(5.0, mean, 1.5)
        assertTrue(variance < 225.0)
        assertEquals(12.0, RiderFit.stops(emptyList()).first, 1e-9)
    }

    @Test
    fun theModelFeelsLessWindWhenLearnedSo() {
        val seg = EtaSegment(0.0, 1000.0, 90.0, 0.0, 1.0, 0)
        val wind = com.tegenwind.app.weather.WindSample(0, 6.0, 90.0, null)
        val full = EtaModel(listOf(seg)).speedMps(seg, wind)
        val felt = EtaModel(listOf(seg), learned = RiderLearned(windFeel = 0.3)).speedMps(seg, wind)
        val calm = EtaModel(listOf(seg)).speedMps(seg, null)
        assertTrue(full < felt && felt < calm)
    }
}
