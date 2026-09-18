package com.adrexample.blescannerdemo_cdm.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BleCompanionDeviceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "BleCompanionDeviceSvc"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    private val activeGatts = mutableMapOf<Int, BluetoothGatt>()
    private val connectTimeoutJobs = mutableMapOf<Int, Job>()

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

        BleRepository.updateStatus(BleStatus.CONNECTING)
        connectTimeoutJobs[associationId] = serviceScope.launch {
            delay(BleConstants.CONNECT_TIMEOUT_MS)
            BleRepository.updateStatus(BleStatus.ERROR)
            closeGatt(associationId)
        }
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(this, false, gattCallback(associationId), BluetoothDevice.TRANSPORT_LE)
        activeGatts[associationId] = gatt
    }

    private fun onDeviceDisappeared(associationId: Int) {
        BleRepository.updateStatus(BleStatus.DISCONNECTED)
        closeGatt(associationId)
    }

    private fun findAssociationInfo(associationId: Int) =
        getSystemService(CompanionDeviceManager::class.java)
            ?.myAssociations
            ?.find { it.id == associationId }

    private fun gattCallback(associationId: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJobs.remove(associationId)?.cancel()
                    if (!hasConnectPermission()) return
                    BleRepository.updateStatus(BleStatus.DISCOVERING_SERVICES)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeoutJobs.remove(associationId)?.cancel()
                    BleRepository.updateStatus(BleStatus.DISCONNECTED)
                    closeGatt(associationId)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BleRepository.updateStatus(BleStatus.ERROR)
                disconnect(associationId)
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)
            if (characteristic == null) {
                BleRepository.updateStatus(BleStatus.ERROR)
                disconnect(associationId)
                return
            }
            if (!hasConnectPermission()) return
            BleRepository.updateStatus(BleStatus.READING_CHARACTERISTIC)
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val timestampText = parseTimestamp(value)
                BleRepository.addLog(
                    deviceName = if (hasConnectPermission()) gatt.device.name else null,
                    deviceAddress = gatt.device.address,
                    timestampText = timestampText
                )
                BleRepository.updateStatus(BleStatus.READ_SUCCESS)
            } else {
                BleRepository.updateStatus(BleStatus.ERROR)
            }
            disconnect(associationId)
        }
    }

    private fun disconnect(associationId: Int) {
        if (hasConnectPermission()) {
            activeGatts[associationId]?.disconnect()
        } else {
            closeGatt(associationId)
        }
    }

    private fun closeGatt(associationId: Int) {
        connectTimeoutJobs.remove(associationId)?.cancel()
        val gatt = activeGatts.remove(associationId) ?: return
        if (hasConnectPermission()) {
            gatt.close()
        }
    }

    private fun hasConnectPermission() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    // The peripheral's exact encoding isn't specified, so this covers the common cases:
    // an 8-byte epoch-millis long, a 4-byte epoch-seconds int, or a UTF-8 string (numeric or plain text).
    private fun parseTimestamp(value: ByteArray): String = when (value.size) {
        8 -> formatEpochMillis(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).long)
        4 -> formatEpochMillis(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int * 1000L)
        else -> {
            val text = String(value, Charsets.UTF_8).trim()
            text.toLongOrNull()?.let { formatEpochMillis(it) } ?: text.ifEmpty { "<empty>" }
        }
    }

    private fun formatEpochMillis(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
}
