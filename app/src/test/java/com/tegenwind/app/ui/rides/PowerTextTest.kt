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
    fun formIsSaidAsAPercentageOfTheModelRider() {
        assertEquals("form +3%", formText(1.03))
        assertEquals("form −8%", formText(0.92))
        assertEquals("form 0%", formText(1.001))
    }

    @Test
    fun aRideKeepsTheWattsOfTheSetupItWasRiddenWith() {
        // From before bikes could be set: worked out on the first setup, the Gazelle.
        assertEquals(124.0, rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.88))!!, 3.0)
        // Since then: what was kept with the ride, whatever the settings say now.
        assertEquals(200.0, rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.88, bikeName = "Racer", pedalPowerW = 200.0))!!, 0.0)
        // A bike that rides itself kept no watts, and none are made up.
        assertNull(rideWatts(RideEntity(startedAtMs = 0, formFactor = 0.95, bikeName = "Scooter")))
    }
}
