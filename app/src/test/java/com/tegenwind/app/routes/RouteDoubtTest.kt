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

    @Test
    fun theRouteThatStartsHereIsOffered() {
        assertEquals(1L, RouteFit.best(starts, home, emptyMap()))
        assertEquals(2L, RouteFit.best(starts, work, mapOf(1L to 9)))
        // Nowhere near any route.
        assertNull(RouteFit.best(starts, GeoPoint(52.2, 4.9), mapOf(1L to 9)))
    }

    @Test
    fun ofTwoRoutesFromHereTheHabitAtThisHourWins() {
        val both = mapOf(1L to home, 3L to home, 2L to work)
        assertEquals(3L, RouteFit.best(both, home, mapOf(3L to 4, 1L to 1)))
        // Nothing to tell them apart: leave the choice alone.
        assertNull(RouteFit.best(both, home, emptyMap()))
    }

    @Test
    fun withoutAPositionOnlyAClearHabitIsOffered() {
        assertEquals(2L, RouteFit.best(starts, null, mapOf(2L to 5, 1L to 1)))
        assertNull(RouteFit.best(starts, null, mapOf(2L to 1)))
    }
}
