package com.tegenwind.app.ui.routes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.tegenwind.app.data.RideEntity
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.EnrichState
import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.ride.formatElapsed
import com.tegenwind.app.routes.EnrichProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val DATA_ATTRIBUTION =
    "Map data © OpenStreetMap contributors (ODbL). Elevation: Copernicus DEM via Open-Meteo (CC BY 4.0)."

@Composable
fun RoutesScreen() {
    val context = LocalContext.current
    val repo = context.appContainer.routes
    val routes by remember { repo.routes() }.collectAsStateWithLifecycle(emptyList())
    val rides by remember { context.appContainer.db.rides().finishedRides() }.collectAsStateWithLifecycle(emptyList())
    val ridesByRoute = remember(rides) { rides.filter { !it.simulated }.groupBy { it.routeId } }
    val scope = rememberCoroutineScope()

    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var importName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var editingRouteId by remember { mutableStateOf<Long?>(null) }
    var editingRouteName by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            importName = displayName(context, uri)?.substringBeforeLast('.') ?: "Route"
        }
    }

    val id = openId
    if (id != null) {
        BackHandler { openId = null }
        RouteDetailScreen(routeId = id, onBack = { openId = null }, onOpen = { openId = it })
        return
    }

    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Routes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // Rarely needed, so it doesn't shout: outlined, not filled.
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import GPX") }
            }
        }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
        if (routes.isEmpty()) {
            item {
                Text(
                    "Import a GPX file of your commute, for example exported from Komoot or Strava. " +
                        "Tegenwind then looks up elevation, buildings and traffic lights along it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(routes, key = { it.id }) { r ->
            RouteRow(r, ridesByRoute[r.id].orEmpty(), onClick = { openId = r.id }, onRename = { editingRouteId = r.id; editingRouteName = r.name })
        }
        item {
            Text(DATA_ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val uri = pendingUri
    if (uri != null) {
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("Import route") },
            text = {
                OutlinedTextField(value = importName, onValueChange = { importName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = importName.isNotBlank(),
                    onClick = {
                        pendingUri = null
                        scope.launch {
                            message = try {
                                val stream = context.contentResolver.openInputStream(uri) ?: error("Can't open the file")
                                openId = repo.importGpx(importName, stream)
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Couldn't import this file: ${e.message ?: e::class.simpleName}"
                            }
                        }
                    },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null }) { Text("Cancel") } },
        )
    }

    val idToEdit = editingRouteId
    if (idToEdit != null) {
        AlertDialog(
            onDismissRequest = { editingRouteId = null },
            title = { Text("Rename route") },
            text = {
                OutlinedTextField(value = editingRouteName, onValueChange = { editingRouteName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = editingRouteName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            routes.find { it.id == idToEdit }?.let { route ->
                                repo.updateRouteName(route.copy(name = editingRouteName))
                                editingRouteId = null
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingRouteId = null }) { Text("Cancel") } },
        )
    }
}

/** One route: how long, how often ridden and how long it usually takes, and where it's open to the wind. */
@Composable
private fun RouteRow(route: RouteEntity, rides: List<RideEntity>, onClick: () -> Unit, onRename: () -> Unit) {
    val dao = LocalContext.current.appContainer.db.routes()
    val segments by remember(route.id) { dao.segmentsFlow(route.id) }.collectAsStateWithLifecycle(emptyList())
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(route.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRename) { Text("Rename") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    routeSummary(route, rides),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segments.any { it.exposure != null }) {
                    ExposureStrip(segments, route.lengthM, Modifier.fillMaxWidth().height(20.dp), axis = false)
                }
                EnrichStatus(route)
            }
        }
    }
}

/** "13.6 km · 6 rides · usually 37:43": the typical time is the median, start to finish. */
private fun routeSummary(route: RouteEntity, rides: List<RideEntity>): String {
    val durations = rides.map { (it.endedAtMs ?: it.startedAtMs) - it.startedAtMs }.sorted()
    val parts = listOfNotNull(
        "%.1f km".format(route.lengthM / 1000),
        if (rides.isEmpty()) "not ridden yet" else "${rides.size} ride${if (rides.size == 1) "" else "s"}",
        durations.takeIf { it.isNotEmpty() }?.let { "usually ${formatElapsed(it[it.size / 2])}" },
    )
    return parts.joinToString(" · ") { it.replace(' ', '\u00A0') }
}

@Composable
fun EnrichStatus(route: RouteEntity) {
    val repo = LocalContext.current.appContainer.routes
    when (route.enrichState) {
        EnrichState.PENDING, EnrichState.RUNNING -> {
            var now by remember(route.id) { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(route.id) {
                while (true) {
                    delay(1_000)
                    now = System.currentTimeMillis()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "  Looking up elevation, buildings and traffic lights…" +
                            (route.enrichStartedAtMs?.let { " ${formatElapsed(now - it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (EnrichProgress.looksStuck(route.enrichStartedAtMs, now)) {
                    Text(
                        "Taking longer than usual. The free map servers are busy, or the lookup stopped " +
                            "when Android closed the app. A route this size normally takes a few seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { repo.enrich(route.id) }) { Text("Start the lookup again") }
                }
            }
        }
        EnrichState.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Couldn't look up map data: ${route.enrichError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { repo.enrich(route.id) }) { Text("Try again") }
        }
        else -> Unit
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
