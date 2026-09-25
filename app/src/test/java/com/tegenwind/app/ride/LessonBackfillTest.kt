package com.tegenwind.app.ride

import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.data.SegmentTraversalEntity
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.eta.SegmentLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LessonBackfillTest {

    private fun pass(rideId: Long, movingMs: Long) = SegmentTraversalEntity(
        rideId = rideId, routeId = 1, segIdx = 0, enterMs = rideId * 1000, exitMs = rideId * 1000 + movingMs,
        movingMs = movingMs, headwindMps = null, predictedMovingMs = 50_000,
    )

    // Five rides; learning was switched on from the third, so only the last three taught.
    private val passes = listOf(pass(1, 70_000), pass(2, 65_000), pass(3, 60_000), pass(4, 58_000), pass(5, 62_000))
    private val rides = (1L..5L).associateWith { PassRide(simulated = false, formFactor = 0.9) }

    private fun segmentAfter(taught: List<SegmentTraversalEntity>): RouteSegmentEntity {
        var c = SegmentCorrection()
        taught.forEach { c = SegmentLearner.update(c, it.movingMs, it.predictedMovingMs) }
        return RouteSegmentEntity(1, 0, 0.0, 250.0, 90.0, learnedLogMean = c.logMean, learnedLogVar = c.logVar, learnedPasses = c.passes)
    }

    @Test
    fun onlyThePassesThatTaughtGetANudge() {
        val out = backfillLessons(listOf(segmentAfter(passes.drop(2))), passes, rides)
        assertNull(out[0].learnedShiftMs)
        assertNull(out[1].learnedShiftMs)
        out.drop(2).forEach { assertNotNull(it.learnedShiftMs) }
        // Before learning, the model expected plain physics at that day's form.
        assertEquals(50_000 / 0.9, out[0].expectedMovingMs!!.toDouble(), 1.0)
        // The first pass that taught started from scratch too; the next one from what it taught.
        assertEquals(out[0].expectedMovingMs, out[2].expectedMovingMs)
        val firstNudge = out[2].learnedShiftMs!!
        assertEquals(out[2].expectedMovingMs!! + firstNudge / 0.9, out[3].expectedMovingMs!!.toDouble(), 2.0)
    }

    @Test
    fun aReplayThatDoesNotMatchLeavesTheNudgesOut() {
        val stored = segmentAfter(passes.drop(2)).let { it.copy(learnedLogMean = it.learnedLogMean + 0.01) }
        val out = backfillLessons(listOf(stored), passes, rides)
        out.forEach {
            assertNotNull(it.expectedMovingMs)
            assertNull(it.learnedShiftMs)
        }
    }

    @Test
    fun simulatedAndTooShortPassesTeachNothing() {
        val short = pass(6, 2_000)
        val sim = pass(7, 60_000)
        val all = passes + short + sim
        val withSim = rides + (6L to PassRide(false, 0.9)) + (7L to PassRide(true, null))
        val out = backfillLessons(listOf(segmentAfter(passes.drop(2))), all, withSim)
        assertNull(out[5].learnedShiftMs)
        assertNull(out[6].learnedShiftMs)
        assertNotNull(out[4].learnedShiftMs)
    }
}
