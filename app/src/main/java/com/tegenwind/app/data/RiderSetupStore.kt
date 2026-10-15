package com.tegenwind.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps [RiderSetup] on the phone. A handful of numbers that change once in a while, so a small
 * preference file rather than a table. Before anything was saved, it's the first setup.
 */
class RiderSetupStore(context: Context) {
    private val prefs = context.getSharedPreferences("rider_setup", Context.MODE_PRIVATE)

    private val _setup = MutableStateFlow(load())
    val setup: StateFlow<RiderSetup> = _setup.asStateFlow()

    /** Changes the setup, keeps it within what a rider and a bike can be, and saves it. */
    fun update(change: (RiderSetup) -> RiderSetup) {
        val next = change(_setup.value).valid()
        _setup.value = next
        prefs.edit().putString(KEY, next.toJson()).apply()
    }

    private fun load(): RiderSetup =
        prefs.getString(KEY, null)?.let { runCatching { RiderSetup.fromJson(it) }.getOrNull() } ?: RiderSetup()

    private companion object {
        const val KEY = "setup"
    }
}
