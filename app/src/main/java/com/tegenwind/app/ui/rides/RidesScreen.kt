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
    val dao = LocalContext.current.appContainer.db.rides()
    val rides by remember { dao.finishedRides() }.collectAsStateWithLifecycle(emptyList())
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
        items(rides, key = { it.id }) { ride -> RideRow(ride) { openId = ride.id } }
    }
}

@Composable
private fun RideRow(ride: RideEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(formatRideStart(ride.startedAtMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "%.1f km · %s moving".format(ride.distanceM / 1000, formatElapsed(ride.movingMs)) +
                        if (ride.simulated) " · simulated" else "",
                    style = MaterialTheme.typography.bodyMedium,
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

fun avgSpeedText(ride: RideEntity): String =
    if (ride.movingMs > 0) "%.1f km/h".format(ride.distanceM / (ride.movingMs / 1000.0) * 3.6) else "--"
