package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichRetryTest {

    @Test
    fun waitsLongerAfterEachRejectedRequest() {
        assertEquals(20_000L, RouteEnricher.retryDelayMs(1))
        assertEquals(40_000L, RouteEnricher.retryDelayMs(2))
        assertEquals(80_000L, RouteEnricher.retryDelayMs(3))
        // Every attempt is bounded, so a whole lookup gives up within minutes rather than hours.
        val total = (1 until RouteEnricher.MAX_ATTEMPTS).sumOf { RouteEnricher.retryDelayMs(it) }
        assertTrue("waits $total ms in total", total <= 5 * 60_000L)
    }

    @Test
    fun offersARestartOnceALookupLooksStuck() {
        val startedAt = 1_000_000L
        assertFalse(EnrichProgress.looksStuck(startedAt, startedAt + 20_000))
        assertTrue(EnrichProgress.looksStuck(startedAt, startedAt + EnrichProgress.SLOW_AFTER_MS))
        // No start time: left behind by an older version or by the app being killed mid-lookup.
        assertTrue(EnrichProgress.looksStuck(null, startedAt))
    }
}
