package com.adrexample.blescannerdemo_cdm.ble

import android.content.Context

/**
 * Persists the CompanionDeviceManager association id and whether presence observation was
 * started for it, so the app can resume observing an existing association on the next launch
 * instead of re-running the device picker.
 */
object AssociationPrefs {
    private const val PREFS_NAME = "ble_association_prefs"
    private const val KEY_ASSOCIATION_ID = "association_id"
    private const val KEY_OBSERVING = "observing"

    fun setAssociationId(context: Context, id: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ASSOCIATION_ID, id)
            .apply()
    }

    fun getAssociationId(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ASSOCIATION_ID)) return null
        return prefs.getInt(KEY_ASSOCIATION_ID, -1).takeIf { it >= 0 }
    }

    fun setObserving(context: Context, observing: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OBSERVING, observing)
            .apply()
    }

    fun isObserving(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OBSERVING, false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
