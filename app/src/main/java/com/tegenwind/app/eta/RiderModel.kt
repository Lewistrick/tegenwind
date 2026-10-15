package com.tegenwind.app.eta

import android.util.Log
import com.tegenwind.app.data.RideDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.ln

/**
 * Holds what has been learned about how you ride ([RiderLearned]). Nothing is stored of its own:
 * it is worked out again from the stored rides at start-up and after every real ride, so it can
 * never disagree with them.
 */
class RiderModel(
    private val rides: RideDao,
    /** The physics of the rider and bike now set, for fitting how much of the wind you feel. */
    private val params: () -> RiderParams = { RiderParams() },
) {
    private val _learned = MutableStateFlow(RiderLearned())
    val learned: StateFlow<RiderLearned> = _learned.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.Default) {
        val passes = rides.learningPasses()
        val windPasses = passes.mapNotNull {
            val wind = it.headwindMps ?: return@mapNotNull null
            WindPass(
                rideId = it.rideId,
                segmentKey = it.routeId * 10_000 + it.segIdx,
                lengthM = it.endM - it.startM,
                gradePct = it.gradePct ?: 0.0,
                headwindMps = wind,
                movingS = it.movingMs / 1000.0,
            )
        }
        val facts = passes.mapNotNull { p -> p.expectedMovingMs?.let { PassFacts(p.movingMs, it) } }
        val meanLogVar = if (passes.isEmpty()) 0.0 else passes.sumOf { it.learnedLogVar } / passes.size
        val byRide = passes.filter { it.expectedMovingMs != null && it.movingMs >= 5_000 }
            .groupBy { it.rideId }
            .values.map { r -> r.sortedBy { it.segIdx }.map { ln(it.movingMs.toDouble() / it.expectedMovingMs!!).coerceIn(-0.7, 0.7) } }
        // How long you stood still on each segment's latest passes: what was neither moving nor elsewhere.
        val stops = passes.groupBy { RiderLearned.stopKey(it.routeId, it.segIdx) }.mapValues { (_, p) ->
            p.sortedByDescending { it.enterMs }.take(StopLearner.RECENT)
                .map { ((it.exitMs - it.enterMs - it.movingMs) / 1000.0).coerceAtLeast(0.0) }
        }
        val learned = RiderFit.learn(windPasses, rides.rideFacts(), facts, meanLogVar, byRide, stops, params())
        _learned.value = learned
        Log.i(
            "RiderModel",
            "wind %.0f%% of modelled, form sd %.1f%% (drifts %.2f%% per segment), pass noise %.1f%% (own %.1f%%), stops learned on %d segments (never standing still on %d), from %d rides"
                .format(
                    learned.windFeel * 100, learned.formSd * 100, 100 * kotlin.math.sqrt(learned.formDrift), learned.passNoise * 100, learned.passExtra * 100,
                    learned.stops.size, learned.stops.values.count { StopLearner.ignoresLights(it) }, learned.rides,
                ),
        )
    }
}
