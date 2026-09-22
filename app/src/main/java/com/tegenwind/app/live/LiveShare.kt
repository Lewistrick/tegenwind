package com.tegenwind.app.live

import android.util.Log
import com.tegenwind.app.BuildConfig
import com.tegenwind.app.ride.LiveRide
import com.tegenwind.app.routes.LoadedRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Sends the ride to the meewind server while it's happening, so whoever holds the link can watch.
 *
 * Nothing is queued: a tick that doesn't arrive is dropped, because a late position is worse than a
 * missing one. The server forgets rides (and can be restarted), so it answers 409 when it doesn't
 * have the route a tick belongs to, and the route is simply sent again.
 */
class LiveShare(
    private val baseUrl: String = BuildConfig.LIVE_BASE_URL.trimEnd('/'),
    private val key: String = BuildConfig.LIVE_KEY,
) {
    private val _link = MutableStateFlow<String?>(null)

    /** The link to send someone, while a ride is being shared. */
    val link: StateFlow<String?> = _link.asStateFlow()

    private var token: String? = null
    private var routeVersion = 0
    private var sentVersion = -1
    private var lastRouteId: Long? = NOT_SET

    val configured: Boolean get() = baseUrl.isNotBlank() && key.isNotBlank()

    /** Starts sharing: a fresh token, so an old link can't follow this ride. */
    fun begin(): String? {
        if (!configured) return null
        token = randomToken()
        routeVersion = 0
        sentVersion = -1
        lastRouteId = NOT_SET
        _link.value = "$baseUrl?ridetoken=${token}"
        return _link.value
    }

    /**
     * Sends one tick, re-sending the route first whenever it has changed — which auto-select does
     * mid-ride — or whenever the server says it doesn't have it.
     */
    suspend fun push(ride: LiveRide, route: LoadedRoute?) {
        val token = token ?: return
        if (!ride.started) return
        if (route?.route?.id != lastRouteId) {
            lastRouteId = route?.route?.id
            routeVersion++
        }
        try {
            if (sentVersion != routeVersion) sendRoute(token, ride.simulated, route)
            val tick = tickJson(routeVersion, ride, System.currentTimeMillis())
            if (post(token, "state", tick) == NEEDS_ROUTE) {
                sendRoute(token, ride.simulated, route)
                post(token, "state", tick)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Tegenwind", "Live share tick failed", e)
        }
    }

    /** One last tick saying you've arrived, then the ride is let go. */
    suspend fun finish(last: LiveRide?, route: LoadedRoute?) {
        val token = token ?: return
        try {
            if (last != null && last.started) {
                if (sentVersion != routeVersion) sendRoute(token, last.simulated, route)
                post(token, "state", tickJson(routeVersion, last, System.currentTimeMillis()).markFinished())
            }
            post(token, "end", JSONObject())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Tegenwind", "Live share couldn't say goodbye", e)
        } finally {
            this.token = null
            _link.value = null
        }
    }

    private suspend fun sendRoute(token: String, simulated: Boolean, route: LoadedRoute?) {
        if (post(token, "route", manifestJson(routeVersion, simulated, route)) == OK) {
            sentVersion = routeVersion
        }
    }

    /**
     * One attempt plus two quick retries. The map-data fetchers back off for tens of seconds, which
     * is right for them and useless here: by then the next tick has already come round.
     */
    private suspend fun post(token: String, path: String, body: JSONObject): Int = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val status = try {
                send(token, path, body.toString())
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS - 1) throw e
                -1
            }
            if (status == OK || status == NEEDS_ROUTE || status in 400..499) return@withContext status
            if (++attempt >= MAX_ATTEMPTS) return@withContext status
            delay(RETRY_MS[attempt - 1])
        }
        @Suppress("UNREACHABLE_CODE") OK
    }

    private fun send(token: String, path: String, body: String): Int {
        val conn = (URL("$baseUrl/api/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Tegenwind-Key", key)
            setRequestProperty("X-Ride-Token", token)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun randomToken(): String {
        val random = SecureRandom()
        return (1..TOKEN_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }

    private companion object {
        const val OK = 200
        const val NEEDS_ROUTE = 409
        const val MAX_ATTEMPTS = 3
        val RETRY_MS = longArrayOf(400, 1_000)
        const val TOKEN_LENGTH = 16
        /** No vowels, so a token can't accidentally spell anything, and no look-alike characters. */
        const val ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789"
        const val USER_AGENT = "Tegenwind/1.0 (personal cycling app; github.com/Lewistrick/tegenwind)"
        /** Distinct from null, which is a real value: a free ride with no route at all. */
        val NOT_SET = Long.MIN_VALUE
    }
}
