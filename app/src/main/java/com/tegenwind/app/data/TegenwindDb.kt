package com.tegenwind.app.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
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
)

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
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY name")
    fun routes(): Flow<List<RouteEntity>>

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

    @Insert
    suspend fun insertRoute(route: RouteEntity): Long

    @Insert
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Insert
    suspend fun insertSegments(segments: List<RouteSegmentEntity>)

    @Update
    suspend fun updateSegments(segments: List<RouteSegmentEntity>)

    @Update
    suspend fun updateRoute(route: RouteEntity)

    @Query("UPDATE routes SET enrichState = :state, enrichError = :error, enrichStartedAtMs = :startedAtMs WHERE id = :id")
    suspend fun setEnrichState(id: Long, state: String, error: String?, startedAtMs: Long? = null)

    /** Routes whose lookup never finished, e.g. because Android stopped the app while it ran. */
    @Query("SELECT * FROM routes WHERE enrichState IN ('pending', 'running')")
    suspend fun unenriched(): List<RouteEntity>

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun insertFull(route: RouteEntity, points: List<RoutePointEntity>, segments: List<RouteSegmentEntity>): Long {
        val id = insertRoute(route)
        insertPoints(points.map { it.copy(routeId = id) })
        insertSegments(segments.map { it.copy(routeId = id) })
        return id
    }
}

@Database(
    entities = [
        RideEntity::class, TrackPointEntity::class, RouteEntity::class, RoutePointEntity::class,
        RouteSegmentEntity::class, SegmentTraversalEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class TegenwindDb : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun routes(): RouteDao
}
