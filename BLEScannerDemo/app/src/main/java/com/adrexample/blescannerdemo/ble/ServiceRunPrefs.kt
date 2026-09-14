package com.adrexample.blescannerdemo.ble

import android.content.Context

/**
 * Persists whether the user wants [BleForegroundService] running, independent of the service's
 * own process lifetime. A plain in-memory flag can't distinguish "the OS silently respawned the
 * service after a low-memory kill" (should resume) from "the user stopped it, then the process
 * happened to die anyway" (should stay stopped) — SharedPreferences survives that process death.
 */
object ServiceRunPrefs {
    private const val PREFS_NAME = "ble_service_prefs"
    private const val KEY_SHOULD_RUN = "should_run"

    fun setShouldRun(context: Context, shouldRun: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOULD_RUN, shouldRun)
            .apply()
    }

    fun shouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_RUN, false)
}
