package com.tegenwind.app.ui.rides

import com.tegenwind.app.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RidePaceTest {

    private fun ride(day: Int, form: Double?, simulated: Boolean = false) =
        RideEntity(id = day.toLong(), startedAtMs = day * 86_400_000L, endedAtMs = day * 86_400_000L + 1, formFactor = form, simulated = simulated)

    @Test
    fun eachRideIsComparedWithTheRealRidesBeforeIt() {
        val rides = listOf(ride(0, 0.88), ride(1, 0.88), ride(2, 2.0, simulated = true), ride(3, 0.968), ride(4, null))
        val forms = formsByStart(rides)
        assertNull(ridePace(rides[0], forms)) // nothing before it
        assertEquals("100%", ridePace(rides[1], forms))
        // The simulated ride doesn't count towards your usual.
        assertEquals("110%", ridePace(rides[3], forms))
        assertNull(ridePace(rides[4], forms)) // too short to judge
    }
}
