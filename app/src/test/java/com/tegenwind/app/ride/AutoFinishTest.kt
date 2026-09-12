package com.tegenwind.app.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFinishTest {

    @Test
    fun countsAsArrivedNearTheEndOnly() {
        assertTrue(AutoFinish.arrived(12.0))
        assertTrue(AutoFinish.arrived(0.0))
        assertFalse(AutoFinish.arrived(80.0))
    }

    @Test
    fun countsDownThenStops() {
        val arrivedAt = 1_000_000L
        assertEquals(15, AutoFinish.secondsLeft(arrivedAt, arrivedAt))
        assertEquals(5, AutoFinish.secondsLeft(arrivedAt, arrivedAt + 10_000))
        assertEquals(1, AutoFinish.secondsLeft(arrivedAt, arrivedAt + 14_500))
        assertEquals(0, AutoFinish.secondsLeft(arrivedAt, arrivedAt + 15_000))
        assertFalse(AutoFinish.dueToStop(arrivedAt, arrivedAt + 14_999))
        assertTrue(AutoFinish.dueToStop(arrivedAt, arrivedAt + 15_000))
    }
}
