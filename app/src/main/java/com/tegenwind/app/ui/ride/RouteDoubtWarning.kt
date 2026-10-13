package com.tegenwind.app.ui.ride

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tegenwind.app.appContainer
import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.RouteDoubt
import com.tegenwind.app.routes.RouteFit
import com.tegenwind.app.routes.habitByRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A line under the route picker when the chosen route doesn't fit where you are or what you usually
 * ride at this hour, with the route that does fit one tap away. Nothing when it fits, or when
 * there's nothing to judge by.
 */
@Composable
fun RouteDoubtWarning(selectedRouteId: Long, routes: List<RouteEntity>, locationOk: Boolean, onSelectRoute: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    // Looked at again whenever the screen comes back: you may have moved since.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val doubt by produceState<RouteDoubt?>(null, selectedRouteId, routes, locationOk, resumed) {
        val starts = container.db.routes().routeStarts().associate { it.routeId to GeoPoint(it.lat, it.lon) }
        while (true) {
            val here = if (locationOk) whereAmI(context) else null
            val habit = habitByRoute(container.db.rides().recentRouteStarts(), System.currentTimeMillis())
            value = RouteFit.doubt(selectedRouteId, starts, here, habit)
            delay(60_000)
        }
    }
    val d = doubt ?: return
    val name = routes.firstOrNull { it.id == selectedRouteId }?.name ?: return
    val far = d.farFromStartM?.let { if (it >= 950) "%.1f km".format(it / 1000) else "${(it / 50).toInt() * 50} m" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                far != null && d.unusualHour -> "You're $far from where $name starts, and you don't usually ride it at this hour."
                far != null -> "You're $far from where $name starts."
                else -> "You don't usually ride $name at this hour."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        d.better?.let { id ->
            routes.firstOrNull { it.id == id }?.let { r -> TextButton(onClick = { onSelectRoute(id) }) { Text("Switch to ${r.name}") } }
        }
    }
}

/** Roughly where the phone is now, or null when that can't be told in a few seconds. Stays on the phone. */
@SuppressLint("MissingPermission")
internal suspend fun whereAmI(context: Context): GeoPoint? = suspendCancellableCoroutine { cont ->
    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        .setMaxUpdateAgeMillis(120_000)
        .setDurationMillis(10_000)
        .build()
    try {
        LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, null)
            .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l?.let { GeoPoint(it.latitude, it.longitude) }) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    } catch (e: SecurityException) {
        if (cont.isActive) cont.resume(null)
    }
}
