package com.tegenwind.app.eta

import android.util.Log
import com.tegenwind.app.data.RideDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Holds what has been learned about how you ride ([RiderLearned]). Nothing is stored of its own:
 * it is worked out again from the stored rides at start-up and after every real ride, so it can
 * never disagree with them.
 */
class RiderModel(private val rides: RideDao) {
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
        val learned = RiderFit.learn(windPasses, rides.rideFacts(), facts, meanLogVar)
        _learned.value = learned
        Log.i(
            "RiderModel",
            "wind %.0f%% of modelled, form sd %.1f%%, pass noise %.1f%% (own %.1f%%), %.1f s per light (sd %.0f s), from %d rides"
                .format(
                    learned.windFeel * 100, learned.formSd * 100, learned.passNoise * 100, learned.passExtra * 100,
                    learned.stopMeanS, kotlin.math.sqrt(learned.stopVarS2), learned.rides,
                ),
        )
    }
}
