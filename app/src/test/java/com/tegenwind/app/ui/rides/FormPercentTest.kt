package com.tegenwind.app.ui.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormPercentTest {
    @Test
    fun showsFormAsSignedPercentage() {
        assertEquals("+3%", formPercent(1.032))
        assertEquals("-4%", formPercent(0.96))
        assertEquals("+0%", formPercent(1.0))
        assertNull(formPercent(null)) // ride too short to judge
    }
}
