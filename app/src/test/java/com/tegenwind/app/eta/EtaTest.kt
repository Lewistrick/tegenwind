package com.tegenwind.app.eta

import com.tegenwind.app.weather.WindForecast
import com.tegenwind.app.weather.WindSample
import com.tegenwind.app.weather.beaufort
import com.tegenwind.app.weather.compassPoint
import com.tegenwind.app.weather.lerpAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaTest {

    private val p = RiderParams()
    private val rho = Physics.airDensity(15.0)

    @Test
    fun cruisesAround25KmhOnFlatCalmRoad() {
        val kmh = Physics.speedMps(p.powerW, 0.0, 0.0, rho, p) * 3.6
        assertTrue("got $kmh", kmh in 23.0..27.0)
    }

    @Test
    fun headwindAndClimbsSlowYouDownTailwindSpeedsYouUp() {
        val calm = Physics.speedMps(p.powerW, 0.0, 0.0, rho, p)
        assertTrue(Physics.speedMps(p.powerW, 0.0, 5.0, rho, p) < calm - 1.5)
        assertTrue(Physics.speedMps(p.powerW, 0.0, -5.0, rho, p) > calm + 1.0)
        assertTrue(Physics.speedMps(p.powerW, 3.0, 0.0, rho, p) < calm - 1.0)
    }

    @Test
    fun openFieldsFeelMoreWindThanTown() {
        val open = Physics.riderWindFactor(1.0)
        val town = Physics.riderWindFactor(0.15)
        assertTrue("open $open", open in 0.6..0.75)
        assertTrue("town $town", town in 0.2..0.4)
        // Wind from the north while riding north = full headwind; riding east = none.
        assertEquals(10 * open, Physics.headwindMps(10.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(0.0, Physics.headwindMps(10.0, 0.0, 90.0, 1.0), 1e-9)
        assertTrue(Physics.headwindMps(10.0, 180.0, 0.0, 1.0) < 0) // wind from behind
    }

    @Test
    fun interpolatesWindThroughNorth() {
        assertEquals(0.0, lerpAngle(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, lerpAngle(10.0, 340.0, 0.5), 1e-9)
        val f = WindForecast(listOf(WindSample(0, 4.0, 350.0, 10.0), WindSample(900_000, 8.0, 10.0, 12.0)))
        val mid = f.at(450_000)
        assertEquals(6.0, mid.speedMps, 1e-9)
        assertEquals(0.0, mid.fromDeg, 1e-9)
        assertEquals(11.0, mid.tempC!!, 1e-9)
        assertEquals(8.0, f.at(5_000_000).speedMps, 1e-9) // beyond the forecast: last value
    }

    @Test
    fun namesWind() {
        assertEquals("SW", compassPoint(225.0))
        assertEquals("N", compassPoint(355.0))
        assertEquals(4, beaufort(6.0))
        assertEquals(0, beaufort(0.1))
    }

    private fun flatRoute(bearing: Double, exposure: Double = 1.0, signals: Int = 0) =
        (0 until 10).map { i -> EtaSegment(i * 250.0, (i + 1) * 250.0, bearing, 0.0, exposure, signals) }

    @Test
    fun etaWithoutWindIsDistanceOverSpeed() {
        val model = EtaModel(flatRoute(90.0))
        val v = model.speedMps(flatRoute(90.0)[0], null)
        val eta = model.predict(progressM = 1000.0, nowMs = 0, forecast = null, form = FormEstimate(1.0, 0.0))
        assertEquals(1500.0 / v, eta.remainingS, 0.01)
        assertEquals(0.0, eta.windCostS, 1e-9)
    }

    @Test
    fun headwindCostsTimeTailwindSavesIt() {
        val route = flatRoute(90.0) // riding east
        val model = EtaModel(route)
        fun windFrom(deg: Double) = WindForecast(listOf(WindSample(0, 8.0, deg, 15.0)))
        val form = FormEstimate(1.0, 0.01)
        val head = model.predict(0.0, 0, windFrom(90.0), form)
        val tail = model.predict(0.0, 0, windFrom(270.0), form)
        assertTrue("head ${head.windCostS}", head.windCostS > 60)
        assertTrue("tail ${tail.windCostS}", tail.windCostS < -30)
        // Sheltered by buildings, the same headwind costs much less.
        val town = EtaModel(flatRoute(90.0, exposure = 0.15)).predict(0.0, 0, windFrom(90.0), form)
        assertTrue(town.windCostS < head.windCostS / 2)
    }

    @Test
    fun trafficLightsAddExpectedWaits() {
        val form = FormEstimate(1.0, 0.0)
        val none = EtaModel(flatRoute(0.0)).predict(0.0, 0, null, form)
        val some = EtaModel(flatRoute(0.0, signals = 1)).predict(0.0, 0, null, form)
        assertEquals(10 * EtaModel.STOP_MEAN_S, some.remainingS - none.remainingS, 1e-6)
        assertTrue(some.sigmaS > none.sigmaS)
    }

    @Test
    fun formLearnsFromSegments() {
        val f = FormEstimator(prior = 1.0)
        repeat(15) { f.observe(1.1) }
        assertEquals(1.1, f.estimate().mean, 0.02)
        // Settles at ~6% uncertainty: single segments are noisy, and form may drift during a ride.
        assertTrue("variance ${f.estimate().variance}", f.estimate().variance < 0.004)
        val before = f.estimate().mean
        assertFalse(f.observe(3.0)) // an absurd segment (e.g. after a GPS jump) is ignored
        assertEquals(before, f.estimate().mean, 1e-12)
    }
}
