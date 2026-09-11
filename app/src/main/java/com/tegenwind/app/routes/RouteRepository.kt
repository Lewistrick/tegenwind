package com.tegenwind.app.routes

import com.tegenwind.app.data.EnrichState
import com.tegenwind.app.data.RouteDao
import com.tegenwind.app.data.RouteEntity
import com.tegenwind.app.data.RoutePointEntity
import com.tegenwind.app.data.RouteSegmentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** A route ready for riding: its line plus the per-segment data. */
data class LoadedRoute(val route: RouteEntity, val line: Polyline, val segments: List<RouteSegmentEntity>) {
    val signalsAtM: List<Double>
        get() = segments.flatMap { s -> List(s.signals ?: 0) { s.endM } }
}

class RouteRepository(
    private val dao: RouteDao,
    private val enricher: RouteEnricher,
    private val scope: CoroutineScope,
) {
    fun routes(): Flow<List<RouteEntity>> = dao.routes()

    /** Saves a GPX file as a route and starts looking up elevation and map data. */
    suspend fun importGpx(name: String, input: InputStream): Long {
        val points = cleanPoints(withContext(Dispatchers.IO) { input.use { Gpx.parse(it) } })
        if (points.size < 2) throw IllegalArgumentException("No track in this file")
        return save(name, Polyline(points))
    }

    /** Turns a recorded ride's GPS track into a route. Standing still and GPS wobble are filtered out. */
    suspend fun createFromTrack(name: String, track: List<GeoPoint>): Long {
        val points = simplify(cleanPoints(track, minStepM = 3.0), toleranceM = 5.0)
        if (points.size < 2 || Polyline(points).lengthM < 200) throw IllegalArgumentException("This ride is too short for a route")
        return save(name, Polyline(points))
    }

    /** Creates the same route in the other direction, e.g. "werk-woon" from "woon-werk". */
    suspend fun createReverse(routeId: Long): Long {
        val route = dao.route(routeId) ?: throw IllegalArgumentException("Route not found")
        val line = Polyline(dao.points(routeId).map { GeoPoint(it.lat, it.lon) }).reversed()
        return save(reverseName(route.name), line)
    }

    suspend fun delete(routeId: Long) = dao.delete(routeId)

    suspend fun load(routeId: Long): LoadedRoute? {
        val route = dao.route(routeId) ?: return null
        val line = Polyline(dao.points(routeId).map { GeoPoint(it.lat, it.lon) })
        return LoadedRoute(route, line, dao.segments(routeId))
    }

    private suspend fun save(name: String, line: Polyline): Long {
        val segments = segmentBounds(line.lengthM).mapIndexed { i, (start, end) ->
            RouteSegmentEntity(
                routeId = 0, idx = i, startM = start, endM = end,
                bearingDeg = bearingDeg(line.pointAt(start), line.pointAt(end)),
            )
        }
        val id = dao.insertFull(
            RouteEntity(name = name.trim(), lengthM = line.lengthM, createdAtMs = System.currentTimeMillis()),
            line.points.mapIndexed { i, p -> RoutePointEntity(0, i, p.lat, p.lon) },
            segments,
        )
        enrich(id)
        return id
    }

    /** Runs in the background; the route shows its progress through [RouteEntity.enrichState]. */
    fun enrich(routeId: Long) {
        scope.launch {
            try {
                dao.setEnrichState(routeId, EnrichState.RUNNING, null)
                val loaded = load(routeId) ?: return@launch
                val line = loaded.line
                val bounds = loaded.segments.map { it.startM to it.endM }

                // Elevation every 50 m, then a straight-line fit per segment.
                val sampleAt = generateSequence(0.0) { it + ELEVATION_STEP_M }.takeWhile { it < line.lengthM }.toList() + line.lengthM
                val elevations = enricher.elevations(sampleAt.map(line::pointAt))
                val osm = enricher.osmStats(line, bounds)

                // A signal counts for the segment it's on, if it's within 25 m of the route.
                // Junctions have a signal node per approach; lights within 40 m count as one stop.
                val signalAlong = clusterAlong(
                    osm.signalPoints.map { line.project(it) }.filter { it.offsetM <= 25.0 }.map { it.alongM },
                    withinM = 40.0,
                )

                val updated = loaded.segments.mapIndexed { i, s ->
                    val idx = sampleAt.indices.filter { sampleAt[it] >= s.startM - 1 && sampleAt[it] <= s.endM + 1 }
                    s.copy(
                        gradePct = gradePct(idx.map { sampleAt[it] }, idx.map { elevations[it] }),
                        buildings = osm.buildingCounts[i],
                        exposure = RouteEnricher.exposureFromBuildings(osm.buildingCounts[i]),
                        signals = signalAlong.count { it >= s.startM && (it < s.endM || i == loaded.segments.lastIndex) },
                    )
                }
                dao.updateSegments(updated)
                dao.setEnrichState(routeId, EnrichState.DONE, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.setEnrichState(routeId, EnrichState.FAILED, e.message ?: e::class.simpleName)
            }
        }
    }

    private companion object {
        const val ELEVATION_STEP_M = 50.0
    }
}
