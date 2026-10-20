package com.tegenwind.app.ui.rides

import com.tegenwind.app.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTextTest {
    @Test
    fun showsPedalPowerToTheWholeWatt() {
        assertEquals("≈124 W", powerText(124.4))
        assertEquals("≈127 W", powerText(126.5))
        assertNull(powerText(null)) // ride too short to judge, or a bike that rides itself
    }

    @Test
    fun usualIsTheMedianOfYourOwnRides() {
        val rides = listOf(110.0, 130.0, 118.0).map { RideEntity(startedAtMs = 0, formFactor = 0.9, bikeName = "Gazelle", pedalPowerW = it) }
        assertEquals(118.0, usualWatts(rides)!!, 0.0)
        assertEquals("usually ≈118 W", usualPowerText(usualWatts(rides)))
        // Before the first real ride there is nothing to compare with, and no model rider stands in.
        assertNull(usualWatts(emptyList()))
        assertNull(usualPowerText(null))
    }

    @Test
    fun aRideKeepsItsEffortAndNothingIsMadeUp() {
        assertEquals(200.0, rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.88, bikeName = "Racer", pedalPowerW = 200.0))!!, 0.0)
        // No watts kept (too short, a bike that rides itself, no passes): none are guessed from form.
        assertNull(rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.95, bikeName = "Scooter")))
        assertNull(rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.88)))
    }
}
