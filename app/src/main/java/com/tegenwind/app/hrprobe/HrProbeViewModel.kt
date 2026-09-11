package com.tegenwind.app.hrprobe

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tegenwind.app.health.HealthConnectHr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class HcStatus { CHECKING, UNAVAILABLE, NEEDS_UPDATE, NEEDS_PERMISSION, READY }
enum class ProbeMode { IDLE, LIVE, PAST }

/** The most recent heart-rate reading currently stored in Health Connect. */
data class LatestReading(val time: Instant, val bpm: Long, val source: String, val writtenAt: Instant)

data class ProbeUi(
    val status: HcStatus = HcStatus.CHECKING,
    val mode: ProbeMode = ProbeMode.IDLE,
    val startedAt: Instant? = null,
    val lastPollAt: Instant? = null,
    val checks: Int = 0,
    val pastHours: Long? = null,
    val latest: LatestReading? = null,
    val batches: List<HrBatch> = emptyList(), // newest first
    val stats: ProbeStats = ProbeMath.stats(emptyList()),
    val workouts: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Phase 0 spike: measures how late Steel HR readings reach Health Connect.
 * Live mode snapshots what's already stored, then re-reads every 30 s and treats unseen readings as new arrivals.
 * Past mode re-reads a time window afterwards.
 */
class HrProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val hc = HealthConnectHr(app)
    private val _ui = MutableStateFlow(ProbeUi())
    val ui: StateFlow<ProbeUi> = _ui.asStateFlow()
    val permissions: Set<String> get() = hc.permissions

    private var liveJob: Job? = null
    private val seenSamples = HashSet<String>()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val status = when (hc.sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE ->
                    if (runCatching { hc.hasAllPermissions() }.getOrDefault(false)) HcStatus.READY
                    else HcStatus.NEEDS_PERMISSION
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_UPDATE
                else -> HcStatus.UNAVAILABLE
            }
            _ui.update { it.copy(status = status) }
        }
    }

    fun startLive() {
        if (liveJob?.isActive == true) return
        seenSamples.clear()
        val started = Instant.now()
        _ui.update { ProbeUi(status = it.status, mode = ProbeMode.LIVE, startedAt = started) }
        liveJob = viewModelScope.launch {
            // Baseline: everything already stored doesn't count as a new arrival.
            try {
                val existing = hc.heartRateBetween(started.minus(LOOKBACK), started)
                existing.forEach { r -> r.samples.forEach { seenSamples.add(sampleKey(r, it.time)) } }
                _ui.update { it.copy(latest = newestReading(existing), lastPollAt = Instant.now(), checks = 1) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(mode = ProbeMode.IDLE, error = describe(e)) }
                return@launch
            }
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                poll()
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        _ui.update { it.copy(mode = ProbeMode.IDLE) }
    }

    fun pollNow() {
        if (liveJob?.isActive == true) viewModelScope.launch { poll() }
    }

    private suspend fun poll() {
        val now = Instant.now()
        val from = (_ui.value.startedAt ?: now).minus(LOOKBACK)
        try {
            val records = hc.heartRateBetween(from, now)
            // Records grow as the watch syncs; only readings we haven't seen before are new arrivals.
            val newBatches = records.mapNotNull { r ->
                val fresh = r.samples
                    .filter { seenSamples.add(sampleKey(r, it.time)) }
                    .map { HrPoint(it.time, it.beatsPerMinute) }
                if (fresh.isEmpty()) null
                else HrBatch(r.metadata.id, r.metadata.dataOrigin.packageName, r.metadata.lastModifiedTime, fresh)
            }
            val workouts = hc.workoutsBetween(from, now).map(::describeWorkout)

            _ui.update { u ->
                val all = newBatches.sortedByDescending { it.writtenAt } + u.batches
                u.copy(
                    batches = all,
                    stats = ProbeMath.stats(all),
                    workouts = workouts,
                    latest = newestReading(records) ?: u.latest,
                    lastPollAt = Instant.now(),
                    checks = u.checks + 1,
                    error = null,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _ui.update { it.copy(error = describe(e), lastPollAt = Instant.now()) }
        }
    }

    /** Re-reads a past window. Delays use the record's last write time, so updated records may look later than they were. */
    fun analyzePast(hours: Long) {
        liveJob?.cancel()
        liveJob = null
        viewModelScope.launch {
            try {
                val end = Instant.now()
                val start = end.minus(Duration.ofHours(hours))
                val records = hc.heartRateBetween(start, end)
                val batches = records
                    .filter { it.samples.isNotEmpty() }
                    .map { r ->
                        HrBatch(
                            recordId = r.metadata.id,
                            source = r.metadata.dataOrigin.packageName,
                            writtenAt = r.metadata.lastModifiedTime,
                            samples = r.samples.map { HrPoint(it.time, it.beatsPerMinute) },
                        )
                    }
                    .sortedByDescending { it.writtenAt }
                val workouts = hc.workoutsBetween(start, end).map(::describeWorkout)
                _ui.update {
                    ProbeUi(
                        status = it.status,
                        mode = ProbeMode.PAST,
                        pastHours = hours,
                        lastPollAt = Instant.now(),
                        latest = newestReading(records),
                        batches = batches,
                        stats = ProbeMath.stats(batches),
                        workouts = workouts,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = describe(e)) }
            }
        }
    }

    fun shareText(): String {
        val u = _ui.value
        val s = u.stats
        return buildString {
            appendLine("Tegenwind heart-rate delay test")
            appendLine(
                when (u.mode) {
                    ProbeMode.PAST -> "Mode: past ${u.pastHours} h"
                    else -> "Mode: live, started ${ProbeMath.formatClock(u.startedAt)}, ${u.checks} checks"
                }
            )
            u.latest?.let {
                appendLine("Newest reading: ${it.bpm} bpm at ${ProbeMath.formatClock(it.time)}, stored ${ProbeMath.formatClock(it.writtenAt)}, source ${it.source}")
            }
            appendLine("Verdict: ${ProbeMath.verdict(s)}")
            appendLine("Readings: ${s.sampleCount} in ${s.batchCount} batches")
            appendLine("Reading every: ${ProbeMath.formatDuration(s.medianIntervalSec)}")
            appendLine("Delay median: ${ProbeMath.formatDuration(s.delayMedianSec)}, 90%: ${ProbeMath.formatDuration(s.delayP90Sec)}, max: ${ProbeMath.formatDuration(s.delayMaxSec)}")
            appendLine("Sources: ${u.batches.map { it.source }.distinct().joinToString()}")
            u.workouts.forEach { appendLine("Workout: $it") }
            appendLine()
            append(ProbeMath.toCsv(u.batches))
        }
    }

    private fun sampleKey(r: HeartRateRecord, time: Instant) = "${r.metadata.dataOrigin.packageName}@$time"

    private fun newestReading(records: List<HeartRateRecord>): LatestReading? =
        records.flatMap { r ->
            r.samples.map { LatestReading(it.time, it.beatsPerMinute, r.metadata.dataOrigin.packageName, r.metadata.lastModifiedTime) }
        }.maxByOrNull { it.time }

    private fun describeWorkout(s: ExerciseSessionRecord): String {
        val type = if (s.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING) "Cycling" else "Workout type ${s.exerciseType}"
        return "$type ${ProbeMath.formatClock(s.startTime)}–${ProbeMath.formatClock(s.endTime)} · ${ProbeMath.sourceName(s.metadata.dataOrigin.packageName)}"
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException ->
            "Health Connect refused the read. Keep this app open on screen, and check the permission is still allowed."
        else -> "Couldn't read Health Connect: ${e.message ?: e::class.simpleName}"
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
        /** Readings measured up to this long before the test started still count if they arrive during it. */
        val LOOKBACK: Duration = Duration.ofHours(6)
    }
}
