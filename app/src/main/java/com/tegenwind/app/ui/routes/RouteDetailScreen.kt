package com.tegenwind.app.ui.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.EnrichState
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.ui.theme.Danger
import com.tegenwind.app.ui.theme.GoodColor
import com.tegenwind.app.ui.theme.SpeedColor
import kotlinx.coroutines.launch

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
fun compass(bearingDeg: Double): String = COMPASS[(((bearingDeg + 22.5) % 360) / 45).toInt()]

@Composable
fun RouteDetailScreen(routeId: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val container = LocalContext.current.appContainer
    val dao = container.db.routes()
    val repo = container.routes
    val scope = rememberCoroutineScope()
    val route by remember(routeId) { dao.routeFlow(routeId) }.collectAsStateWithLifecycle(null)
    val segments by remember(routeId) { dao.segmentsFlow(routeId) }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Routes") }
        val r = route ?: return@Column
        Text(r.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "%.1f km · %d segments of ~%d m".format(r.lengthM / 1000, segments.size, if (segments.isEmpty()) 0 else (r.lengthM / segments.size).toInt()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnrichStatus(r)

        if (r.enrichState == EnrichState.DONE && segments.isNotEmpty()) {
            val lights = segments.sumOf { it.signals ?: 0 }
            val openKm = segments.filter { (it.exposure ?: 0.0) >= 0.7 }.sumOf { it.endM - it.startM } / 1000
            val steepest = segments.mapNotNull { it.gradePct }.maxByOrNull { kotlin.math.abs(it) } ?: 0.0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Traffic lights", "$lights", Modifier.weight(1f))
                Stat("Open to wind", "%.1f km".format(openKm), Modifier.weight(1f))
                Stat("Steepest", "%+.1f%%".format(steepest), Modifier.weight(1f))
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Wind exposure along the route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tall bars: open fields. Short bars: between buildings. Red ticks: traffic lights.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExposureStrip(segments, r.lengthM, Modifier.fillMaxWidth().height(90.dp).padding(top = 8.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { onOpen(repo.createReverse(routeId)) } }) { Text("Add reverse route") }
            if (r.enrichState == EnrichState.FAILED || r.enrichState == EnrichState.DONE) {
                OutlinedButton(onClick = { repo.enrich(routeId) }) { Text("Look up again") }
            }
        }

        if (segments.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    SegmentHeader()
                    segments.forEach { s ->
                        HorizontalDivider()
                        SegmentRow(s)
                    }
                }
            }
        }

        TextButton(onClick = { confirmDelete = true }) { Text("Delete route", color = MaterialTheme.colorScheme.error) }
        Text(DATA_ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this route?") },
            text = { Text("Your rides on it stay, but they're no longer linked to a route.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.delete(routeId)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExposureStrip(segments: List<RouteSegmentEntity>, lengthM: Double, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val gap = 1.dp.toPx()
        segments.forEach { s ->
            val x0 = (s.startM / lengthM * size.width).toFloat()
            val w = ((s.endM - s.startM) / lengthM * size.width).toFloat() - gap
            val e = (s.exposure ?: 0.0).toFloat()
            drawRect(track, Offset(x0, 0f), Size(w, size.height))
            drawRect(SpeedColor.copy(alpha = 0.35f + 0.65f * e), Offset(x0, size.height * (1 - e)), Size(w, size.height * e))
            repeat(s.signals ?: 0) { k ->
                val x = x0 + w - 2.dp.toPx() - k * 4.dp.toPx()
                drawRect(Danger, Offset(x, 0f), Size(2.dp.toPx(), 10.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SegmentHeader() {
    Row(Modifier.padding(vertical = 6.dp)) {
        listOf("km" to 70, "dir" to 44, "grade" to 64, "open" to 60, "lights" to 50).forEach { (t, w) ->
            Text(t, Modifier.width(w.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SegmentRow(s: RouteSegmentEntity) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%.2f".format(s.startM / 1000), Modifier.width(70.dp), style = style)
        Text(compass(s.bearingDeg), Modifier.width(44.dp), style = style)
        Text(s.gradePct?.let { "%+.1f%%".format(it) } ?: "–", Modifier.width(64.dp), style = style)
        Text(s.exposure?.let { "%d%%".format((it * 100).toInt()) } ?: "–", Modifier.width(60.dp), style = style,
            color = if ((s.exposure ?: 0.0) >= 0.7) GoodColor else MaterialTheme.colorScheme.onSurface)
        Text(s.signals?.takeIf { it > 0 }?.toString() ?: "", Modifier.width(50.dp), style = style, color = Danger)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
    }
}
