package com.tegenwind.app.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SetOffTest {
    /** About 1 m of latitude. */
    private val m = 1.0 / 111_195

    private fun fix(s: Int, northM: Double, accuracyM: Double = 5.0, speedMps: Double = 0.0) =
        Fix(s * 1000L, 52.0 + northM * m, 5.0, speedMps, accuracyM)

    @Test
    fun standingWithWanderingPositionsAndNoisySpeedIsNotLeaving() {
        val setOff = SetOff()
        // Ten minutes at the door: positions a few metres apart, GPS speed up to 5 km/h.
        for (s in 0 until 600) {
            assertNull(setOff.add(fix(s, northM = (s % 7) - 3.0, speedMps = (s % 5) * 0.35)))
        }
    }

    @Test
    fun oneBlurryPositionFarAwayIsNotLeaving() {
        val setOff = SetOff()
        assertNull(setOff.add(fix(0, 0.0)))
        // 40 m off, but only good to 60 m: not used at all.
        assertNull(setOff.add(fix(1, 40.0, accuracyM = 60.0)))
        // 22 m off, good to 15 m: within what the two positions can be off together.
        assertNull(setOff.add(fix(2, 22.0, accuracyM = 15.0)))
        assertNull(setOff.add(fix(3, 1.0)))
    }

    @Test
    fun leavingStartsTheRideAtTheLastMomentStandingWithEveryPositionSince() {
        val setOff = SetOff()
        for (s in 0..30) assertNull(setOff.add(fix(s, 0.0)))
        // Riding off at 5 m/s from second 30.
        var ridden: List<Fix>? = null
        var s = 31
        while (ridden == null) {
            ridden = setOff.add(fix(s, (s - 30) * 5.0, speedMps = 5.0))
            s++
        }
        assertNotNull(ridden)
        // Left: further than 5 + 5 m accuracy + 15 m = 25 m, at 30 m. Begun: the last position within
        // the two accuracies (10 m), so a second or two late rather than early.
        assertEquals(30.0, (ridden.last().lat - 52.0) / m, 0.01)
        assertEquals(32_000L, ridden.first().timeMs)
        assertEquals(5, ridden.size)
    }

    @Test
    fun standingStillForMinutesThenLeavingDoesNotCountTheWait() {
        val setOff = SetOff()
        // Three minutes at the door, positions wandering up to 8 m with 5 m accuracy.
        for (s in 0 until 180) assertNull(setOff.add(fix(s, if (s % 3 == 0) 8.0 else -2.0)))
        var ridden: List<Fix>? = null
        var s = 180
        while (ridden == null) {
            ridden = setOff.add(fix(s, (s - 179) * 5.0, speedMps = 5.0))
            s++
        }
        assertEquals(true, ridden.first().timeMs >= 179_000L)
    }

    @Test
    fun aSharperPositionBecomesWhereYouStand() {
        val setOff = SetOff()
        // A blurry first position 12 m off where you really are.
        assertNull(setOff.add(fix(0, 12.0, accuracyM = 15.0)))
        assertNull(setOff.add(fix(1, 1.0, accuracyM = 3.0)))
        // 18 m from the real spot is still home (leaving is 3 + 3 + 15 = 21 m from the sharp position);
        // against the blurry first one it would have been 30 m away.
        assertNull(setOff.add(fix(2, -18.0, accuracyM = 3.0)))
    }
}
