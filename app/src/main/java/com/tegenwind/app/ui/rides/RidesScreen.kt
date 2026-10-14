package com.tegenwind.app.ui.rides

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.tegenwind.app.eta.pacePercent
import com.tegenwind.app.eta.usualFormBefore
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
    val forms = remember(rides) { formsByStart(rides) }
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
            RideRow(ride, routeNames[ride.routeId], ridePace(ride, forms)) { openId = ride.id }
        }
    }
}

@Composable
private fun RideRow(ride: RideEntity, routeName: String?, pace: String?, onClick: () -> Unit) {
    // Simulated rides stay in the list but step back, so the real ones stand out.
    val ink = if (ride.simulated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(routeName ?: "Free ride", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink)
                Text(avgSpeedText(ride), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = ink)
            }
            Text(
                formatRideStart(ride.startedAtMs) + if (ride.simulated) " · simulated" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            // Each item keeps its words together ("pace 97%"), so a line can only break between items.
            Text(
                listOfNotNull(
                    "%.1f km".format(ride.distanceM / 1000),
                    "${formatElapsed(ride.movingMs)} moving",
                    pace?.let { "pace $it" },
                ).joinToString(" · ") { it.replace(' ', ' ') },
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = muted,
            )
        }
    }
}

/** Each real ride's form by its start time: what every ride's pace is measured against. */
fun formsByStart(rides: List<RideEntity>): List<Pair<Long, Double>> =
    rides.filter { !it.simulated && it.formFactor != null }.map { it.startedAtMs to it.formFactor!! }

/**
 * A ride's pace, "103%": its form as a share of your usual form going into it, the median of the ten
 * real rides before it. Null for a ride too short to judge, or one with no rides before it to compare.
 */
fun ridePace(ride: RideEntity, forms: List<Pair<Long, Double>>): String? =
    pacePercent(ride.formFactor, usualFormBefore(forms, ride.startedAtMs))

fun avgSpeedText(ride: RideEntity): String =
    if (ride.movingMs > 0) "%.1f km/h".format(ride.distanceM / (ride.movingMs / 1000.0) * 3.6) else "--"
