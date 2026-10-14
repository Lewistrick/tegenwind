package com.tegenwind.app.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceTest {

    @Test
    fun yourUsualSpeedIsAHundredPercent() {
        assertEquals("100%", pacePercent(0.88, 0.88))
        // Twice as slow as usual: the ride takes twice as long.
        assertEquals("50%", pacePercent(0.44, 0.88))
        assertEquals("200%", pacePercent(1.76, 0.88))
        assertEquals("103%", pacePercent(0.9064, 0.88))
        assertNull(pacePercent(null, 0.88)) // ride too short to judge
        assertNull(pacePercent(0.9, null)) // no earlier rides to compare with
    }

    @Test
    fun yourUsualIsTheMedianOfTheTenRidesBefore() {
        val day = 86_400_000L
        // Eleven rides, one a day, forms 0.80, 0.81, ... 0.90.
        val forms = (0..10).map { it * day to 0.80 + it * 0.01 }
        assertNull(usualFormBefore(forms, 0L)) // the first ride has nothing before it
        assertEquals(0.80, usualFormBefore(forms, day)!!, 1e-12)
        // Before the eleventh: the ten before it, 0.80–0.89, whose median (upper middle) is 0.85.
        assertEquals(0.85, usualFormBefore(forms, 10 * day)!!, 1e-12)
        // After all eleven, the oldest drops out: 0.81–0.90 gives 0.86.
        assertEquals(0.86, usualFormBefore(forms, 11 * day)!!, 1e-12)
        // The same rule as the starting form of a new ride.
        assertEquals(priorForm(forms.takeLast(10).map { it.second }.reversed()), usualFormBefore(forms, 11 * day)!!, 1e-12)
    }

    @Test
    fun movingAllFormsTogetherLeavesPaceAlone() {
        val day = 86_400_000L
        val forms = listOf(0L to 0.84, day to 0.90, 2 * day to 0.86, 3 * day to 0.95)
        val scaled = forms.map { (t, f) -> t to f * 0.9 }
        assertEquals(
            pacePercent(0.95, usualFormBefore(forms, 3 * day)),
            pacePercent(0.95 * 0.9, usualFormBefore(scaled, 3 * day)),
        )
    }
}
