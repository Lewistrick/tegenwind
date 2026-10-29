package com.tegenwind.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenStreetMap's map tiles: from memory, else from the phone, else fetched. Its tile servers are
 * free on conditions (operations.osmfoundation.org/policies/tiles), which this keeps to: the app
 * says who it is, a fetched tile is kept on the phone and not fetched again for a week, at most two
 * are fetched at a time, and only tiles still on screen are fetched.
 *
 * Use it from the main thread only: the map asks for tiles while it composes and draws, and every
 * load comes back to the main thread before it touches what is kept here.
 */
class OsmTiles(private val dir: File) {
    private val memory = object : LinkedHashMap<TileKey, ImageBitmap>(MEMORY_TILES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, ImageBitmap>?) = size > MEMORY_TILES
    }
    private val loading = HashSet<TileKey>()
    private var wanted: Set<TileKey> = emptySet()
    private val downloads = Semaphore(MAX_DOWNLOADS)

    /** Goes up by one every time a tile arrives, so a map reading it draws again. */
    var arrived by mutableIntStateOf(0)
        private set

    /** The tile if it is in memory: nothing is loaded for it. */
    operator fun get(key: TileKey): ImageBitmap? = memory[key]

    /**
     * Loads the [keys] that aren't in memory yet, in [scope]: closing the map cancels them. From now
     * on only these are fetched; any others still waiting for their turn are left.
     */
    fun request(keys: List<TileKey>, scope: CoroutineScope) {
        wanted = keys.toSet()
        for (key in keys) {
            if (key in memory || key in loading) continue
            loading += key
            scope.launch {
                try {
                    val image = load(key)
                    if (image != null) {
                        memory[key] = image
                        arrived++
                    }
                } finally {
                    loading -= key
                }
            }
        }
    }

    private suspend fun load(key: TileKey): ImageBitmap? {
        val file = File(dir, "${key.z}/${key.x}/${key.y}.png")
        val (kept, fresh) = withContext(Dispatchers.IO) {
            val image = try {
                if (file.exists()) decode(file.readBytes()) else null
            } catch (e: Exception) {
                null
            }
            image to (image != null && System.currentTimeMillis() - file.lastModified() < KEEP_MS)
        }
        if (fresh || key !in wanted) return kept
        val fetched = downloads.withPermit {
            // Scrolled away while waiting for a turn: don't fetch what isn't shown.
            if (key !in wanted) null else withContext(Dispatchers.IO) { fetch(key, file) }
        }
        // Offline or refused: a week-old tile is still a good map.
        return fetched ?: kept
    }

    private fun fetch(key: TileKey, file: File): ImageBitmap? = try {
        val conn = (URL(key.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null else {
                val bytes = conn.inputStream.use { it.readBytes() }
                decode(bytes)?.also {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        // Tiles have no see-through parts, so two bytes a pixel is enough: half the memory.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        /** About two screens full at two zoom levels; at 128 kB each, 12 MB. */
        const val MEMORY_TILES = 96
        const val MAX_DOWNLOADS = 2
        const val KEEP_MS = 7 * 24 * 3_600_000L
        const val USER_AGENT = "Tegenwind/1.0 (personal cycling app; github.com/Lewistrick/tegenwind)"
    }
}
