package com.tegenwind.app.ui.rides

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.ride.formatElapsed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault())
fun formatRideStart(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFormat)

@Composable
fun RidesScreen() {
    val db = LocalContext.current.appContainer.db
    val rides by remember { db.rides().finishedRides() }.collectAsStateWithLifecycle(emptyList())
    val routes by remember { db.routes().routes() }.collectAsStateWithLifecycle(emptyList())
    val routeNames = remember(routes) { routes.associate { it.id to it.name } }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }

    val id = openId
    if (id != null) {
        BackHandler { openId = null }
        RideDetailScreen(rideId = id, onBack = { openId = null })
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Rides", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        if (rides.isEmpty()) {
            item {
                Text(
                    "Your rides appear here after you stop them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rides, key = { it.id }) { ride ->
            RideRow(ride, routeNames[ride.routeId]) { openId = ride.id }
        }
    }
}

@Composable
private fun RideRow(ride: RideEntity, routeName: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(routeName ?: "Free ride", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    formatRideStart(ride.startedAtMs) + if (ride.simulated) " · simulated" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "%.1f km · %s moving".format(ride.distanceM / 1000, formatElapsed(ride.movingMs)) +
                        (formPercent(ride.formFactor)?.let { " · form $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(4.dp))
            Text(
                avgSpeedText(ride),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/**
 * Speed compared with the physics model at the end of that ride: "+3%" means 3% faster than predicted.
 * Null for rides too short to judge (fewer than three complete route segments).
 */
fun formPercent(formFactor: Double?): String? =
    formFactor?.let { "%+d%%".format(kotlin.math.round((it - 1) * 100).toInt()) }

fun avgSpeedText(ride: RideEntity): String =
    if (ride.movingMs > 0) "%.1f km/h".format(ride.distanceM / (ride.movingMs / 1000.0) * 3.6) else "--"
