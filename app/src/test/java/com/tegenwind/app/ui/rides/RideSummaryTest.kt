package com.tegenwind.app.ui.rides

import com.tegenwind.app.data.SegmentTraversalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideSummaryTest {
    @Test
    fun placesCountTheFasterRidesAndShareTies() {
        val all = listOf(1_700_000L, 1_650_000L, 1_800_000L, 1_650_000L)
        assertEquals(Place(1, 4), placeAmong(1_650_000L, all))
        assertEquals(Place(3, 4), placeAmong(1_700_000L, all))
        assertEquals(Place(4, 4), placeAmong(1_800_000L, all))
        assertNull(placeAmong(1_000L, all))
    }

    @Test
    fun placesAreSaidPlainly() {
        assertEquals("fastest yet", placeText(Place(1, 42)))
        assertEquals("slowest yet", placeText(Place(42, 42)))
        assertEquals("3rd fastest of 42", placeText(Place(3, 42)))
        assertEquals("first ride on this route", placeText(Place(1, 1)))
        assertEquals("11th", ordinal(11))
        assertEquals("22nd", ordinal(22))
        assertEquals("101st", ordinal(101))
    }

    @Test
    fun aheadIsPositive() {
        assertEquals(35.0, aheadS(mineMs = 1_000_000, otherMs = 1_035_000), 0.0)
        assertEquals(-20.0, aheadS(mineMs = 1_020_000, otherMs = 1_000_000), 0.0)
    }

    private fun pass(rideId: Long, seg: Int, seconds: Long) =
        SegmentTraversalEntity(rideId, 1, seg, 0, seconds * 1000, seconds * 1000, null, seconds * 1000)

    @Test
    fun aRecordNeedsEnoughEarlierPassesAndTheQuickestTime() {
        val earlier = (1L..3L).flatMap { r -> listOf(pass(r, 0, 60), pass(r, 1, 50), pass(r, 2, 40)) } + pass(4, 3, 30)
        val mine = listOf(pass(9, 0, 55), pass(9, 1, 50), pass(9, 2, 41), pass(9, 3, 10))
        // Segment 0: quicker than all three. 1: only as quick. 2: slower. 3: too few earlier passes.
        assertEquals(listOf(0), recordSegments(mine, earlier))
    }

    @Test
    fun tenEqualBinsFromFastestToSlowestWithTheSlowestInTheLast() {
        // 20:00 to 30:00: one minute per bin.
        val all = listOf(20, 21, 21, 24, 25, 29, 30).map { it * 60_000L }
        val bins = timeBins(all, mine = 24 * 60_000L)!!
        assertEquals(listOf(1, 2, 0, 0, 1, 1, 0, 0, 0, 2), bins.counts.toList())
        assertEquals(4, bins.mine)
        assertEquals(60_000.0, bins.widthMs, 0.0)
        assertEquals(24 * 60_000L + 30_000L, bins.middleMs(4))
    }

    @Test
    fun onlyOneTimeHasNoBins() {
        assertNull(timeBins(listOf(1_000L, 1_000L), mine = 1_000L))
        assertNull(timeBins(emptyList(), mine = 1_000L))
    }
}
