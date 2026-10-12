package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDoubtTest {
    private val home = GeoPoint(52.0375, 4.6411)
    private val work = GeoPoint(52.0800, 4.4600)
    private val starts = mapOf(1L to home, 2L to work) // 1 = woon-werk, 2 = werk-woon

    @Test
    fun theUsualRouteFromItsStartRaisesNoDoubt() {
        assertNull(RouteFit.doubt(1, starts, home, mapOf(1L to 6)))
        // Nothing known about the hour or the place: nothing to say.
        assertNull(RouteFit.doubt(1, starts, null, emptyMap()))
    }

    @Test
    fun farFromTheStartPointsAtTheRouteThatStartsHere() {
        val d = RouteFit.doubt(1, starts, work, mapOf(1L to 6))!!
        assertTrue(d.farFromStartM!! > 10_000)
        assertEquals(2L, d.better)
    }

    @Test
    fun anHourYouRideAnotherRouteIsADoubtEvenWithoutAPosition() {
        val d = RouteFit.doubt(1, starts, null, mapOf(2L to 5))!!
        assertNull(d.farFromStartM)
        assertTrue(d.unusualHour)
        assertEquals(2L, d.better)
    }

    @Test
    fun anOddHourAtTheRightStartSuggestsNothingElse() {
        // At home in the evening: you usually ride werk-woon now, but that doesn't start here.
        val d = RouteFit.doubt(1, starts, home, mapOf(2L to 5))!!
        assertTrue(d.unusualHour)
        assertNull(d.farFromStartM)
        assertNull(d.better)
    }

    @Test
    fun aRouteIsNotDoubtedForItsHourWithoutAClearHabitElsewhere() {
        assertNull(RouteFit.doubt(1, starts, home, emptyMap()))
        assertNull(RouteFit.doubt(1, starts, home, mapOf(2L to 1)))
    }
}
