package com.tegenwind.app.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.tegenwind.app.eta.RideLoad
import com.tegenwind.app.routes.RouteStart
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val simulated: Boolean = false,
    /** The route followed, if one was picked. No foreign key: deleting a route keeps its rides. */
    val routeId: Long? = null,
    /** Forecast wind at 10 m when the ride started. */
    val windSpeedMps: Double? = null,
    val windFromDeg: Double? = null,
    /** Speed relative to the physics model at the end of the ride (1.0 = as predicted). */
    val formFactor: Double? = null,
    /**
     * Training load for the fitness model: TRIMP once heart rate has been read for this ride,
     * otherwise estimated from how long and how hard you rode.
     */
    val loadTss: Double? = null,
    /**
     * The stretch of its route's line (metres along it, as the line was then) that this ride
     * rerouted: you took another street there, as on another recent ride. Null when it rerouted nothing.
     */
    val rerouteStartM: Double? = null,
    val rerouteEndM: Double? = null,
)

/** The stretch a ride rerouted; see [RideEntity.rerouteStartM]. */
data class RideReroute(val rerouteStartM: Double?, val rerouteEndM: Double?)

/** How long one ride took over one route segment: the raw material for stats and learning. */
@Entity(
    tableName = "segment_traversals",
    primaryKeys = ["rideId", "segIdx"],
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("routeId", "segIdx")],
)
data class SegmentTraversalEntity(
    val rideId: Long,
    val routeId: Long,
    val segIdx: Int,
    val enterMs: Long,
    val exitMs: Long,
    /** Time spent moving; exitMs - enterMs - movingMs was spent standing still (lights, crossings). */
    val movingMs: Long,
    /** Forecast headwind at rider height when entering the segment, m/s (negative = tailwind). */
    val headwindMps: Double?,
    /** Physics prediction for the moving time at form 1.0, for later comparison. */
    val predictedMovingMs: Long,
    /** What the model expected before this ride: physics, the segment's learned correction and the day's form. */
    val expectedMovingMs: Long? = null,
    /** How much this pass moved the segment's learned moving time (at form 1.0); null when nothing was learned. */
    val learnedShiftMs: Long? = null,
)

/** Every GPS fix as received, including inaccurate ones (the ride stats skip those). */
@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val accuracyM: Double?,
    val altitudeM: Double?,
)

object EnrichState {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lengthM: Double,
    val createdAtMs: Long,
    /** Elevation and OpenStreetMap lookup: see [EnrichState]. */
    val enrichState: String = EnrichState.PENDING,
    val enrichError: String? = null,
    /** When the current lookup started, so the UI can tell a slow one from a stalled one. */
    val enrichStartedAtMs: Long? = null,
    /**
     * The start time of the last ride folded into the line (see [com.tegenwind.app.routes.RouteDrift]).
     * Null for a route from before lines followed rides: all its rides are still to be folded in.
     */
    val lineFoldedThroughMs: Long? = null,
)

@Entity(
    tableName = "route_points",
    primaryKeys = ["routeId", "idx"],
    foreignKeys = [ForeignKey(RouteEntity::class, ["id"], ["routeId"], onDelete = ForeignKey.CASCADE)],
)
data class RoutePointEntity(
    val routeId: Long,
    val idx: Int,
    val lat: Double,
    val lon: Double,
)

/** A ~250 m stretch of a route. Enrichment fills in the nullable fields. */
@Entity(
    tableName = "route_segments",
    primaryKeys = ["routeId", "idx"],
    foreignKeys = [ForeignKey(RouteEntity::class, ["id"], ["routeId"], onDelete = ForeignKey.CASCADE)],
)
data class RouteSegmentEntity(
    val routeId: Long,
    val idx: Int,
    val startM: Double,
    val endM: Double,
    /** Direction of travel, degrees clockwise from north. */
    val bearingDeg: Double,
    val gradePct: Double? = null,
    /** Buildings within ~125 m of the segment's middle, from OpenStreetMap. */
    val buildings: Int? = null,
    /** 1 = open field, fully exposed to wind; ~0.15 = sheltered between buildings. */
    val exposure: Double? = null,
    val signals: Int? = null,
    /**
     * What this segment costs beyond the physics baseline, learned from every pass:
     * the posterior mean of log(actual / predicted moving time). Positive = slower than physics says.
     */
    @ColumnInfo(defaultValue = "0.0")
    val learnedLogMean: Double = 0.0,
    /** Posterior variance of [learnedLogMean]; starts wide and narrows with every pass. */
    @ColumnInfo(defaultValue = "0.0324")
    val learnedLogVar: Double = 0.18 * 0.18,
    /** Clean passes that went into the correction, for the "what I learned" card. */
    @ColumnInfo(defaultValue = "0")
    val learnedPasses: Int = 0,
)

/**
 * Heart rate during a ride, as read from Health Connect, for fitting the in-ride heart-rate estimate.
 * Kept for [com.tegenwind.app.health.HR_KEEP_DAYS] days only; Health Connect keeps its own copy.
 */
@Entity(
    tableName = "hr_samples",
    primaryKeys = ["rideId", "timeMs"],
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("timeMs")],
)
data class HrSampleEntity(val rideId: Long, val timeMs: Long, val bpm: Double)

/**
 * How speed varies within the segments of a route, per 25 m: see [com.tegenwind.app.eta.SpeedProfile].
 * Only bins some ride has taught anything are stored.
 */
@Entity(
    tableName = "route_profile",
    primaryKeys = ["routeId", "bin"],
    foreignKeys = [ForeignKey(RouteEntity::class, ["id"], ["routeId"], onDelete = ForeignKey.CASCADE)],
)
data class RouteProfileEntity(
    val routeId: Long,
    /** Which 25 m of the route: bin 0 is the first 25 m. */
    val bin: Int,
    /** Log of the speed there relative to its segment's average: negative is slower. */
    val logShape: Double,
    /** Rides that went into it. */
    val rides: Int,
)

/** Everything stored about one route's line; see [RouteDao.full]. */
data class FullRoute(
    val route: RouteEntity,
    val points: List<RoutePointEntity>,
    val segments: List<RouteSegmentEntity>,
    val profile: List<RouteProfileEntity>,
)

@Dao
interface RideDao {
    @Insert
    suspend fun insertRide(ride: RideEntity): Long

    @Update
    suspend fun updateRide(ride: RideEntity)

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM rides WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC")
    fun finishedRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE endedAtMs IS NULL")
    suspend fun unfinishedRides(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun ride(id: Long): RideEntity?

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY timeMs")
    suspend fun points(rideId: Long): List<TrackPointEntity>

    @Query("SELECT MAX(timeMs) FROM track_points WHERE rideId = :rideId")
    suspend fun lastPointTime(rideId: Long): Long?

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertTraversals(traversals: List<SegmentTraversalEntity>)

    /** Recent end-of-ride form factors, newest first: the starting guess for the next ride. */
    @Query("SELECT formFactor FROM rides WHERE formFactor IS NOT NULL AND simulated = 0 ORDER BY startedAtMs DESC LIMIT 10")
    suspend fun recentForms(): List<Double>

    /** Finished, real (non-simulated) rides on one route, oldest first: the raw material for its stats page. */
    @Query("SELECT * FROM rides WHERE routeId = :routeId AND endedAtMs IS NOT NULL AND simulated = 0 ORDER BY startedAtMs")
    fun ridesForRoute(routeId: Long): Flow<List<RideEntity>>

    /** Training load per ride since [sinceMs], oldest first: what the fitness and fatigue model runs on. */
    @Query(
        "SELECT startedAtMs, loadTss FROM rides " +
            "WHERE loadTss IS NOT NULL AND simulated = 0 AND startedAtMs >= :sinceMs ORDER BY startedAtMs"
    )
    suspend fun loadsSince(sinceMs: Long): List<RideLoad>

    /** What each segment of this ride actually cost, against what was predicted when riding it. */
    @Query("SELECT * FROM segment_traversals WHERE rideId = :rideId ORDER BY segIdx")
    suspend fun traversals(rideId: Long): List<SegmentTraversalEntity>

    /** When recent rides on a route started, for guessing which route you're on at this hour. */
    @Query(
        "SELECT routeId, startedAtMs FROM rides " +
            "WHERE routeId IS NOT NULL AND simulated = 0 AND endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT 200"
    )
    suspend fun recentRouteStarts(): List<RouteStart>

    /** Rides recorded before the fitness model existed, so their load can be filled in once. */
    @Query("SELECT * FROM rides WHERE loadTss IS NULL AND endedAtMs IS NOT NULL AND simulated = 0")
    suspend fun ridesWithoutLoad(): List<RideEntity>

    @Update
    suspend fun updateRides(rides: List<RideEntity>)

    /** Routes with passes from before rides recorded what they taught, so it can be filled in once. */
    @Query("SELECT DISTINCT routeId FROM segment_traversals WHERE expectedMovingMs IS NULL")
    suspend fun routesWithUnannotatedPasses(): List<Long>

    /** Every pass over one route, in the order they were ridden. */
    @Query("SELECT * FROM segment_traversals WHERE routeId = :routeId ORDER BY enterMs")
    suspend fun traversalsForRoute(routeId: Long): List<SegmentTraversalEntity>

    /** The rides with passes over one route; by pass rather than by ride, since auto-select can switch route. */
    @Query("SELECT * FROM rides WHERE id IN (SELECT DISTINCT rideId FROM segment_traversals WHERE routeId = :routeId)")
    suspend fun ridesOnRoute(routeId: Long): List<RideEntity>

    @Update
    suspend fun updateTraversals(traversals: List<SegmentTraversalEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHr(samples: List<HrSampleEntity>)

    /** Heart rate older than [beforeMs] goes: it is only kept for a couple of weeks. */
    @Query("DELETE FROM hr_samples WHERE timeMs < :beforeMs")
    suspend fun deleteHrBefore(beforeMs: Long)

    /** Real rides since [sinceMs] without heart rate stored yet, to read from Health Connect. */
    @Query(
        "SELECT * FROM rides WHERE simulated = 0 AND endedAtMs IS NOT NULL AND startedAtMs >= :sinceMs " +
            "AND id NOT IN (SELECT DISTINCT rideId FROM hr_samples) ORDER BY startedAtMs"
    )
    suspend fun recentRidesWithoutHr(sinceMs: Long): List<RideEntity>

    /** Every real, finished ride, oldest first: what a route's speed profile is learned from. */
    @Query("SELECT * FROM rides WHERE simulated = 0 AND endedAtMs IS NOT NULL ORDER BY startedAtMs")
    suspend fun realRidesOldestFirst(): List<RideEntity>

    /** Real, finished rides on a route that started after [afterMs], oldest first: not yet folded into its line. */
    @Query(
        "SELECT * FROM rides WHERE routeId = :routeId AND simulated = 0 AND endedAtMs IS NOT NULL " +
            "AND startedAtMs > :afterMs ORDER BY startedAtMs"
    )
    suspend fun ridesOnRouteAfter(routeId: Long, afterMs: Long): List<RideEntity>

    /** The two real rides on a route before [beforeMs], newest first: what a detour is checked against. */
    @Query(
        "SELECT * FROM rides WHERE routeId = :routeId AND simulated = 0 AND endedAtMs IS NOT NULL " +
            "AND startedAtMs < :beforeMs ORDER BY startedAtMs DESC LIMIT 2"
    )
    suspend fun twoRidesOnRouteBefore(routeId: Long, beforeMs: Long): List<RideEntity>

    @Query("UPDATE rides SET rerouteStartM = :startM, rerouteEndM = :endM WHERE id = :rideId")
    suspend fun setReroute(rideId: Long, startM: Double, endM: Double)

    /** Written a moment after the ride finishes, while its page may already be open. */
    @Query("SELECT rerouteStartM, rerouteEndM FROM rides WHERE id = :rideId")
    fun rerouteFlow(rideId: Long): Flow<RideReroute?>
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY name")
    fun routes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes ORDER BY name")
    suspend fun allRoutes(): List<RouteEntity>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun route(id: Long): RouteEntity?

    @Query("SELECT * FROM routes WHERE id = :id")
    fun routeFlow(id: Long): Flow<RouteEntity?>

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY idx")
    suspend fun points(routeId: Long): List<RoutePointEntity>

    @Query("SELECT * FROM route_segments WHERE routeId = :routeId ORDER BY idx")
    suspend fun segments(routeId: Long): List<RouteSegmentEntity>

    @Query("SELECT * FROM route_segments WHERE routeId = :routeId ORDER BY idx")
    fun segmentsFlow(routeId: Long): Flow<List<RouteSegmentEntity>>

    /** Every traversal of every segment of this route, across all rides: the raw material for the slowest-segments table. */
    @Query("SELECT * FROM segment_traversals WHERE routeId = :routeId")
    fun traversalsFlow(routeId: Long): Flow<List<SegmentTraversalEntity>>

    @Insert
    suspend fun insertRoute(route: RouteEntity): Long

    @Insert
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Insert
    suspend fun insertSegments(segments: List<RouteSegmentEntity>)

    /*
     * A segment's row has two writers that can overlap in time: the map lookup (which can take
     * minutes) and the end of a ride. Each writes only its own columns, so neither can put back a
     * stale copy of what the other just stored.
     */

    @Query(
        "UPDATE route_segments SET learnedLogMean = :logMean, learnedLogVar = :logVar, learnedPasses = :passes " +
            "WHERE routeId = :routeId AND idx = :idx"
    )
    suspend fun setLearned(routeId: Long, idx: Int, logMean: Double, logVar: Double, passes: Int)

    @Transaction
    suspend fun setLearned(segments: List<RouteSegmentEntity>) {
        segments.forEach { setLearned(it.routeId, it.idx, it.learnedLogMean, it.learnedLogVar, it.learnedPasses) }
    }

    @Query(
        "UPDATE route_segments SET gradePct = :gradePct, buildings = :buildings, exposure = :exposure, signals = :signals " +
            "WHERE routeId = :routeId AND idx = :idx"
    )
    suspend fun setMapData(routeId: Long, idx: Int, gradePct: Double?, buildings: Int?, exposure: Double?, signals: Int?)

    @Transaction
    suspend fun setMapData(segments: List<RouteSegmentEntity>) {
        segments.forEach { setMapData(it.routeId, it.idx, it.gradePct, it.buildings, it.exposure, it.signals) }
    }

    @Update
    suspend fun updateRoute(route: RouteEntity)

    /** Only the name: a copy of the whole row from the screen could put back a line's old length. */
    @Query("UPDATE routes SET name = :name WHERE id = :routeId")
    suspend fun rename(routeId: Long, name: String)

    @Query("UPDATE routes SET enrichState = :state, enrichError = :error, enrichStartedAtMs = :startedAtMs WHERE id = :id")
    suspend fun setEnrichState(id: Long, state: String, error: String?, startedAtMs: Long? = null)

    /** Routes whose lookup never finished, e.g. because Android stopped the app while it ran. */
    @Query("SELECT * FROM routes WHERE enrichState IN ('pending', 'running')")
    suspend fun unenriched(): List<RouteEntity>

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM route_points WHERE routeId = :routeId")
    suspend fun deletePoints(routeId: Long)

    @Query("DELETE FROM route_segments WHERE routeId = :routeId")
    suspend fun deleteSegments(routeId: Long)

    /**
     * Segment times are keyed by segment index, so they only mean anything for the line they were
     * ridden on. Changing a route's line makes them point at other stretches of road.
     */
    @Query("DELETE FROM segment_traversals WHERE routeId = :routeId")
    suspend fun deleteTraversals(routeId: Long)

    @Query("SELECT * FROM route_profile WHERE routeId = :routeId")
    suspend fun profile(routeId: Long): List<RouteProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(bins: List<RouteProfileEntity>)

    /** Like segment times, a profile only means something for the line it was ridden on. */
    @Query("DELETE FROM route_profile WHERE routeId = :routeId")
    suspend fun deleteProfile(routeId: Long)

    /** Ridden routes that have no speed profile yet, so one can be learned from the rides already stored. */
    @Query(
        "SELECT DISTINCT routeId FROM segment_traversals " +
            "WHERE routeId IN (SELECT id FROM routes) AND routeId NOT IN (SELECT DISTINCT routeId FROM route_profile)"
    )
    suspend fun routesWithoutProfile(): List<Long>

    @Transaction
    suspend fun insertFull(route: RouteEntity, points: List<RoutePointEntity>, segments: List<RouteSegmentEntity>): Long {
        val id = insertRoute(route)
        insertPoints(points.map { it.copy(routeId = id) })
        insertSegments(segments.map { it.copy(routeId = id) })
        return id
    }

    /** A route's line, segments and speed profile, read together so a line being relearned can't come between them. */
    @Transaction
    suspend fun full(routeId: Long): FullRoute? {
        val route = route(routeId) ?: return null
        return FullRoute(route, points(routeId), segments(routeId), profile(routeId))
    }

    @Query("UPDATE routes SET lineFoldedThroughMs = :foldedThroughMs WHERE id = :routeId")
    suspend fun setLineFolded(routeId: Long, foldedThroughMs: Long)

    @Query("UPDATE routes SET lengthM = :lengthM, lineFoldedThroughMs = :foldedThroughMs WHERE id = :routeId")
    suspend fun setLine(routeId: Long, lengthM: Double, foldedThroughMs: Long)

    @Query("DELETE FROM segment_traversals WHERE routeId = :routeId AND segIdx NOT IN (:kept)")
    suspend fun deleteTraversalsExcept(routeId: Long, kept: List<Int>)

    @Query("UPDATE segment_traversals SET segIdx = :to WHERE routeId = :routeId AND segIdx = :from")
    suspend fun moveTraversals(routeId: Long, from: Int, to: Int)

    @Query("UPDATE segment_traversals SET segIdx = -segIdx - 1 WHERE routeId = :routeId AND segIdx < 0")
    suspend fun unflipTraversals(routeId: Long)

    /**
     * Gives a route a line learned from its rides, keeping what it learned on the old one.
     * [rebuild] turns the segments and speed profile as stored into those of the new line. They are
     * read inside this transaction, so what a ride finishing meanwhile learned isn't overwritten.
     * [newIdx] says which segment each old one became; passes over segments that are gone go with them.
     */
    @Transaction
    suspend fun relearnGeometry(
        routeId: Long,
        lengthM: Double,
        foldedThroughMs: Long,
        points: List<RoutePointEntity>,
        newIdx: Map<Int, Int>,
        rebuild: (List<RouteSegmentEntity>, List<RouteProfileEntity>) -> Pair<List<RouteSegmentEntity>, List<RouteProfileEntity>>,
    ) {
        val (segments, profile) = rebuild(segments(routeId), profile(routeId))
        deletePoints(routeId)
        insertPoints(points)
        deleteSegments(routeId)
        insertSegments(segments)
        deleteProfile(routeId)
        saveProfile(profile)
        deleteTraversalsExcept(routeId, newIdx.keys.toList())
        val moves = newIdx.filter { (from, to) -> from != to }
        // By way of negative numbers, so two passes of one ride never share a segment number on the way.
        moves.forEach { (from, to) -> moveTraversals(routeId, from, -to - 1) }
        if (moves.isNotEmpty()) unflipTraversals(routeId)
        setLine(routeId, lengthM, foldedThroughMs)
    }

    /** Gives a route a new line, keeping its id so the rides ridden on it stay attached. */
    @Transaction
    suspend fun replaceGeometry(route: RouteEntity, points: List<RoutePointEntity>, segments: List<RouteSegmentEntity>) {
        deletePoints(route.id)
        deleteSegments(route.id)
        deleteTraversals(route.id)
        deleteProfile(route.id)
        updateRoute(route)
        insertPoints(points.map { it.copy(routeId = route.id) })
        insertSegments(segments.map { it.copy(routeId = route.id) })
    }
}

@Database(
    entities = [
        RideEntity::class, TrackPointEntity::class, RouteEntity::class, RoutePointEntity::class,
        RouteSegmentEntity::class, SegmentTraversalEntity::class, RouteProfileEntity::class, HrSampleEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class TegenwindDb : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun routes(): RouteDao
}
