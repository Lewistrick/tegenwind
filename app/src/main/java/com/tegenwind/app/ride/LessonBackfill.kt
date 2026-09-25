package com.tegenwind.app.ride

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RouteDao
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.eta.SegmentLearner
import kotlin.math.abs
import kotlin.math.roundToLong

/** What the backfill needs to know about the ride a pass belongs to. */
data class PassRide(val simulated: Boolean, val formFactor: Double?)

/**
 * Passes recorded before rides kept track of what they taught get it filled in afterwards, by
 * replaying history exactly as it happened. Nothing records when learning was switched on, and
 * nothing needs to: a segment that learned from N passes learned from its last N real ones that
 * were long enough to count, so replaying those from scratch lands on what it knows today.
 *
 * Every pass gets [SegmentTraversalEntity.expectedMovingMs]; those that taught also get
 * [SegmentTraversalEntity.learnedShiftMs]. A segment whose replay doesn't land exactly on its stored
 * correction gets no shifts at all rather than a guess. The stored corrections are never touched.
 *
 * [passes] are one route's, in the order they were ridden.
 */
fun backfillLessons(
    segments: List<RouteSegmentEntity>,
    passes: List<SegmentTraversalEntity>,
    rides: Map<Long, PassRide>,
): List<SegmentTraversalEntity> {
    val bySegIdx = segments.associateBy { it.idx }
    val out = passes.associateBy { it.rideId to it.segIdx }.toMutableMap()

    for ((segIdx, segPasses) in passes.groupBy { it.segIdx }) {
        val seg = bySegIdx[segIdx]
        val taught = segPasses.filter { p ->
            rides[p.rideId]?.simulated == false && p.movingMs >= SegmentLearner.MIN_MS && p.predictedMovingMs > 0
        }.takeLast(seg?.learnedPasses ?: 0)

        // Replay what the segment learned, remembering where it stood before each pass.
        var c = SegmentCorrection()
        val before = HashMap<Long, SegmentCorrection>()
        val after = HashMap<Long, SegmentCorrection>()
        for (p in taught) {
            before[p.rideId] = c
            c = SegmentLearner.update(c, p.movingMs, p.predictedMovingMs)
            after[p.rideId] = c
        }
        val trusted = seg != null && c.passes == seg.learnedPasses && abs(c.logMean - seg.learnedLogMean) < 1e-9

        // Passes before the first one that taught saw the untouched prior; later ones that didn't
        // count (too short, simulated) saw whatever the segment knew by then.
        var known = SegmentCorrection()
        for (p in segPasses) {
            val was = before[p.rideId] ?: known
            val form = rides[p.rideId]?.formFactor ?: 1.0
            val now = after[p.rideId]
            out[p.rideId to p.segIdx] = p.copy(
                expectedMovingMs = (p.predictedMovingMs * was.timeFactor / form).roundToLong(),
                learnedShiftMs = if (trusted && now != null) (p.predictedMovingMs * (now.timeFactor - was.timeFactor)).roundToLong() else null,
            )
            if (now != null) known = now
        }
    }
    return passes.map { out.getValue(it.rideId to it.segIdx) }
}

/** Fills in every route that still has passes from before rides recorded what they taught. */
suspend fun backfillLessons(rideDao: RideDao, routeDao: RouteDao) {
    for (routeId in rideDao.routesWithUnannotatedPasses()) {
        val rides = rideDao.ridesOnRoute(routeId).associate { it.id to PassRide(it.simulated, it.formFactor) }
        val passes = rideDao.traversalsForRoute(routeId)
        val annotated = backfillLessons(routeDao.segments(routeId), passes, rides)
        // Only the passes that lacked it: whatever a ride recorded itself stays as it was.
        rideDao.updateTraversals(annotated.filterIndexed { i, _ -> passes[i].expectedMovingMs == null })
    }
}
