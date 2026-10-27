package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class RouteDriftTest {

    private val lat0 = 51.5
    private val lon0 = 4.2
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** Point [eastM] east and [northM] north of the origin. */
    private fun p(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon)

    private fun line(vararg corners: Pair<Double, Double>) = Polyline(corners.map { (e, n) -> p(e, n) })

    /** A fix every [stepM] along the corners given, in metres east and north. */
    private fun ride(vararg corners: Pair<Double, Double>, stepM: Double = 5.0): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (i in 0 until corners.size - 1) {
            val (e0, n0) = corners[i]
            val (e1, n1) = corners[i + 1]
            val len = hypot(e1 - e0, n1 - n0)
            val steps = maxOf(1, (len / stepM).toInt())
            for (k in 0 until steps) out += p(e0 + (e1 - e0) * k / steps, n0 + (n1 - n0) * k / steps)
        }
        out += corners.last().let { (e, n) -> p(e, n) }
        return out
    }

    private val straight = line(0.0 to 0.0, 1000.0 to 0.0)
    private val quarters = segmentBounds(1000.0)

    /** Along the straight line, but [northM] to the side of it. */
    private fun beside(northM: Double) = ride(-20.0 to northM, 1020.0 to northM)

    /** Along the straight line, except from 300 to 700 m, ridden a street [northM] away. */
    private fun around(northM: Double) =
        ride(-20.0 to 0.0, 300.0 to 0.0, 300.0 to northM, 700.0 to northM, 700.0 to 0.0, 1020.0 to 0.0)

    @Test
    fun aRideBesideTheLineMovesItAQuarterOfTheWay() {
        val n = nudge(straight, place(straight, beside(8.0)))
        assertNotNull(n)
        n!!
        assertEquals(2.0, n.movedM, 0.2)
        assertEquals(0.0, n.line.project(p(500.0, 2.0)).offsetM, 0.3)
        assertEquals(1000.0, n.line.lengthM, 1.0)
        assertEquals(500.0, n.map.map(500.0)!!, 1.0)
        // Moved sideways, not along: the ends stay the ends.
        assertEquals(0.0, n.line.project(p(0.0, 2.0)).alongM, 0.5)
    }

    @Test
    fun rideAfterRideTheLineSettlesWhereYouRide() {
        val folder = LineFolder(straight, quarters)
        repeat(20) { assertTrue(folder.fold(beside(8.0))) }
        // Within a metre: closer than that, a ride's move isn't worth storing.
        assertEquals(0.0, folder.line.project(p(500.0, 8.0)).offsetM, 1.0)
        assertEquals(0.0, folder.line.project(p(100.0, 8.0)).offsetM, 1.0)
        // Every segment is still the one it was, and only a little moved.
        assertEquals(listOf(0, 1, 2, 3), folder.spans.map { it.origIdx })
        assertEquals(250.0, folder.spans[1].startM, 2.0)
        assertEquals(folder.line.lengthM, folder.spans.last().endM, 1e-9)
    }

    @Test
    fun gpsNoiseAveragesOutInsteadOfBendingTheLine() {
        val random = java.util.Random(1)
        fun noisy(fixes: List<GeoPoint>) = fixes.map { q ->
            GeoPoint(q.lat + random.nextGaussian() * 2 / mPerDegLat, q.lon + random.nextGaussian() * 2 / mPerDegLon)
        }
        val folder = LineFolder(straight, quarters)
        repeat(15) { folder.fold(noisy(beside(8.0))) }
        assertEquals(0.0, folder.line.project(p(500.0, 8.0)).offsetM, 2.0)
        // No zigzag: hardly longer than the straight it is.
        assertEquals(1000.0, folder.line.lengthM, 5.0)
    }

    @Test
    fun aRideOnTheLineChangesNothing() {
        val folder = LineFolder(straight, quarters)
        assertTrue(folder.fold(beside(0.0)))
        assertFalse(folder.changed)
    }

    @Test
    fun aRideOnTooLittleOfTheLineSaysNothing() {
        val folder = LineFolder(straight, quarters)
        assertFalse(folder.fold(ride(-20.0 to 8.0, 300.0 to 8.0)))
        // 70% of the line is still too little; 80% is enough.
        assertFalse(folder.fold(ride(-20.0 to 8.0, 700.0 to 8.0)))
        assertFalse(folder.changed)
        assertTrue(folder.fold(ride(-20.0 to 8.0, 800.0 to 8.0)))
        assertTrue(folder.changed)
    }

    @Test
    fun aStretchRiddenOnAnotherStreetCountsAsCovered() {
        // 400 of 1000 m on a street 100 m away, the rest on the line: covered, so it can move the line.
        assertEquals(1.0, coverage(straight, place(straight, around(100.0))), 0.05)
        assertTrue(LineFolder(straight, quarters).fold(around(100.0), earlier = listOf(beside(0.0), beside(0.0))))
    }

    @Test
    fun anotherStreetDoesntPullTheLineOverToIt() {
        // The street 100 m away, and the streets to it, leave the line where it is.
        assertNull(nudge(straight, place(straight, around(100.0))))
    }

    @Test
    fun aCutCornerIsLearnedAsACutCorner() {
        val corner = line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0)
        val cut = ride(-20.0 to 0.0, 460.0 to 0.0, 500.0 to 40.0, 500.0 to 520.0)
        val folder = LineFolder(corner, quarters)
        repeat(30) { folder.fold(cut) }
        // The ridden way is 460 + 57 + 460 m, not 1000.
        assertEquals(460.0 + 40.0 * sqrt(2.0) + 460.0, folder.line.lengthM, 4.0)
        assertTrue(folder.line.project(p(480.0, 20.0)).offsetM < 3.0)
        assertEquals(listOf(0, 1, 2, 3), folder.spans.map { it.origIdx })
        // Distances before the corner hardly change; after it, they're shorter by what was cut.
        assertEquals(200.0, folder.map(200.0)!!, 2.0)
        assertEquals(900.0 - (1000.0 - folder.line.lengthM), folder.map(900.0)!!, 2.0)
    }

    @Test
    fun aDetourIsFoundWhereItLeavesAndRejoins() {
        val found = detours(place(straight, around(100.0)))
        assertEquals(1, found.size)
        assertEquals(300.0, found[0].leaveM, 10.0)
        assertEquals(700.0, found[0].rejoinM, 10.0)
        // Riding beside the line, or a single wild fix, is no detour.
        assertTrue(detours(place(straight, beside(8.0))).isEmpty())
        val spike = beside(0.0).toMutableList().also { it[100] = p(500.0, 120.0) }
        assertTrue(detours(place(straight, spike)).isEmpty())
    }

    @Test
    fun oneRideAroundTheBlockMovesTheLineAQuarterOfTheWayAndTwoReroute() {
        val folder = LineFolder(straight, quarters)
        folder.fold(around(100.0), earlier = listOf(beside(0.0), beside(0.0)))
        // A quarter of the way toward the street 100 m away: the stretch now runs 25 m north.
        assertTrue(folder.changed)
        assertNotNull(folder.rerouted)
        assertFalse(folder.reroutedFully)
        assertEquals(0.0, folder.line.project(p(500.0, 25.0)).offsetM, 2.0)
        // Each point moves a quarter of the way to the matching point of the ride, so the corners
        // become diagonals: (300, 0) to (350, 25), 300 m along, and back down at 700 m.
        assertEquals(600.0 + 2 * hypot(50.0, 25.0) + 300.0, folder.line.lengthM, 2.0)

        folder.fold(around(100.0), earlier = listOf(around(100.0), beside(0.0)))
        assertTrue(folder.reroutedFully)
        // The line now takes the other street: 300 m, then 100 + 400 + 100 m, then 300 m.
        assertEquals(0.0, folder.line.project(p(500.0, 100.0)).offsetM, 2.0)
        // About 1200 m: where the ride leaves the moved stretch at right angles to its diagonal
        // corner, a few metres of that diagonal stay at each end, for the next rides to straighten.
        assertEquals(1200.0, folder.line.lengthM, 25.0)
        val (from, to) = folder.rerouted!!
        assertEquals(300.0, from, 10.0)
        assertEquals(900.0, to, 25.0)

        // The first and last segments carry on; the two over the old stretch make way for new ones.
        val spans = folder.spans
        assertEquals(0, spans.first().origIdx)
        assertEquals(3, spans.last().origIdx)
        assertTrue(spans.drop(1).dropLast(1).all { it.origIdx == null })
        assertEquals(3, spans.size - 2) // 700 m between 250 m and the old 750 m, now at 950 m
        assertEquals(950.0, spans.last().startM, 25.0)
        assertEquals(folder.line.lengthM, spans.last().endM, 1e-9)
        // Where distances went: the same before, gone on the old stretch, 200 m further after it.
        assertEquals(100.0, folder.map(100.0)!!, 1.0)
        assertNull(folder.map(500.0))
        assertEquals(1000.0, folder.map(800.0)!!, 25.0)
    }

    @Test
    fun twoDifferentDetoursOnlyMoveTheLineAQuarterOfTheWay() {
        val folder = LineFolder(straight, quarters)
        folder.fold(around(100.0), earlier = listOf(around(-100.0), beside(0.0)))
        assertFalse(folder.reroutedFully)
        assertEquals(0.0, folder.line.project(p(500.0, 25.0)).offsetM, 2.0)
        assertTrue(folder.line.project(p(500.0, 100.0)).offsetM > 50.0)
    }

    @Test
    fun aStreetFarAwayPullsItsStretchAQuarterOfTheWayAndNoMore() {
        val detour = detours(place(straight, around(150.0))).single()
        val r = blend(straight, detour)
        assertFalse(r.full)
        // 150 m away: the stretch moves 37.5 m toward it, between where the ride left and rejoined.
        assertEquals(0.0, r.line.project(p(500.0, 37.5)).offsetM, 2.0)
        assertEquals(0.0, r.line.project(p(100.0, 0.0)).offsetM, 0.5)
        assertEquals(0.0, r.line.project(p(900.0, 0.0)).offsetM, 0.5)
        // The ride's corners (150 m in on a 700 m way) land 3/14 of the way along: diagonals to (364, 37.5).
        val corner = 0.75 * 400.0 * 3 / 14
        val stretch = 2 * hypot(corner, 37.5) + (400.0 - 2 * corner)
        assertEquals(600.0 + stretch, r.line.lengthM, 2.0)
        // Distances before the stretch stay; after it they move on by what the stretch grew.
        assertEquals(100.0, r.map.map(100.0)!!, 1.0)
        assertNull(r.map.map(500.0))
        assertEquals(900.0 + stretch - 400.0, r.map.map(900.0)!!, 2.0)
    }

    @Test
    fun theStartupPassNeverReroutes() {
        // Without earlier rides to compare with, a detour on every ride still only nudges.
        val folder = LineFolder(straight, quarters)
        repeat(3) { folder.fold(around(100.0)) }
        assertNull(folder.rerouted)
        assertEquals(1000.0, folder.line.lengthM, 1.0)
    }

    @Test
    fun theAlongMapLeavesOutARerouteButShiftsWhatFollows() {
        val map = AlongMap(doubleArrayOf(0.0, 300.0, 700.0, 1000.0), doubleArrayOf(0.0, 300.0, 900.0, 1200.0), gap = 300.0 to 700.0)
        assertEquals(150.0, map.map(150.0)!!, 1e-9)
        assertEquals(300.0, map.map(300.0)!!, 1e-9)
        assertNull(map.map(500.0))
        assertEquals(900.0, map.map(700.0)!!, 1e-9)
        assertEquals(1050.0, map.map(850.0)!!, 1e-9)
    }

    @Test
    fun segmentsFollowANudgeUnchanged() {
        val map = AlongMap(doubleArrayOf(0.0, 1000.0), doubleArrayOf(0.0, 990.0))
        val spans = remapSpans(quarters.mapIndexed { i, (s, e) -> SegmentSpan(s, e, i) }, map, 990.0)
        assertEquals(listOf(0, 1, 2, 3), spans.map { it.origIdx })
        assertEquals(247.5, spans[1].startM, 1e-9)
        assertEquals(990.0, spans.last().endM, 1e-9)
    }

    @Test
    fun aSpikeInTheLineDoesNotCostTheStretchAfterIt() {
        // The line goes 10 m north, doubles back, and only then sets off east: at the tip of the
        // spike it has no direction. Nudging once threw away everything after such a point.
        val spiked = line(0.0 to 0.0, 0.0 to 5.0, 0.0 to 10.0, 0.0 to 5.0, 0.0 to 0.0, 1000.0 to 0.0)
        val n = nudge(spiked, place(spiked, ride(0.0 to 6.0, 1000.0 to 6.0)))
        assertNotNull(n)
        assertEquals(spiked.lengthM, n!!.line.lengthM, 0.03 * spiked.lengthM)
        assertEquals(0.0, n.line.project(p(500.0, 1.5)).offsetM, 0.5)
        assertEquals(0.0, n.line.project(p(900.0, 1.5)).offsetM, 0.5)
    }

    @Test
    fun aRideThatRodeTheRouteButLiesAwayFromItsLineSaysTheLineIsDamaged() {
        val road = ride(0.0 to 0.0, 500.0 to 400.0, 1000.0 to 0.0)
        val bent = line(0.0 to 0.0, 500.0 to 400.0, 1000.0 to 0.0)
        assertFalse(contradicts(bent, road))
        // The same route with its corner lost: most of the ride is now far from it.
        assertTrue(contradicts(straight, road))
        // Too few fixes to judge by.
        assertFalse(contradicts(straight, road.take(20)))
    }

    @Test
    fun segmentsKeepTheirShareOfALineOfAnotherLength() {
        val spans = rescaleSpans(listOf(0.0 to 200.0, 200.0 to 400.0, 400.0 to 800.0), 1000.0)
        assertEquals(listOf(0, 1, 2), spans.map { it.origIdx })
        assertEquals(listOf(0.0, 250.0, 500.0), spans.map { it.startM })
        assertEquals(1000.0, spans.last().endM, 1e-9)
    }

    @Test
    fun aRideIsCutToWhereTheLineStartsAndEnds() {
        val whole = ride(-200.0 to 0.0, 1200.0 to 0.0)
        val cut = between(whole, p(0.0, 0.0), p(1000.0, 0.0))
        assertEquals(0.0, distanceM(cut.first(), p(0.0, 0.0)), 3.0)
        assertEquals(0.0, distanceM(cut.last(), p(1000.0, 0.0)), 3.0)
    }
}
