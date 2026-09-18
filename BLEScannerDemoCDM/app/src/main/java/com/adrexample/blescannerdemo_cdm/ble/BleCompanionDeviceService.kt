package com.adrexample.blescannerdemo_cdm.ble

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Bound by the system whenever the CompanionDeviceManager detects that our associated
 * peripheral has come into or gone out of BLE range (via startObservingDevicePresence).
 * The system grants a temporary background-execution allowance for this call, which is
 * enough to kick off [BleConnectService] — the CDM permissions REQUEST_COMPANION_RUN_IN_BACKGROUND
 * and REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND allow that even while
 * the app has no foreground activity.
 */
class BleCompanionDeviceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "BleCompanionDeviceService"
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        // MacAddress.toString() is lowercase, but BluetoothAdapter.getRemoteDevice() (used in
        // BleConnectService) only accepts uppercase hex — normalize it here.
        val address = associationInfo.deviceMacAddress?.toString()?.uppercase()
        Log.d(TAG, "onDeviceAppeared: $address")
        if (address == null) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }
        val intent = Intent(this, BleConnectService::class.java).apply {
            putExtra(BleConstants.EXTRA_DEVICE_ADDRESS, address)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        Log.d(TAG, "onDeviceDisappeared: ${associationInfo.deviceMacAddress}")
        BleRepository.updateStatus(BleStatus.WAITING_FOR_DEVICE)
    }
}
