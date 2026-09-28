package com.tegenwind.app.eta

import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.eta.SpeedProfileLearner.Pass
import com.tegenwind.app.ride.passesAlong
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class SpeedProfileTest {

    private val segments = listOf(0.0 to 250.0, 250.0 to 500.0, 500.0 to 750.0)

    /** A fix every 5 m at 25 km/h, except [slowFrom, slowTo) at 15 km/h: a bridge ramp in the first segment. */
    private fun ride(slowFrom: Double = 100.0, slowTo: Double = 150.0) =
        (0 until 150).map { i ->
            val m = i * 5.0 + 2.5
            Pass(m, if (m >= slowFrom && m < slowTo) 15.0 else 25.0)
        }

    @Test
    fun aRideShowsWhereItWasSlowWithinTheSegment() {
        val shape = SpeedProfileLearner.rideShape(ride(), segments)
        // Bins 4 and 5 (100–150 m) are the slow ones, the rest of that segment a little above average.
        assertTrue(shape.getValue(4) < 0.7)
        assertTrue(shape.getValue(5) < 0.7)
        assertTrue(shape.getValue(1) > 1.0)
        // Segments ridden at one speed are flat.
        assertEquals(1.0, shape.getValue(12), 1e-9)
        // Within a segment, the shapes average out to the segment's own speed (distance over time).
        val first = (0..9).map { shape.getValue(it) }
        assertEquals(1.0, first.size / first.sumOf { 1 / it }, 1e-9)
    }

    @Test
    fun stopsAndCrawlingDontCount() {
        val standing = ride() + (0 until 20).map { Pass(120.0, 0.5) }
        assertEquals(SpeedProfileLearner.rideShape(ride(), segments), SpeedProfileLearner.rideShape(standing, segments))
    }

    @Test
    fun theProfileNeverChangesASegmentsTime() {
        val bins = SpeedProfileLearner.update(emptyMap(), SpeedProfileLearner.rideShape(ride(), segments))
        val profile = SpeedProfile(bins)
        // Riding a segment at level × factor takes exactly as long as riding it at the level.
        for ((start, end) in segments) {
            val steps = generateSequence(start + 0.5) { it + 1.0 }.takeWhile { it < end }.toList()
            val relativeTime = steps.sumOf { 1 / profile.factorAt(it, start, end) } / steps.size
            assertEquals(1.0, relativeTime, 0.01)
        }
        // And it does dip where the ride was slow.
        assertTrue(profile.factorAt(125.0, 0.0, 250.0) < 0.75)
        // A route without a profile rides flat.
        assertEquals(1.0, SpeedProfile(emptyMap()).factorAt(125.0, 0.0, 250.0), 1e-9)
    }

    @Test
    fun ridesAreAveragedAndOldOnesFade() {
        val once = SpeedProfileLearner.update(emptyMap(), mapOf(3 to 0.8))
        assertEquals(ln(0.8), once.getValue(3).logShape, 1e-9)
        val twice = SpeedProfileLearner.update(once, mapOf(3 to 1.0))
        assertEquals(ln(0.8) / 2, twice.getValue(3).logShape, 1e-9)
        assertEquals(2, twice.getValue(3).rides)
        // After many rides a new one still counts for a fifth, so road works show up within a week or two.
        var many = once
        repeat(20) { many = SpeedProfileLearner.update(many, mapOf(3 to 0.8)) }
        val after = SpeedProfileLearner.update(many, mapOf(3 to 1.0)).getValue(3).logShape
        assertEquals(ln(0.8) * (1 - SpeedProfileLearner.MIN_WEIGHT), after, 1e-9)
        // One freak ride can't claim a stretch is ten times slower.
        val freak = SpeedProfileLearner.update(emptyMap(), mapOf(3 to 0.1)).getValue(3).logShape
        assertEquals(-SpeedProfileLearner.MAX_LOG, freak, 1e-9)
    }

    @Test
    fun onlyRidesAlongTheRouteTeachIt() {
        // A kilometre due east, and a ride along it at 6 m/s, one fix a second.
        val line = Polyline(listOf(GeoPoint(52.0, 5.0), GeoPoint(52.0, 5.0 + 1000.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(52.0))))))
        val fixes = (0..166).map { i ->
            val p = line.pointAt(i * 6.0)
            TrackPointEntity(rideId = 1, timeMs = i * 1000L, lat = p.lat, lon = p.lon, speedMps = 6.0, accuracyM = 5.0, altitudeM = null)
        }
        val along = passesAlong(line, fixes)
        assertNotNull(along)
        assertTrue(along!!.size > 150)
        // The same road the other way round is another route.
        assertNull(passesAlong(line, fixes.reversed().mapIndexed { i, f -> f.copy(timeMs = i * 1000L) }))
    }
}
