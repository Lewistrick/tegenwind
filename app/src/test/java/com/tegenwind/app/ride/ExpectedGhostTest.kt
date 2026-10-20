package com.tegenwind.app.ride

import com.tegenwind.app.eta.EtaModel
import com.tegenwind.app.eta.EtaSegment
import com.tegenwind.app.eta.Physics
import com.tegenwind.app.eta.RiderParams
import com.tegenwind.app.weather.RouteWeather
import com.tegenwind.app.weather.WindForecast
import com.tegenwind.app.weather.WindSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedGhostTest {
    private val params = RiderParams()

    /** Ten 250 m segments heading north, with a traffic light in each. */
    private val model = EtaModel(
        (0 until 10).map { i -> EtaSegment(i * 250.0, (i + 1) * 250.0, 0.0, 0.0, 1.0, signals = 1) },
        params,
    )

    @Test
    fun itRidesStraightThroughTheLights() {
        val ghost = expectedGhost(model, null, null, 1.0, 0)
        val calm = params.modelSpeedMps()
        // 2.5 km at the model's speed: not a second more for the ten lights.
        assertEquals(2500.0 / calm, ghost.totalS, 1.0)
        assertEquals(1250.0, ghost.alongAt(1250.0 / calm), 15.0)
    }

    @Test
    fun aHeadwindHoldsItBackAndGoodFormSpeedsItUp() {
        val headwind = RouteWeather.everywhere(WindForecast(listOf(WindSample(0, 8.0, 0.0, 15.0))))
        val still = expectedGhost(model, null, null, 1.0, 0).totalS
        assertTrue(expectedGhost(model, null, headwind, 1.0, 0).totalS > still * 1.1)
        assertTrue(expectedGhost(model, null, null, 1.1, 0).totalS < still)
    }

    @Test
    fun theGhostReachesEachSpotAtTheTimeItSays() {
        val ghost = expectedGhost(model, null, null, 1.0, 0)
        val at = ghost.elapsedAt(1000.0)!!
        assertEquals(1000.0, ghost.alongAt(at), 1.0)
        assertNull(ghost.elapsedAt(2600.0))
    }

    @Test
    fun effortCarriesOverAsForm() {
        assertEquals(0.9, params.formAtPowerW(params.powerAtFormW(0.9)!!)!!, 1e-3)
        assertEquals(1.0, params.formAtPowerW(RiderParams.MODEL_RIDER_W)!!, 1e-3)
        assertNull(params.copy(motorFull = true).formAtPowerW(100.0))
        assertTrue(Physics.speedMps(100.0, 0.0, 0.0, Physics.airDensity(null), params) > 0)
    }
}
