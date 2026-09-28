package com.tegenwind.app.health

import com.tegenwind.app.data.HrSampleEntity
import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.ride.Sample
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** How long heart-rate readings stay on the phone. Health Connect keeps its own copy. */
const val HR_KEEP_DAYS = 14L

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Keeps the heart rate of recent rides on the phone, as material for estimating heart rate during a
 * ride. Only the last [HR_KEEP_DAYS] days: anything older is deleted whenever the app starts.
 */
class HeartRateStore(private val hc: HealthConnectHr, private val dao: RideDao) {

    private fun cutoff(nowMs: Long) = nowMs - HR_KEEP_DAYS * DAY_MS

    /** Deletes readings older than [HR_KEEP_DAYS] days. */
    suspend fun forgetOld(nowMs: Long = System.currentTimeMillis()) = dao.deleteHrBefore(cutoff(nowMs))

    /** Keeps [samples] read for [ride], unless the ride is simulated or already too old to keep. */
    suspend fun keep(ride: RideEntity, samples: List<Sample>, nowMs: Long = System.currentTimeMillis()) {
        if (ride.simulated || samples.isEmpty()) return
        val keep = samples.filter { it.timeMs >= cutoff(nowMs) }
        if (keep.isNotEmpty()) dao.insertHr(keep.map { HrSampleEntity(ride.id, it.timeMs, it.value) })
    }

    /**
     * Reads Health Connect once for recent rides that have no readings kept yet. Health Connect only
     * answers while the app is in front, so this runs when it comes to the front, and quietly does
     * nothing without permission.
     */
    suspend fun catchUp(nowMs: Long = System.currentTimeMillis()) {
        forgetOld(nowMs)
        try {
            if (!hc.hasAllPermissions()) return
            for (ride in dao.recentRidesWithoutHr(cutoff(nowMs))) {
                val end = ride.endedAtMs ?: continue
                keep(ride, hc.heartRateSamples(Instant.ofEpochMilli(ride.startedAtMs), Instant.ofEpochMilli(end)), nowMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Health Connect missing, busy or refusing: try again next time.
            android.util.Log.w("Tegenwind", "Couldn't read heart rate for recent rides", e)
        }
    }
}
