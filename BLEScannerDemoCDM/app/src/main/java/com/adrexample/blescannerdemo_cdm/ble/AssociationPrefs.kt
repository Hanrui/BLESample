package com.adrexample.blescannerdemo_cdm.ble

import android.content.Context

/**
 * Persists the CompanionDeviceManager association id across process restarts. The association
 * itself is durable (survives app/process death), but [android.companion.CompanionDeviceManager]
 * presence observation must be re-armed with [android.companion.CompanionDeviceManager.startObservingDevicePresence]
 * every time the process starts, so the id needs to outlive this app's in-memory state.
 */
object AssociationPrefs {
    private const val PREFS_NAME = "ble_association_prefs"
    private const val KEY_ASSOCIATION_ID = "association_id"
    private const val NO_ASSOCIATION = -1

    fun setAssociationId(context: Context, associationId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ASSOCIATION_ID, associationId)
            .apply()
    }

    fun getAssociationId(context: Context): Int? {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ASSOCIATION_ID, NO_ASSOCIATION)
        return id.takeIf { it != NO_ASSOCIATION }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ASSOCIATION_ID)
            .apply()
    }
}
