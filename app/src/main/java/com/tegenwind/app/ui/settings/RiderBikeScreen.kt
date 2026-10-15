package com.tegenwind.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.Bike
import com.tegenwind.app.data.Gears
import com.tegenwind.app.data.Motor
import com.tegenwind.app.data.Position
import com.tegenwind.app.data.RiderSetup
import com.tegenwind.app.data.Tyres
import com.tegenwind.app.ui.Action
import com.tegenwind.app.ui.ActionCard
import com.tegenwind.app.ui.Picker
import com.tegenwind.app.ui.rememberConfirmTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * You and your bikes: what the arrival time and your watts are worked out with. Every change is
 * kept at once and counts from the next ride; rides already made keep what they were ridden with.
 */
@Composable
fun RiderBikeScreen(onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val store = container.riderSetup
    val setup by store.setup.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Ride") }
        Text("Rider and bike", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "What your arrival time and watts are worked out with. Changes count from your next ride.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RiderCard(setup) { change -> store.update(change) }

        setup.bikes.forEach { bike ->
            BikeCard(bike) { changed -> store.update { s -> s.copy(bikes = s.bikes.map { if (it.id == bike.id) changed else it }) } }
            BikeActions(
                bike = bike,
                active = bike.id == setup.activeBikeId,
                canDelete = setup.bikes.size > 1,
                onRide = { store.update { it.copy(activeBikeId = bike.id) } },
                onDelete = { store.update { s -> s.copy(bikes = s.bikes.filter { it.id != bike.id }) } },
            )
        }

        ActionCard(
            listOf(
                Action("Add bike", "Another bike you ride, e.g. an e-bike. Pick it on the Ride screen before you set off.") {
                    store.update { s ->
                        val id = (s.bikes.maxOfOrNull { it.id } ?: 0) + 1
                        s.copy(bikes = s.bikes + Bike(id = id, name = "Bike $id", weightKg = 18.0))
                    }
                },
            )
        )
    }
}

/** Your weight and what you carry; your weight can come from Health Connect. */
@Composable
private fun RiderCard(setup: RiderSetup, onChange: ((RiderSetup) -> RiderSetup) -> Unit) {
    val container = LocalContext.current.appContainer
    val health = container.healthConnect
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // Bumped to show a weight read from Health Connect in the field, replacing what was typed.
    var readCount by remember { mutableStateOf(0) }

    fun readWeight() {
        scope.launch {
            message = try {
                val kg = health.latestWeightKg()
                if (kg == null) "No weight in Health Connect from the last year."
                else {
                    onChange { it.copy(riderKg = kg) }
                    readCount++
                    "Taken from Health Connect: %.1f kg.".format(kg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't read Health Connect: ${e.message ?: e::class.simpleName}"
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(health.weightPermissions)) readWeight() else message = "No access to your weight in Health Connect."
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("You", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            NumberField("Your weight", setup.riderKg, "kg", key = "rider-$readCount") { kg -> onChange { it.copy(riderKg = kg) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    if (health.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
                        message = "Health Connect isn't available on this phone."
                    } else scope.launch {
                        if (health.hasWeightPermission()) readWeight() else launcher.launch(health.weightPermissions)
                    }
                }) { Text("From Health Connect") }
            }
            message?.let { Explain(it) }
            NumberField("Luggage", setup.luggageKg, "kg") { kg -> onChange { it.copy(luggageKg = kg) } }
            Explain("What you usually carry: bags, a laptop, shopping.")
            Explain("Riding ${setup.activeBike.name}: %.1f kg in all.".format(setup.params().massKg))
        }
    }
}

/** One bike: what it weighs, how you sit on it, its tyres, gears and motor. */
@Composable
private fun BikeCard(bike: Bike, onChange: (Bike) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var name by remember(bike.id) { mutableStateOf(bike.name) }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (it.isNotBlank()) onChange(bike.copy(name = it))
                },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("Bike weight", bike.weightKg, "kg", key = "bike-${bike.id}") { onChange(bike.copy(weightKg = it)) }
            Explain("With what stays on it, such as locks.")

            Choice("Riding position", bike.position, Position.entries.map { it to it.label }, bike.position.explain) {
                onChange(bike.copy(position = it))
            }
            Choice("Tyres", bike.tyres, Tyres.entries.map { it to it.label }, bike.tyres.explain) {
                onChange(bike.copy(tyres = it))
            }
            Choice("Gears", bike.gears, Gears.entries.map { it to it.label }, bike.gears.explain) {
                onChange(bike.copy(gears = it))
            }
            Choice("Motor", bike.motor, Motor.entries.map { it to it.label }, bike.motor.explain) {
                onChange(bike.copy(motor = it, motorW = if (it == Motor.FULL && bike.motor != Motor.FULL) 500.0 else bike.motorW))
            }
            if (bike.motor != Motor.NONE) {
                NumberField("Motor power", bike.motorW, "W", key = "motor-${bike.id}-${bike.motor}") { onChange(bike.copy(motorW = it)) }
                Explain("What the motor adds. A normal e-bike's is 250 W by law; a gentle support mode gives less.")
                NumberField("Stops helping at", bike.cutoffKmh, "km/h", key = "cutoff-${bike.id}") { onChange(bike.copy(cutoffKmh = it)) }
                Explain("25 km/h for a normal e-bike, 45 km/h for a speed pedelec.")
            }
        }
    }
}

/** Riding a bike next, and deleting it: deleting takes two taps. */
@Composable
private fun BikeActions(bike: Bike, active: Boolean, canDelete: Boolean, onRide: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by rememberConfirmTap()
    val actions = buildList {
        if (!active) add(Action("Ride this bike", "Your next ride is on ${bike.name}.") { onRide() })
        if (canDelete) add(
            Action(
                if (confirmDelete) "Sure? Tap again" else "Delete bike",
                "Removes ${bike.name}. Rides made on it keep what they were ridden with.",
                danger = true,
                armed = confirmDelete,
            ) {
                if (confirmDelete) {
                    confirmDelete = false
                    onDelete()
                } else confirmDelete = true
            }
        )
    }
    if (active) Explain("Your next ride is on ${bike.name}.")
    if (actions.isNotEmpty()) ActionCard(actions)
}

/** A choice from a short list, with what the current choice means under it. */
@Composable
private fun <T> Choice(label: String, selected: T, options: List<Pair<T, String>>, explain: String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Picker(selected = selected, options = options, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
        Explain(explain)
    }
}

/**
 * A number with its unit. What's typed stays as typed while you type; every value that reads as a
 * number is kept at once. [key] starts the field afresh, e.g. after a weight came from Health Connect.
 */
@Composable
private fun NumberField(label: String, value: Double, unit: String, key: String = label, onValue: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Explain(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "84", "6.5": no trailing ".0". */
private fun formatNumber(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)
