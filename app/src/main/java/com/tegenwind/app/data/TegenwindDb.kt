package com.tegenwind.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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

@Database(entities = [RideEntity::class, TrackPointEntity::class], version = 1, exportSchema = false)
abstract class TegenwindDb : RoomDatabase() {
    abstract fun rides(): RideDao
}
