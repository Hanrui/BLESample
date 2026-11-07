package com.adrexample.blescannerdemo_cdm.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
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
 * Owns the single active GATT connection to the companion peripheral. Connections are opened
 * when the device is reported nearby (by [BleCompanionDeviceService] or right after pairing) and
 * closed once the timestamp characteristic has been read or the device disappears.
 */
object BleGattClientManager {
    private const val TAG = "BleGattClientManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectTimeoutJob: Job? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // The stable connectGatt(Context, Boolean, Callback, transport) overload is deprecated in
    // favor of a BluetoothGattConnectionSettings API that sits behind a feature flag not
    // guaranteed to be enabled on all API 36 devices, so the stable overload is used deliberately.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            Log.d(TAG, "connect: already have a gatt, ignoring")
            return
        }
        Log.d(TAG, "connect: calling connectGatt() for ${device.address}")
        BleRepository.updateStatus(BleStatus.CONNECTING)
        connectTimeoutJob = scope.launch {
            delay(BleConstants.CONNECT_TIMEOUT_MS)
            Log.w(TAG, "connect timeout fired for ${device.address}")
            BleRepository.updateStatus(BleStatus.ERROR)
            disconnectAndClose()
        }
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnectAndClose() {
        connectTimeoutJob?.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    BleRepository.updateStatus(BleStatus.DISCOVERING_SERVICES)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    BleRepository.updateStatus(BleStatus.DISCONNECTED)
                    disconnectAndClose()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status $status")
                BleRepository.updateStatus(BleStatus.ERROR)
                gatt.disconnect()
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Target characteristic not found")
                BleRepository.updateStatus(BleStatus.ERROR)
                gatt.disconnect()
                return
            }
            BleRepository.updateStatus(BleStatus.READING_CHARACTERISTIC)
            gatt.readCharacteristic(characteristic)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val timestampText = parseTimestamp(value)
                BleRepository.addLog(
                    deviceName = gatt.device.name,
                    deviceAddress = gatt.device.address,
                    timestampText = timestampText
                )
                BleRepository.updateStatus(BleStatus.READ_SUCCESS)
            } else {
                Log.e(TAG, "Characteristic read failed: status $status")
                BleRepository.updateStatus(BleStatus.ERROR)
            }
            gatt.disconnect()
        }
    }

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
