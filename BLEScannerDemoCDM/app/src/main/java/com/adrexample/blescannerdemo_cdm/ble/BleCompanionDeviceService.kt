package com.adrexample.blescannerdemo_cdm.ble

import android.Manifest
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

/**
 * Bound by the system whenever a device matching an associated presence-observation request
 * comes in or out of BLE range. Replaces the old always-on foreground scan loop: the platform
 * now does the scanning and just wakes this service up on appearance/disappearance.
 */
class BleCompanionDeviceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "BleCompanionDeviceSvc"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectTimeoutJob: Job? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Bonding is owned by MainActivity (triggered right after association) — by the time this
    // event fires, the peripheral should already be bonded, so this just connects directly.
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Log.d(TAG, "onDevicePresenceEvent: associationId=${event.associationId}, event=${event.event}")
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                val device = remoteDeviceForAssociation(event.associationId)
                if (device == null) {
                    BleRepository.updateStatus(BleStatus.ERROR, "Could not resolve companion device")
                    return
                }
                connectToDevice(device)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                connectTimeoutJob?.cancel()
                BleRepository.updateStatus(BleStatus.DISCONNECTED, "Device out of range")
                closeGatt()
            }
            DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> {
                connectTimeoutJob?.cancel()
                closeGatt()
                BleRepository.setObserving(false)
                BleRepository.updateStatus(BleStatus.IDLE)
            }
        }
    }

    private fun remoteDeviceForAssociation(associationId: Int): BluetoothDevice? {
        val macAddress = getSystemService(CompanionDeviceManager::class.java)
            ?.myAssociations
            ?.firstOrNull { it.id == associationId }
            ?.deviceMacAddress
            ?: return null
        // MacAddress.toString() is lowercase; normalize to uppercase to match the hex format
        // BluetoothAdapter.getRemoteDevice() expects.
        val address = macAddress.toString().uppercase()
        return getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission() = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    // The stable connectGatt(Context, Boolean, Callback, transport) overload is deprecated in
    // favor of a BluetoothGattConnectionSettings API that sits behind a feature flag not
    // guaranteed to be enabled on all API 36 devices, so the stable overload is used deliberately.
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            BleRepository.updateStatus(BleStatus.ERROR, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        BleRepository.updateStatus(BleStatus.CONNECTING, "Connecting to ${device.address}")
        connectTimeoutJob = serviceScope.launch {
            delay(BleConstants.CONNECT_TIMEOUT_MS)
            BleRepository.updateStatus(BleStatus.ERROR, "Connection timed out")
            closeGatt()
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    if (!hasConnectPermission()) return
                    BleRepository.updateStatus(BleStatus.DISCOVERING_SERVICES, "Discovering services on ${gatt.device.address}")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeoutJob?.cancel()
                    BleRepository.updateStatus(BleStatus.DISCONNECTED, "Disconnected from ${gatt.device.address}")
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BleRepository.updateStatus(BleStatus.ERROR, "Service discovery failed: status $status")
                disconnectGatt()
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)

            if (characteristic == null) {
                BleRepository.updateStatus(BleStatus.ERROR, "Target characteristic not found")
                disconnectGatt()
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
                BleRepository.updateStatus(BleStatus.READ_SUCCESS, "Read: $timestampText")
            } else {
                BleRepository.updateStatus(BleStatus.ERROR, "Characteristic read failed: status $status")
            }
            disconnectGatt()
        }
    }

    private fun disconnectGatt() {
        if (hasConnectPermission()) {
            bluetoothGatt?.disconnect()
        } else {
            closeGatt()
        }
    }

    private fun closeGatt() {
        if (hasConnectPermission()) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
    }

    // ---- Timestamp decoding ----

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
