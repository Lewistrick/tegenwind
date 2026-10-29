package com.tegenwind.app.ui

import com.tegenwind.app.routes.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class WebMercatorTest {
    private val dam = GeoPoint(52.3731, 4.8926)

    @Test
    fun aPlaceLandsOnOpenStreetMapsOwnTile() {
        // Dam Square in Amsterdam is on tile 15/16829/10768.
        val w = worldOf(dam)
        assertEquals(16829, (w.x * 2.0.pow(15)).toInt())
        assertEquals(10768, (w.y * 2.0.pow(15)).toInt())
        assertEquals("https://tile.openstreetmap.org/15/16829/10768.png", TileKey(15, 16829, 10768).url)
    }

    @Test
    fun aPlaceComesBackFromTheWorldSquare() {
        val back = geoOf(worldOf(dam))
        assertEquals(dam.lat, back.lat, 1e-9)
        assertEquals(dam.lon, back.lon, 1e-9)
    }

    @Test
    fun theNearestTileLevelIsDrawn() {
        assertEquals(15, tileZoom(15.0))
        assertEquals(15, tileZoom(15.49))
        assertEquals(16, tileZoom(15.5))
        assertEquals(OsmZoom.MAX_TILE, tileZoom(20.0))
        assertEquals(0, tileZoom(-1.0))
    }

    @Test
    fun theTilesCoverTheViewMiddleFirst() {
        // A 1080 × 1800 view with tiles 704 px wide (256 dp at 2.75): a 3 × 4 or so block.
        val tiles = visibleTiles(worldOf(dam), 15.0, 1080f, 1800f, 704f)
        assertTrue(tiles.size in 6..16)
        assertTrue(tiles.all { it.z == 15 })
        assertEquals(TileKey(15, 16829, 10768), tiles.first())
        // Every corner of the view is on one of them.
        val world = worldPx(15.0, 704f)
        for ((dx, dy) in listOf(-540.0 to -900.0, 540.0 to -900.0, -540.0 to 900.0, 540.0 to 900.0)) {
            val x = ((worldOf(dam).x + dx / world) * 2.0.pow(15)).toInt()
            val y = ((worldOf(dam).y + dy / world) * 2.0.pow(15)).toInt()
            assertTrue(tiles.any { it.x == x && it.y == y })
        }
    }

    @Test
    fun noViewNoTiles() {
        assertEquals(emptyList<TileKey>(), visibleTiles(worldOf(dam), 15.0, 0f, 0f, 704f))
    }

    @Test
    fun aRideIsOpenedWithAllOfItInView() {
        val ride = listOf(GeoPoint(52.0907, 5.1214), GeoPoint(52.1100, 5.1500), GeoPoint(52.0950, 5.1800)).map(::worldOf)
        val (center, zoom) = fitView(ride, 1080f, 1500f, 704f, 77f)
        val world = worldPx(zoom, 704f)
        for (p in ride) {
            val x = (p.x - center.x) * world + 540
            val y = (p.y - center.y) * world + 750
            assertTrue(x in 76.0..1004.0 && y in 76.0..1424.0)
        }
        // And not from further away than needed: at one level closer something falls outside.
        val closer = worldPx(zoom + 1, 704f)
        assertTrue(ride.any { p -> kotlin.math.abs((p.x - center.x) * closer) > 540 - 77 || kotlin.math.abs((p.y - center.y) * closer) > 750 - 77 })
    }

    @Test
    fun aSingleSpotOpensCloseInButNotFurtherThanStreetLevel() {
        val (_, zoom) = fitView(listOf(worldOf(dam)), 1080f, 1500f, 704f, 77f)
        assertEquals(17.0, zoom, 0.0)
    }

    private fun apply(m: FloatArray, r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).toDouble().coerceIn(0.0, 255.0)
        return Triple(ch(0), ch(1), ch(2))
    }

    @Test
    fun theDarkMapIsDarkWhereOpenStreetMapIsLightAndKeepsItsColours() {
        val m = darkTileMatrix()
        // Land (#F2EFE9) turns nearly black, white roads too; black text turns light.
        val land = apply(m, 0xF2, 0xEF, 0xE9)
        assertTrue(land.first < 40 && land.second < 40 && land.third < 40)
        val text = apply(m, 0, 0, 0)
        assertTrue(text.first > 200 && text.second > 200 && text.third > 200)
        // Water (#AAD3DF) stays blue, a park (#C8FACC) stays green.
        val water = apply(m, 0xAA, 0xD3, 0xDF)
        assertTrue(water.third > water.first)
        val park = apply(m, 0xC8, 0xFA, 0xCC)
        assertTrue(park.second > park.first && park.second > park.third)
        // Alpha is left alone.
        assertEquals(1f, m[18], 0f)
        assertEquals(0f, m[19], 0f)
    }
}
