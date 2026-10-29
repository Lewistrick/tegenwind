package com.tegenwind.app.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RideLinesTest {
    private val line = listOf(GeoPoint(52.0907, 5.1214), GeoPoint(52.0912, 5.1230), GeoPoint(52.0931, 5.1251))

    @Test
    fun anEncodedLineComesBackToATenthOfAMetre() {
        val back = decodePolyline(encodePolyline(line))
        assertEquals(line.size, back.size)
        line.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
    }

    @Test
    fun theWholeWorldAndNoLineAtAllSurviveToo() {
        val far = listOf(GeoPoint(-33.8688, 151.2093), GeoPoint(64.1466, -21.9426), GeoPoint(0.0, 0.0), GeoPoint(-85.0, -180.0))
        val back = decodePolyline(encodePolyline(far))
        far.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
        assertEquals(emptyList<GeoPoint>(), decodePolyline(encodePolyline(emptyList())))
    }

    @Test
    fun anEncodedLineIsShortAndOnOneLine() {
        // A route's line, a point every 10 m or so for 5 km: a few kilobytes.
        val long = (0 until 500).map { GeoPoint(52.0 + it * 0.00009, 5.0 + it * 0.00002) }
        val text = encodePolyline(long)
        assertTrue(text.length < 4 * 1024)
        assertFalse(text.contains('\n'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun textThatIsNoLineIsRefused() {
        decodePolyline("not a line\u0001")
    }

    @Test
    fun aRideKeepsItsLinesUntilItIsDeleted() {
        val dir = Files.createTempDirectory("ride-lines").toFile()
        val store = RideLineStore(File(dir, "lines"))
        val planned = Polyline(line)
        val after = Polyline(line.map { GeoPoint(it.lat + 0.00003, it.lon) })
        assertNull(store.load(7))
        store.save(7, planned, after)
        val back = store.load(7)
        assertNotNull(back)
        assertTrue(sameLine(back!!.planned, planned))
        assertTrue(sameLine(back.after, after))
        assertFalse(back.unchanged)
        store.forget(7)
        assertNull(store.load(7))
        dir.deleteRecursively()
    }

    @Test
    fun aRideThatDidntMoveTheLineSaysSo() {
        val dir = Files.createTempDirectory("ride-lines").toFile()
        val store = RideLineStore(dir)
        store.save(3, Polyline(line), Polyline(line))
        assertTrue(store.load(3)!!.unchanged)
        dir.deleteRecursively()
    }

    @Test
    fun aDamagedFileIsNoLinesAtAll() {
        val dir = Files.createTempDirectory("ride-lines").toFile()
        File(dir, "5.txt").writeText("only one line\n")
        assertNull(RideLineStore(dir).load(5))
        dir.deleteRecursively()
    }

    @Test
    fun linesThreeMetresApartAreNotTheSame() {
        val moved = Polyline(line.map { GeoPoint(it.lat + 0.00003, it.lon) })
        assertFalse(sameLine(Polyline(line), moved))
        assertFalse(sameLine(Polyline(line), Polyline(line.take(2))))
        assertTrue(sameLine(Polyline(line), Polyline(line.map { GeoPoint(it.lat + 1e-7, it.lon - 1e-7) })))
    }
}
