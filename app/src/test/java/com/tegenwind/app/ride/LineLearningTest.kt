package com.tegenwind.app.ride

import com.tegenwind.app.data.RouteProfileEntity
import com.tegenwind.app.data.RouteSegmentEntity
import com.tegenwind.app.eta.SegmentCorrection
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.SegmentSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineLearningTest {

    private val line = Polyline(listOf(GeoPoint(51.5, 4.2), GeoPoint(51.5, 4.2 + 1200.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(51.5))))))

    private fun stored(idx: Int, startM: Double, endM: Double, logMean: Double = 0.1, passes: Int = 4) = RouteSegmentEntity(
        routeId = 7, idx = idx, startM = startM, endM = endM, bearingDeg = 90.0,
        gradePct = 1.5, buildings = 12, exposure = 0.6, signals = 1,
        learnedLogMean = logMean, learnedLogVar = 0.01, learnedPasses = passes,
    )

    @Test
    fun aSegmentThatMovesKeepsWhatItLearnedAndItsTime() {
        val was = stored(1, 250.0, 500.0)
        val now = rebuildSegments(7, listOf(SegmentSpan(0.0, 240.0, 1)), listOf(was), line).single()
        assertEquals(0, now.idx)
        assertEquals(240.0, now.endM, 1e-9)
        // Physics time goes with the length; the correction makes up for it, so the expected time stays.
        assertEquals(
            250.0 * SegmentCorrection(was.learnedLogMean).timeFactor,
            240.0 * SegmentCorrection(now.learnedLogMean).timeFactor,
            1e-6,
        )
        assertEquals(was.learnedLogVar, now.learnedLogVar, 1e-12)
        assertEquals(was.learnedPasses, now.learnedPasses)
        assertEquals(was.signals, now.signals)
        assertEquals(was.exposure!!, now.exposure!!, 1e-12)
    }

    @Test
    fun aSegmentNeverLearnedFromJustTakesTheNewLength() {
        val was = stored(0, 0.0, 250.0, logMean = 0.0, passes = 0)
        val now = rebuildSegments(7, listOf(SegmentSpan(0.0, 240.0, 0)), listOf(was), line).single()
        assertEquals(0.0, now.learnedLogMean, 1e-12)
    }

    @Test
    fun aNewSegmentStartsFromNothing() {
        val now = rebuildSegments(7, listOf(SegmentSpan(0.0, 250.0, 0), SegmentSpan(250.0, 480.0, null)), listOf(stored(0, 0.0, 250.0)), line)
        val fresh = now[1]
        assertEquals(1, fresh.idx)
        assertEquals(0, fresh.learnedPasses)
        assertEquals(0.0, fresh.learnedLogMean, 1e-12)
        assertNull(fresh.gradePct)
        assertNull(fresh.signals)
        assertEquals(90.0, fresh.bearingDeg, 0.5)
    }

    @Test
    fun theProfileMovesWithTheRoadAndLeavesTheOldStretch() {
        fun bin(b: Int, rides: Int = 3) = RouteProfileEntity(7, b, -0.2, rides)
        // 0–300 m stays, 300–700 m is gone, from 700 m on everything is 200 m further along.
        val map = { m: Double -> if (m <= 300.0) m else if (m < 700.0) null else m + 200.0 }
        val moved = remapProfile(listOf(bin(2), bin(15), bin(30), bin(39)), map)
        assertEquals(listOf(2, 38, 47), moved.map { it.bin })
        assertEquals(-0.2, moved[1].logShape, 1e-12)
        // Two landing on one 25 m: the better known one stays.
        val squeezed = remapProfile(listOf(bin(4, rides = 2), bin(5, rides = 6)), { m -> m * 0.5 })
        assertEquals(1, squeezed.size)
        assertEquals(6, squeezed.single().rides)
    }
}
