package com.tegenwind.app.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class LearningTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun segmentLearnsHowMuchSlowerItReallyIs() {
        var c = SegmentCorrection()
        // A segment that always takes 60 s where physics says 50 s: 20% slower.
        repeat(15) { c = SegmentLearner.update(c, actualMovingMs = 60_000, physicsMovingMs = 50_000) }
        // Most of the way there after a week of commuting; it stays deliberately slow to convince.
        assertEquals(1.2, c.timeFactor, 0.03)
        assertEquals(15, c.passes)
        // And it is far surer of that than it was before the first pass.
        assertTrue("var ${c.logVar}", c.logVar < SegmentLearner.PRIOR_VAR / 4)
    }

    @Test
    fun learningFollowsRoadWorksInsteadOfAveragingOverThem() {
        var c = SegmentCorrection()
        repeat(20) { c = SegmentLearner.update(c, 50_000, 50_000) }
        assertEquals(1.0, c.timeFactor, 0.02)
        // The road is dug up and every pass now costs half as much again. Overturning a settled
        // belief takes more evidence than forming one, so it closes the gap steadily rather than
        // jumping: what matters is that it keeps moving and never gets stuck.
        repeat(20) { c = SegmentLearner.update(c, 75_000, 50_000) }
        val afterTwoWeeks = c.timeFactor
        assertTrue("$afterTwoWeeks", afterTwoWeeks > 1.2)
        repeat(20) { c = SegmentLearner.update(c, 75_000, 50_000) }
        assertTrue("${c.timeFactor}", c.timeFactor > afterTwoWeeks)
        assertTrue("${c.timeFactor}", c.timeFactor < 1.5)
    }

    @Test
    fun aSlowDayIsNotBlamedOnTheRoad() {
        val prior = SegmentCorrection()
        // 55 s where physics says 50 s: on a day you rode at 91% of your usual speed that's just
        // you, so the segment learns nothing; on a normal day it's the road, and it does.
        val tired = SegmentLearner.update(prior, 55_000, 50_000, form = 50.0 / 55.0)
        assertEquals(1.0, tired.timeFactor, 1e-9)
        assertEquals(1, tired.passes)
        val normal = SegmentLearner.update(prior, 55_000, 50_000, form = 1.0)
        assertTrue("factor ${normal.timeFactor}", normal.timeFactor > 1.03)
    }

    @Test
    fun tooShortOrNonsensePassesTeachNothing() {
        val prior = SegmentCorrection()
        assertEquals(prior, SegmentLearner.update(prior, 1_000, 50_000))
        assertEquals(prior, SegmentLearner.update(prior, 60_000, 0))
        // A pass ten times slower than physics is clamped, not believed outright.
        val wild = SegmentLearner.update(prior, 500_000, 50_000)
        assertTrue("factor ${wild.timeFactor}", wild.timeFactor < 1.3)
    }

    @Test
    fun correctedEtaLeansOnWhatTheSegmentsLearned() {
        val plain = (0 until 10).map { EtaSegment(it * 250.0, (it + 1) * 250.0, 90.0, 0.0, 1.0, 0) }
        var learned = SegmentCorrection()
        repeat(15) { learned = SegmentLearner.update(learned, 60_000, 50_000) }
        val slow = plain.map { it.copy(learned = learned) }
        val form = FormEstimate(1.0, 0.0)

        val before = EtaModel(plain).predict(0.0, 0, null, form)
        val after = EtaModel(slow).predict(0.0, 0, null, form)
        assertEquals(before.remainingS * 1.2, after.remainingS, before.remainingS * 0.03)
        // Knowing a road narrows the band: unlearned segments carry the full prior.
        assertTrue("${after.sigmaS} vs ${before.sigmaS}", after.sigmaS < before.sigmaS)
    }

    @Test
    fun steadyRidingLeavesYouNeitherFreshNorTired() {
        val loads = (0 until 60).map { RideLoad(it * day, 50.0) }
        val state = Banister.evaluate(loads, 59 * day)
        assertEquals(50.0, state.fitness, 1.0)
        assertEquals(state.fitness, state.fatigue, 1.0)
        assertEquals(1.0, Banister.freshnessFactor(state), 0.005)
    }

    @Test
    fun aHardWeekLeavesYouTiredAndAQuietOneFresh() {
        val base = (0 until 60).map { RideLoad(it * day, 50.0) }
        val hard = base + (60 until 67).map { RideLoad(it * day, 200.0) }
        val tired = Banister.evaluate(hard, 66 * day)
        assertTrue("freshness ${tired.freshness}", tired.freshness < 0)
        assertTrue(Banister.freshnessFactor(tired) < 1.0)

        // Two quiet weeks after the same base: fatigue falls away faster than fitness.
        val rested = Banister.evaluate(base, 74 * day)
        assertTrue("freshness ${rested.freshness}", rested.freshness > 0)
        assertTrue(Banister.freshnessFactor(rested) > 1.0)
    }

    @Test
    fun freshnessNeverMovesTheEtaFarAndNeedsHistory() {
        // A freak week of enormous rides still only moves the ETA by a few percent.
        val wild = (0 until 30).map { RideLoad(it * day, if (it >= 23) 5_000.0 else 50.0) }
        assertTrue(Banister.freshnessFactor(Banister.evaluate(wild, 29 * day)) >= 0.95)
        val recovered = Banister.freshnessFactor(Banister.evaluate(wild, 60 * day))
        assertTrue("$recovered", recovered <= 1.05)
        // A handful of rides is not enough to claim anything about freshness.
        val few = (0 until 5).map { RideLoad(it * day, 60.0) }
        assertEquals(1.0, Banister.freshnessFactor(Banister.evaluate(few, 5 * day)), 1e-9)
        assertEquals(1.0, Banister.freshnessFactor(Banister.evaluate(emptyList(), day)), 1e-9)
    }

    @Test
    fun loadGrowsWithTimeAndPace() {
        val easyHour = Banister.loadFromRide(3_600_000, 20_000.0)
        val briskHour = Banister.loadFromRide(3_600_000, 28_000.0)
        val halfHour = Banister.loadFromRide(1_800_000, 10_000.0) // same pace, half the ride
        assertTrue("$easyHour", easyHour in 40.0..80.0)
        assertTrue(briskHour > easyHour)
        assertEquals(easyHour / 2, halfHour, 1e-6)
        assertEquals(0.0, Banister.loadFromRide(0, 5_000.0), 1e-9)
    }

    @Test
    fun heartRateGivesAComparableLoadToPace() {
        val hour = 3_600_000L
        val steady = Banister.loadFromHeartRate(List(100) { 140.0 }, hour)
        val harder = Banister.loadFromHeartRate(List(100) { 165.0 }, hour)
        assertTrue("$steady", steady in 50.0..150.0)
        assertTrue(harder > steady)
        assertEquals(0.0, Banister.loadFromHeartRate(emptyList(), hour), 1e-9)
    }

    @Test
    fun startingFormCombinesRecentRidesWithFreshness() {
        val forms = listOf(0.95, 0.96, 0.94)
        val steady = (0 until 60).map { RideLoad(it * day, 50.0) }
        assertEquals(0.95, startingForm(forms, steady, 59 * day), 0.005)
        // After a heavy week the same rider starts a touch slower.
        val hard = steady + (60 until 67).map { RideLoad(it * day, 200.0) }
        assertTrue(startingForm(forms, hard, 66 * day) < 0.95)
    }

    @Test
    fun whatAllSegmentsShareIsTheirAverageOverWhatYouRide() {
        val tilt = SegmentLearner.sharedTilt(
            listOf(
                250.0 to SegmentCorrection(logMean = 0.2, passes = 3),
                250.0 to SegmentCorrection(logMean = 0.0, passes = 1),
                // Never ridden: it has nothing to share.
                500.0 to SegmentCorrection(logMean = -0.1, passes = 0),
            )
        )
        assertEquals(0.15, tilt, 1e-12)
        assertEquals(0.0, SegmentLearner.sharedTilt(emptyList()), 0.0)
    }

    @Test
    fun movingTheSharedTiltIntoFormKeepsEveryRiddenEtaAndFixesNewRoads() {
        fun seg(i: Int, c: SegmentCorrection) = EtaSegment(i * 250.0, (i + 1) * 250.0, 90.0, 0.0, 0.6, 0, c)
        val ridden = listOf(seg(0, SegmentCorrection(0.15, passes = 5)), seg(1, SegmentCorrection(0.05, passes = 5)))
        val form = FormEstimate(0.9, 0.0004)
        val tilt = SegmentLearner.sharedTilt(ridden.map { it.lengthM to it.learned })
        assertEquals(0.1, tilt, 1e-12)

        val shifted = ridden.map { it.copy(learned = it.learned.copy(logMean = it.learned.logMean - tilt)) }
        val newForm = form.copy(mean = form.mean * exp(-tilt))
        // Every ridden segment takes as long as it did...
        assertEquals(
            EtaModel(ridden).predict(0.0, 0L, null, form).remainingS,
            EtaModel(shifted).predict(0.0, 0L, null, newForm).remainingS,
            1e-6,
        )
        // ...the segments now average 0...
        assertEquals(0.0, SegmentLearner.sharedTilt(shifted.map { it.lengthM to it.learned }), 1e-12)
        // ...and a road never ridden is expected at your real pace: slower by what they shared.
        val fresh = EtaModel(listOf(seg(0, SegmentCorrection())))
        assertEquals(exp(tilt), fresh.predict(0.0, 0L, null, newForm).remainingS / fresh.predict(0.0, 0L, null, form).remainingS, 1e-9)
    }

    @Test
    fun theStartingFormLeavesRoomForASlowRider() {
        assertEquals(0.6, priorForm(listOf(0.6, 0.6, 0.6)), 1e-12)
        assertEquals(0.5, priorForm(listOf(0.3)), 1e-12)
        assertEquals(1.6, priorForm(listOf(2.0)), 1e-12)
        // Scaling every stored form scales their median by the same factor.
        val forms = listOf(0.8, 0.9, 1.0, 0.85, 0.95)
        assertEquals(priorForm(forms) * 0.9, priorForm(forms.map { it * 0.9 }), 1e-12)
    }
}
