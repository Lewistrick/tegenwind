package com.tegenwind.app.ui.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTextTest {
    @Test
    fun showsFormAsRoundedPedalPower() {
        // 0.88 of the model rider's 25 km/h, on the Gazelle with bags: about 140 W.
        assertEquals("≈140 W", powerText(0.88))
        assertNull(powerText(null)) // ride too short to judge
    }
}
