package com.tegenwind.app.ride

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.data.RouteDao
import com.tegenwind.app.data.RouteProfileEntity
import com.tegenwind.app.data.TrackPointEntity
import com.tegenwind.app.eta.ProfileBin
import com.tegenwind.app.eta.SpeedProfileLearner
import com.tegenwind.app.eta.SpeedProfileLearner.Pass
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.routes.LoadedRoute
import com.tegenwind.app.routes.Polyline
import com.tegenwind.app.routes.RouteTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Further from the line than this, a fix says nothing about how that stretch of road rides. */
private const val MAX_OFFSET_M = 25.0

/**
 * Where a ride's fixes were along [line], and how fast, following it the way the ride screen does:
 * forward only, fixes off the route or inaccurate left out. Null when the ride isn't this route: too
 * few of its fixes on the line (a different route, or this one ridden the other way), or too little of
 * the route covered.
 */
fun passesAlong(line: Polyline, points: List<TrackPointEntity>): List<Pass>? {
    val usable = points.filter { it.speedMps != null && (it.accuracyM ?: 0.0) <= RideTracker.MAX_ACCURACY_M }
    if (usable.isEmpty()) return null
    val tracker = RouteTracker(line)
    val passes = ArrayList<Pass>()
    for (p in usable) {
        val progress = tracker.update(GeoPoint(p.lat, p.lon))
        if (progress.onRouteYet && !progress.offRoute && progress.offsetM <= MAX_OFFSET_M) {
            passes += Pass(progress.progressM, p.speedMps!! * 3.6)
        }
    }
    if (passes.size < usable.size * MIN_SHARE_ON_ROUTE) return null
    val covered = passes.maxOf { it.alongM } - passes.minOf { it.alongM }
    return passes.takeIf { covered >= line.lengthM * MIN_SHARE_COVERED }
}

/** Half a ride's fixes on the line, over at least a third of it: any less and it's another route. */
private const val MIN_SHARE_ON_ROUTE = 0.5
private const val MIN_SHARE_COVERED = 0.33

private fun LoadedRoute.segmentBounds() = segments.map { it.startM to it.endM }

private fun List<RouteProfileEntity>.asBins() = associate { it.bin to ProfileBin(it.logShape, it.rides) }

private fun Map<Int, ProfileBin>.asEntities(routeId: Long) =
    map { (bin, b) -> RouteProfileEntity(routeId, bin, b.logShape, b.rides) }

/** Folds one finished ride into the speed profile of the route it rode. Simulated rides never get here. */
suspend fun learnProfile(routeDao: RouteDao, route: LoadedRoute, points: List<TrackPointEntity>) {
    // Thousands of fixes against the route line: off the main thread.
    val shape = withContext(Dispatchers.Default) {
        passesAlong(route.line, points)?.let { SpeedProfileLearner.rideShape(it, route.segmentBounds()) }
    } ?: return
    if (shape.isEmpty()) return
    val id = route.route.id
    val updated = SpeedProfileLearner.update(routeDao.profile(id).asBins(), shape)
    routeDao.saveProfile(updated.asEntities(id))
}

/**
 * Routes that were ridden before rides learned a speed profile get one from the rides already
 * stored, oldest first, so it is useful straight away rather than after a few more rides. Runs once
 * per route: afterwards the route has a profile and every new ride adds to it.
 */
suspend fun backfillProfiles(rideDao: RideDao, routeDao: RouteDao, load: suspend (Long) -> LoadedRoute?) {
    val todo = routeDao.routesWithoutProfile()
    if (todo.isEmpty()) return
    val rides = rideDao.realRidesOldestFirst()
    for (routeId in todo) {
        val route = load(routeId) ?: continue
        var bins = emptyMap<Int, ProfileBin>()
        for (ride in rides) {
            val points = rideDao.points(ride.id)
            val shape = withContext(Dispatchers.Default) {
                passesAlong(route.line, points)?.let { SpeedProfileLearner.rideShape(it, route.segmentBounds()) }
            } ?: continue
            bins = SpeedProfileLearner.update(bins, shape)
        }
        if (bins.isNotEmpty()) routeDao.saveProfile(bins.asEntities(routeId))
    }
}
