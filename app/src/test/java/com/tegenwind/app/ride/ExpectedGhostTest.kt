package com.tegenwind.app.ride

import com.tegenwind.app.eta.EtaSegment
import com.tegenwind.app.eta.Physics
import com.tegenwind.app.eta.RiddenStretch
import com.tegenwind.app.eta.RiderParams
import com.tegenwind.app.eta.SteadyPower
import com.tegenwind.app.weather.RouteWeather
import com.tegenwind.app.weather.WindForecast
import com.tegenwind.app.weather.WindSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedGhostTest {
    private val params = RiderParams()
    private val rho = Physics.airDensity(null)

    /** Ten 250 m segments heading north, with a traffic light in each. */
    private val segments = (0 until 10).map { i -> EtaSegment(i * 250.0, (i + 1) * 250.0, 0.0, 0.0, 1.0, signals = 1) }

    private fun wind(fromDeg: Double) = RouteWeather.everywhere(WindForecast(listOf(WindSample(0, 6.0, fromDeg, 15.0))))

    @Test
    fun itHoldsYourEffortAndRidesStraightThroughTheLights() {
        val ghost = expectedGhost(segments, params, null, null, 140.0, 0)
        val speed = Physics.speedMps(140.0, 0.0, 0.0, rho, params)
        // 2.5 km at what 140 W carries you: not a second more for the ten lights.
        assertEquals(2500.0 / speed, ghost.totalS, 1.0)
        assertEquals(1250.0, ghost.alongAt(1250.0 / speed), 15.0)
    }

    @Test
    fun aHeadwindHoldsItBackAndATailwindSpeedsItUp() {
        val calm = expectedGhost(segments, params, null, null, 140.0, 0).totalS
        // Heading north: wind from the north is a headwind, from the south a tailwind.
        assertTrue(expectedGhost(segments, params, null, wind(0.0), 140.0, 0).totalS > calm * 1.15)
        assertTrue(expectedGhost(segments, params, null, wind(180.0), 140.0, 0).totalS < calm * 0.9)
    }

    @Test
    fun moreEffortIsFaster() {
        assertTrue(expectedGhost(segments, params, null, null, 160.0, 0).totalS < expectedGhost(segments, params, null, null, 140.0, 0).totalS)
    }

    @Test
    fun theGhostReachesEachSpotAtTheTimeItSays() {
        val ghost = expectedGhost(segments, params, null, null, 140.0, 0)
        val at = ghost.elapsedAt(1000.0)!!
        assertEquals(1000.0, ghost.alongAt(at), 1.0)
        assertNull(ghost.elapsedAt(2600.0))
    }

    @Test
    fun steadyPowerIsTheEffortWindAndSlopeTakenOut() {
        // Ride 2 km at what 150 W carries you into a 3 m/s headwind: the effort is 150 W...
        val v = Physics.speedMps(150.0, 0.0, 3.0, rho, params)
        val windy = listOf(RiddenStretch(2000.0, 0.0, 3.0, 2000.0 / v))
        assertEquals(150.0, SteadyPower.watts(windy, params)!!, 0.5)
        // ...and the same time spent in calm air would have taken far less.
        val calm = listOf(RiddenStretch(2000.0, 0.0, 0.0, 2000.0 / v))
        assertTrue(SteadyPower.watts(calm, params)!! < 110.0)
        // The expected ride at that effort in that wind takes just as long.
        val ghost = expectedGhost(segments.take(8), params, null, wind(0.0), 150.0, 0)
        val head = Physics.headwindMps(6.0, 0.0, 0.0, 1.0)
        assertEquals(2000.0 / Physics.speedMps(150.0, 0.0, head, rho, params), ghost.totalS, 1.0)
    }

    @Test
    fun tooLittleRiddenOrABikeThatRidesItselfSaysNothing() {
        assertNull(SteadyPower.watts(listOf(RiddenStretch(500.0, 0.0, 0.0, 100.0)), params))
        assertNull(SteadyPower.watts(listOf(RiddenStretch(2000.0, 0.0, 0.0, 400.0)), params.copy(motorFull = true)))
    }
}
