package com.tegenwind.app.routes

import java.io.File
import kotlin.math.abs

/**
 * A route's line around one ride: as it was when the ride set off ([planned]) and as it became once
 * it had learned from the ride ([after], see [RouteDrift]).
 */
class RideLines(val planned: Polyline, val after: Polyline) {
    /** True when this ride didn't move the line at all. */
    val unchanged: Boolean get() = sameLine(planned, after)
}

/** Whether [a] and [b] are the same line, point for point, to about 0.2 m (what storing keeps). */
fun sameLine(a: Polyline, b: Polyline): Boolean =
    a.points.size == b.points.size && a.points.indices.all { i ->
        abs(a.points[i].lat - b.points[i].lat) <= SAME_DEG && abs(a.points[i].lon - b.points[i].lon) <= SAME_DEG
    }

private const val SAME_DEG = 2e-6

/**
 * Keeps each ride's [RideLines], so its map can show what was planned and what the next ride is
 * shown. One small file per ride in [dir], kept out of the database so it never needs a migration.
 * Every call does file work: call it off the main thread. Nothing here throws: a line that can't be
 * kept or read is simply missing, and the map shows the route as it is now instead.
 */
class RideLineStore(private val dir: File) {
    fun save(rideId: Long, planned: Polyline, after: Polyline) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$rideId.tmp")
            tmp.writeText(encodePolyline(planned.points) + "\n" + encodePolyline(after.points) + "\n")
            val file = fileOf(rideId)
            // Written whole and then swapped in, so a crash halfway never leaves half a line.
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Not kept: the ride's map falls back to the route as it is now.
        }
    }

    fun load(rideId: Long): RideLines? = try {
        val file = fileOf(rideId)
        if (!file.exists()) null else {
            val lines = file.readLines()
            val planned = decodePolyline(lines[0])
            val after = decodePolyline(lines[1])
            if (planned.size < 2 || after.size < 2) null else RideLines(Polyline(planned), Polyline(after))
        }
    } catch (e: Exception) {
        null
    }

    fun forget(rideId: Long) {
        try {
            fileOf(rideId).delete()
        } catch (e: Exception) {
            // A file left behind takes a few kilobytes and is never read again.
        }
    }

    private fun fileOf(rideId: Long) = File(dir, "$rideId.txt")
}
