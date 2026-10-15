package com.tegenwind.app.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/** What the music buttons on the ride screen can do. */
interface MediaControls {
    fun playPause()
    fun next()
    fun previous()
    fun volumeUp()
    fun volumeDown()
}

/**
 * Controls whichever app is playing, the way a Bluetooth headset does: the buttons are sent as media
 * keys, which Android hands to the app that holds the media session right now, and the volume is the
 * phone's own. Nothing here needs a permission. It can't see what is playing, so there is no track,
 * and play and pause are one button.
 */
class AudioMediaControls(context: Context) : MediaControls {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun playPause() = key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    override fun next() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    override fun previous() = key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    override fun volumeUp() = volume(AudioManager.ADJUST_RAISE)
    override fun volumeDown() = volume(AudioManager.ADJUST_LOWER)

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    // One step of the phone's own, with its volume bar showing the new level.
    private fun volume(direction: Int) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
}

/** How a held volume button repeats: after [FIRST_MS] it steps every [EVERY_MS], so no ten taps with gloves on. */
object HoldRepeat {
    const val FIRST_MS = 400L
    const val EVERY_MS = 150L
}
