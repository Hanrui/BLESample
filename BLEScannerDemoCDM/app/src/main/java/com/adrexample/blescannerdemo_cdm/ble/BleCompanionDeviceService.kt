package com.adrexample.blescannerdemo_cdm.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

class BleCompanionDeviceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "BleCompanionDeviceSvc"
    }

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> onDeviceAppeared(event.associationId)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> onDeviceDisappeared(event.associationId)
        }
    }

    private fun onDeviceAppeared(associationId: Int) {
        BleRepository.updateStatus(BleStatus.DEVICE_APPEARED)

        val macAddress = findAssociationInfo(associationId)?.deviceMacAddress
            ?.toString()?.uppercase(Locale.US)
        if (macAddress == null) {
            Log.e(TAG, "No mac address for association $associationId")
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }
        if (!hasConnectPermission()) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }
        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device == null) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }

        GattTimestampReader.connectAndRead(this, associationId, device)
    }

    private fun onDeviceDisappeared(associationId: Int) {
        BleRepository.updateStatus(BleStatus.DISCONNECTED)
        GattTimestampReader.disconnect(this, associationId)
    }

    private fun findAssociationInfo(associationId: Int) =
        getSystemService(CompanionDeviceManager::class.java)
            ?.myAssociations
            ?.find { it.id == associationId }

    private fun hasConnectPermission() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
}
