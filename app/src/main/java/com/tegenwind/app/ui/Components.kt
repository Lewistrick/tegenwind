package com.tegenwind.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The app's shared building blocks, so every screen draws a number, a chart, an action or a picker
 * the same way. Buttons follow one scheme:
 * - filled amber: the one main thing to do on a screen (Start ride)
 * - an action table ([ActionCard]): everything you can do with a ride or route, each explained
 * - outlined: small in-place actions (Refresh, a picker)
 * - text: navigation and optional extras ("‹ Rides", "Try a simulated ride", "Rename")
 */

/** A small card with a label over a value: the tiles for numbers. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A titled card around a chart, with an optional line under the title saying what it shows. */
@Composable
fun ChartCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

/** One row of an [ActionCard]. A [danger] action is red, and filled red while [armed]. */
class Action(
    val label: String,
    val description: String,
    val danger: Boolean = false,
    val armed: Boolean = false,
    val onClick: () -> Unit,
)

/** Everything you can do with something: equally sized buttons on the left, what they do on the right. */
@Composable
fun ActionCard(actions: List<Action>, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            actions.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider()
                ActionRow(a)
            }
        }
    }
}

@Composable
private fun ActionRow(a: Action) {
    val error = MaterialTheme.colorScheme.error
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val size = Modifier.width(128.dp).height(52.dp)
        val padding = PaddingValues(horizontal = 8.dp)
        val text: @Composable () -> Unit = {
            Text(a.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
        if (a.armed) {
            Button(
                onClick = a.onClick,
                modifier = size,
                contentPadding = padding,
                colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
            ) { text() }
        } else {
            OutlinedButton(
                onClick = a.onClick,
                modifier = size,
                contentPadding = padding,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (a.danger) error else MaterialTheme.colorScheme.primary,
                ),
                border = BorderStroke(1.dp, if (a.danger) error else MaterialTheme.colorScheme.outline),
            ) { text() }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            a.description,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * For "tap again to confirm": true after the first tap, and back to false by itself unless the
 * second tap follows within three seconds.
 */
@Composable
fun rememberConfirmTap(): MutableState<Boolean> {
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(3_000)
            armed.value = false
        }
    }
    return armed
}

/**
 * A drop-down to choose one of [options], shown as a button with the current choice and a chevron.
 * The list opens as wide as the button, a shade lighter than the cards behind it, with a tick at
 * the current choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedButton(
            // Only ever opens: the anchor already toggles, and toggling twice would close it again.
            onClick = { open = true },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Chevron(open)
        }
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                    trailingIcon = if (value == selected) {
                        { Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    } else null,
                )
            }
        }
    }
}

/** A small "v", pointing up while the list is open. */
@Composable
private fun Chevron(up: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val (a, b) = if (up) h * 0.65f to h * 0.35f else h * 0.35f to h * 0.65f
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(w * 0.15f, a), Offset(w * 0.5f, b), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, b), Offset(w * 0.85f, a), stroke, StrokeCap.Round)
    }
}
