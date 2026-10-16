package com.tegenwind.app.ui.ride

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tegenwind.app.R
import com.tegenwind.app.media.HoldRepeat
import com.tegenwind.app.media.MediaControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music on the handlebar: previous, play/pause, next, volume down, volume up, for whichever app is
 * playing. Big equal buttons with icons only, a tick under the finger for each press, and the volume
 * buttons repeat while held.
 */
@Composable
fun MediaCard(controls: MediaControls, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MediaButton(R.drawable.ic_media_previous, "Previous track", Modifier.weight(1f)) { controls.previous() }
            MediaButton(R.drawable.ic_media_play_pause, "Play or pause", Modifier.weight(1f)) { controls.playPause() }
            MediaButton(R.drawable.ic_media_next, "Next track", Modifier.weight(1f)) { controls.next() }
            MediaButton(R.drawable.ic_volume_down, "Volume down", Modifier.weight(1f), repeat = true) { controls.volumeDown() }
            MediaButton(R.drawable.ic_volume_up, "Volume up", Modifier.weight(1f), repeat = true) { controls.volumeUp() }
        }
    }
}

/** One 56 dp button. With [repeat], holding it keeps acting: after [HoldRepeat.FIRST_MS], every [HoldRepeat.EVERY_MS]. */
@Composable
private fun MediaButton(
    @DrawableRes icon: Int,
    description: String,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    action: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val act by rememberUpdatedState(action)
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(repeat) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        act()
                        val held = if (!repeat) null else scope.launch {
                            delay(HoldRepeat.FIRST_MS)
                            while (true) {
                                act()
                                delay(HoldRepeat.EVERY_MS)
                            }
                        }
                        tryAwaitRelease()
                        held?.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}
