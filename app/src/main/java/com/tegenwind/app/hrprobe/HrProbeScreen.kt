package com.tegenwind.app.hrprobe

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun HrProbeScreen(vm: HrProbeViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refreshStatus() }

    // Permissions can change in system settings while we're away.
    LifecycleResumeEffect(Unit) {
        vm.refreshStatus()
        onPauseOrDispose { }
    }

    // Keep the screen on during a live test: Health Connect only allows reads while the app is visible.
    val view = LocalView.current
    DisposableEffect(ui.mode) {
        view.keepScreenOn = ui.mode == ProbeMode.LIVE
        onDispose { view.keepScreenOn = false }
    }

    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(1_000)
        }
    }

    Scaffold { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 16.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Heart-rate delay test", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Measures how late your watch's heart rate reaches Health Connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                when (ui.status) {
                    HcStatus.CHECKING -> Text("Checking Health Connect…")
                    HcStatus.UNAVAILABLE -> Notice(
                        "Health Connect isn't available on this phone.",
                        "Open settings",
                    ) { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    HcStatus.NEEDS_UPDATE -> Notice(
                        "Health Connect needs an update before it can be used.",
                        "Update",
                    ) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                        )
                    }
                    HcStatus.NEEDS_PERMISSION -> Notice(
                        "Allow Tegenwind to read heart rate and workouts from Health Connect.",
                        "Allow access",
                    ) { permissionLauncher.launch(vm.permissions) }
                    HcStatus.READY -> Controls(
                        ui = ui,
                        onStart = vm::startLive,
                        onStop = vm::stopLive,
                        onPoll = vm::pollNow,
                        onPast = vm::analyzePast,
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Tegenwind heart-rate delay test")
                                putExtra(Intent.EXTRA_TEXT, vm.shareText())
                            }
                            context.startActivity(Intent.createChooser(send, "Share results"))
                        },
                    )
                }
            }

            ui.error?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(msg, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            if (ui.status == HcStatus.READY && ui.mode != ProbeMode.IDLE) {
                item { LatestCard(ui, now) }
            }

            if (ui.status == HcStatus.READY && ui.mode != ProbeMode.IDLE || ui.batches.isNotEmpty()) {
                item { StatsCard(ui, now) }
            }

            if (ui.workouts.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Workouts seen", fontWeight = FontWeight.SemiBold)
                            ui.workouts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            if (ui.batches.isNotEmpty()) {
                item { Text("Batches, newest first", style = MaterialTheme.typography.titleMedium) }
                items(ui.batches, key = { it.recordId + it.writtenAt + it.samples.first().time }) { BatchRow(it) }
            }

            item { HowTo() }
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text)
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Controls(
    ui: ProbeUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPoll: () -> Unit,
    onPast: (Long) -> Unit,
    onShare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.mode == ProbeMode.LIVE) {
                Button(onClick = onStop) { Text("Stop test") }
                OutlinedButton(onClick = onPoll) { Text("Check now") }
            } else {
                Button(onClick = onStart) { Text("Start live test") }
            }
            if (ui.batches.isNotEmpty()) OutlinedButton(onClick = onShare) { Text("Share results") }
        }
        Text("Or check a past ride:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3L, 8L, 24L).forEach { h ->
                FilterChip(
                    selected = ui.mode == ProbeMode.PAST && ui.pastHours == h,
                    onClick = { onPast(h) },
                    label = { Text("Last $h h") },
                )
            }
        }
    }
}

/** What Health Connect holds right now, independent of what counts as "new" for the test. */
@Composable
private fun LatestCard(ui: ProbeUi, now: Instant) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Newest in Health Connect", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val l = ui.latest
            if (l == null) {
                Text("No heart rate in the last 6 hours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                val age = Duration.between(l.time, now).seconds.toDouble()
                Text(
                    "${l.bpm} bpm, measured ${ProbeMath.formatClock(l.time)} (${ProbeMath.formatDuration(age)} ago)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val lag = Duration.between(l.time, l.writtenAt).seconds.toDouble()
                val lagText = if (lag < 0) "${ProbeMath.formatDuration(-lag)} before its own timestamp"
                else "${ProbeMath.formatDuration(lag)} after measuring"
                Text(
                    "Stored ${ProbeMath.formatClock(l.writtenAt)}, $lagText · ${ProbeMath.sourceName(l.source)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (ui.mode == ProbeMode.LIVE) {
                Text(
                    "Checked ${ui.checks}× · last ${ProbeMath.formatClock(ui.lastPollAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(ui: ProbeUi, now: Instant) {
    val s = ui.stats
    val verdict = ProbeMath.verdict(s)
    val (headline, color) = when (verdict) {
        Verdict.NO_DATA -> (if (ui.mode == ProbeMode.LIVE) "Waiting for the first sync…" else "No heart rate found") to MaterialTheme.colorScheme.onSurfaceVariant
        Verdict.LIVE_ENOUGH -> "Close to live" to Color(0xFF2E9E62)
        Verdict.NEAR_LIVE -> "Near-live, as planned" to Color(0xFFC98A00)
        Verdict.TOO_LATE -> "Too late for live, consider a strap" to MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
            Text(
                when (ui.mode) {
                    ProbeMode.LIVE -> "Live since ${ProbeMath.formatClock(ui.startedAt)} · checking every 30 s · last check ${ProbeMath.formatClock(ui.lastPollAt)}"
                    ProbeMode.PAST -> "Past ${ui.pastHours} h · delays use each record's last update, so they can look longer than they were"
                    ProbeMode.IDLE -> "Stopped"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                Metric("Typical delay", ProbeMath.formatDuration(s.delayMedianSec), Modifier.weight(1f))
                Metric("9 in 10 within", ProbeMath.formatDuration(s.delayP90Sec), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("Longest delay", ProbeMath.formatDuration(s.delayMaxSec), Modifier.weight(1f))
                Metric("A reading every", ProbeMath.formatDuration(s.medianIntervalSec), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("Readings", "${s.sampleCount} in ${s.batchCount} batches", Modifier.weight(1f))
                val since = s.lastWrittenAt?.let { Duration.between(it, now).seconds.toDouble() }
                Metric("New data", since?.let { "${ProbeMath.formatDuration(it)} ago" } ?: "–", Modifier.weight(1f))
            }
            val early = ui.batches.sumOf { b -> b.delaysSec.count { it < 0 } }
            if (early > 0) {
                Text(
                    "$early readings were stored before their own timestamp. Either the watch clock runs ahead, " +
                        "or Withings stamps readings with the sync time, so the delays above may look shorter than they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            s.medianIntervalSec?.takeIf { it > 120 }?.let {
                Text(
                    "Readings are ${ProbeMath.formatDuration(it)} apart. Is a workout running on the watch? Without one it only measures every few minutes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BatchRow(b: HrBatch) {
    val delays = b.delaysSec
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Arrived ${ProbeMath.formatClock(b.writtenAt)} · ${ProbeMath.sourceName(b.source)}",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${b.samples.size} readings, ${ProbeMath.formatClock(b.samples.minOf { it.time })}–${ProbeMath.formatClock(b.samples.maxOf { it.time })}, " +
                    "${b.samples.minOf { it.bpm }}–${b.samples.maxOf { it.bpm }} bpm",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Delay ${ProbeMath.formatDuration(delays.minOrNull())} to ${ProbeMath.formatDuration(delays.maxOrNull())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HowTo() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How to run the test", fontWeight = FontWeight.SemiBold)
            listOf(
                "In the Withings app, turn on Health Connect and allow it to share heart rate.",
                "Allow access here when asked.",
                "Before you ride, tap Start live test and leave this app open on your mount. The screen stays on.",
                "Start a cycling workout on the watch.",
                "After the ride, tap Stop test, then Share results.",
                "Forgot to start it? Use \"Check a past ride\" afterwards.",
            ).forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
