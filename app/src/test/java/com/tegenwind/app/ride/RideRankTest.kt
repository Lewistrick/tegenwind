package com.tegenwind.app.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideRankTest {

    /** Twenty rides on a route: 30:00, 30:10, … 33:10. */
    private val twenty = (0 until 20).map { 1_800_000L + it * 10_000L }

    @Test
    fun theFastestAndSlowestAreTheTopAndBottomOneInTwenty() {
        assertEquals(RideRank.Top(5), RideRank.of(twenty.first(), twenty))
        assertEquals(RideRank.Bottom(5), RideRank.of(twenty.last(), twenty))
    }

    @Test
    fun eachRideGetsWhicheverEndItIsNearer() {
        assertEquals(RideRank.Top(15), RideRank.of(twenty[2], twenty))
        assertEquals(RideRank.Top(45), RideRank.of(twenty[8], twenty))
        assertEquals(RideRank.Bottom(45), RideRank.of(twenty[11], twenty))
        // Tenth and eleventh fastest of twenty: half the rides either way.
        assertEquals(RideRank.Middle, RideRank.of(twenty[9], twenty))
        assertEquals(RideRank.Middle, RideRank.of(twenty[10], twenty))
    }

    @Test
    fun aTieCountsInTheRidesFavour() {
        val tied = listOf(10L, 10L, 20L, 30L, 40L)
        assertEquals(RideRank.Top(20), RideRank.of(10L, tied))
    }

    @Test
    fun tooFewRidesSayNothing() {
        assertNull(RideRank.of(10L, listOf(10L, 20L, 30L, 40L)))
    }
}
