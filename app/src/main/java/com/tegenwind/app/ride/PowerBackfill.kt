package com.tegenwind.app.ride

import com.tegenwind.app.data.LearningPass
import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RiderSetup
import com.tegenwind.app.eta.RiddenStretch
import com.tegenwind.app.eta.RiderParams
import com.tegenwind.app.eta.SteadyPower
import kotlinx.coroutines.flow.first

/** The effort of each ride in [passes] (real passes of any rides), by ride id; none where it can't be told. */
fun steadyWattsByRide(passes: List<LearningPass>, paramsOf: (rideId: Long) -> RiderParams): Map<Long, Double> =
    passes.groupBy { it.rideId }.mapNotNull { (rideId, ridden) ->
        val stretches = ridden.sortedBy { it.segIdx }.mapNotNull { p ->
            val wind = p.headwindMps ?: return@mapNotNull null
            RiddenStretch(p.endM - p.startM, p.gradePct ?: 0.0, wind, p.movingMs / 1000.0)
        }
        SteadyPower.watts(stretches, paramsOf(rideId))?.let { rideId to it }
    }.toMap()

/**
 * Rides stored before effort meant steady power ([SteadyPower]) kept a different kind of watts
 * (from form, with the wind mostly taken out). Works out every real ride's effort again from its
 * passes, on the bike it was ridden on; a ride that can't be judged is left without one.
 */
suspend fun backfillSteadyPower(rideDao: RideDao, setup: RiderSetup) {
    val passes = rideDao.learningPasses()
    val rides = rideDao.finishedRides().first().filter { !it.simulated }
    val bikeOf = rides.associate { r -> r.id to (setup.bikes.firstOrNull { it.name == r.bikeName } ?: setup.bikes.first()) }
    val watts = steadyWattsByRide(passes) { id -> setup.params(bikeOf.getValue(id)) }
    rideDao.updateRides(rides.map { it.copy(pedalPowerW = watts[it.id]) })
}
