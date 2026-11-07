package com.adrexample.blescannerdemo_cdm.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log

private const val TAG = "BleCompanionDeviceSvc"

/**
 * Bound by the system whenever an associated peripheral is nearby and connectable, keeping this
 * process alive without a persistent foreground service.
 */
class BleCompanionDeviceService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        super.onDevicePresenceEvent(event)
        Log.d(TAG, "onDevicePresenceEvent event=${event.event} associationId=${event.associationId}")
        val deviceManager = getSystemService(CompanionDeviceManager::class.java) ?: return
        val association = deviceManager.myAssociations.firstOrNull { it.id == event.associationId }
        if (association == null) {
            Log.w(TAG, "No matching association for id=${event.associationId}")
            return
        }

        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED -> connectToDevice(association)

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                Log.d(TAG, "Presence lost, disconnecting GATT")
                BleGattClientManager.disconnectAndClose()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(associationInfo: AssociationInfo) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.w(TAG, "connectToDevice: no BluetoothAdapter")
            return
        }
        val device = resolveBluetoothDevice(adapter, associationInfo)
        if (device == null) {
            Log.w(TAG, "connectToDevice: resolveBluetoothDevice returned null")
            return
        }
        Log.d(TAG, "connectToDevice: resolved ${device.address}, bondState=${device.bondState}")
        ensureBondedThenConnect(applicationContext, device) {
            Log.d(TAG, "connectToDevice: bonded, opening GATT")
            BleGattClientManager.connect(applicationContext, device)
        }
    }
}
