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

    @Query("UPDATE routes SET enrichState = :state, enrichError = :error WHERE id = :id")
    suspend fun setEnrichState(id: Long, state: String, error: String?)

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
    entities = [RideEntity::class, TrackPointEntity::class, RouteEntity::class, RoutePointEntity::class, RouteSegmentEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class TegenwindDb : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun routes(): RouteDao
}
