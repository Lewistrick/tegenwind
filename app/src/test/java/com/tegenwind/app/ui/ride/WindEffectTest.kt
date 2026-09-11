package com.tegenwind.app.ui.ride

import org.junit.Assert.assertEquals
import org.junit.Test

class WindEffectTest {
    @Test
    fun saysCostsOrGainsWithoutSigns() {
        assertEquals("wind costs 1:40", windEffect(100.0))
        assertEquals("wind gains 0:55", windEffect(-55.0))
        assertEquals("wind barely matters", windEffect(-9.0))
    }
}
