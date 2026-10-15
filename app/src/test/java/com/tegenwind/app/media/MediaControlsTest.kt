package com.tegenwind.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlsTest {

    /** Records what was asked, in order. */
    private class Fake : MediaControls {
        val calls = ArrayList<String>()
        override fun playPause() { calls += "playPause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun volumeUp() { calls += "volumeUp" }
        override fun volumeDown() { calls += "volumeDown" }
    }

    @Test
    fun eachButtonAsksForItsOwnActionOnce() {
        val fake = Fake()
        fake.previous(); fake.playPause(); fake.next(); fake.volumeDown(); fake.volumeUp()
        assertEquals(listOf("previous", "playPause", "next", "volumeDown", "volumeUp"), fake.calls)
    }

    @Test
    fun aHeldVolumeButtonStepsAboutSevenTimesASecondAfterAShortPause() {
        assertTrue(HoldRepeat.FIRST_MS in 300..600)
        // From the first step to the second: the pause, then the repeat rate.
        assertTrue(1000 / HoldRepeat.EVERY_MS in 5..10)
        assertTrue(HoldRepeat.FIRST_MS > HoldRepeat.EVERY_MS)
    }
}
