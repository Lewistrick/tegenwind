package com.tegenwind.app.hrprobe

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

data class HrPoint(val time: Instant, val bpm: Long)

/**
 * One write of heart-rate readings into Health Connect.
 * [writtenAt] is when Health Connect stored them, so writtenAt - reading time = delay.
 * Delays can be negative when the watch clock runs ahead or the source timestamps readings at sync time.
 */
data class HrBatch(
    val recordId: String,
    val source: String,
    val writtenAt: Instant,
    val samples: List<HrPoint>,
) {
    val delaysSec: List<Double>
        get() = samples.map { Duration.between(it.time, writtenAt).toMillis() / 1000.0 }
}

data class ProbeStats(
    val sampleCount: Int,
    val batchCount: Int,
    val medianIntervalSec: Double?,
    val delayMedianSec: Double?,
    val delayP90Sec: Double?,
    val delayMaxSec: Double?,
    val lastWrittenAt: Instant?,
)

enum class Verdict { NO_DATA, LIVE_ENOUGH, NEAR_LIVE, TOO_LATE }

object ProbeMath {

    /** Linear-interpolated percentile, p in 0..1. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = p * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
    }

    fun stats(batches: List<HrBatch>): ProbeStats {
        val delays = batches.flatMap { it.delaysSec }
        val times = batches.flatMap { b -> b.samples.map { it.time } }.distinct().sorted()
        val intervals = times.zipWithNext { a, b -> Duration.between(a, b).toMillis() / 1000.0 }
        return ProbeStats(
            sampleCount = delays.size,
            batchCount = batches.size,
            medianIntervalSec = percentile(intervals, 0.5),
            delayMedianSec = percentile(delays, 0.5),
            delayP90Sec = percentile(delays, 0.9),
            delayMaxSec = delays.maxOrNull(),
            lastWrittenAt = batches.maxOfOrNull { it.writtenAt },
        )
    }

    /** Thresholds match the plan: under 2 min feels live, under 10 min is usable near-live. */
    fun verdict(stats: ProbeStats): Verdict {
        val median = stats.delayMedianSec ?: return Verdict.NO_DATA
        return when {
            median <= 120 -> Verdict.LIVE_ENOUGH
            median <= 600 -> Verdict.NEAR_LIVE
            else -> Verdict.TOO_LATE
        }
    }

    fun toCsv(batches: List<HrBatch>): String = buildString {
        appendLine("record_id,source,sample_time,bpm,written_at,delay_s")
        batches.sortedBy { it.writtenAt }.forEach { b ->
            b.samples.zip(b.delaysSec).forEach { (s, delay) ->
                appendLine("${b.recordId},${b.source},${s.time},${s.bpm},${b.writtenAt},${"%.0f".format(Locale.ROOT, delay)}")
            }
        }
    }

    /** 75 -> "1:15", 3725 -> "1:02:05", -12 -> "−0:12" */
    fun formatDuration(seconds: Double?): String {
        if (seconds == null) return "–"
        val sign = if (seconds < 0) "−" else ""
        val s = kotlin.math.abs(seconds.toLong())
        return sign + if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun formatClock(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(clock) ?: "–"

    fun sourceName(packageName: String): String = when (packageName) {
        "com.withings.wiscale2" -> "Withings"
        "com.sec.android.app.shealth" -> "Samsung Health"
        "com.google.android.apps.fitness" -> "Google Fit"
        else -> packageName
    }
}
