package com.adrexample.blescannerdemo_cdm.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
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
 * Connects to a bonded peripheral and reads the timestamp characteristic, keyed by CDM
 * association id. Shared by MainActivity (connects immediately once bonding finishes, so the
 * first read doesn't have to wait for a subsequent EVENT_BLE_APPEARED) and
 * BleCompanionDeviceService (connects on every later appearance while the app isn't observing
 * this directly).
 */
object GattTimestampReader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeGatts = mutableMapOf<Int, BluetoothGatt>()
    private val connectTimeoutJobs = mutableMapOf<Int, Job>()

    fun connectAndRead(context: Context, associationId: Int, device: BluetoothDevice) {
        if (activeGatts.containsKey(associationId)) return
        val appContext = context.applicationContext
        if (!hasConnectPermission(appContext)) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }

        BleRepository.updateStatus(BleStatus.CONNECTING)
        connectTimeoutJobs[associationId] = scope.launch {
            delay(BleConstants.CONNECT_TIMEOUT_MS)
            BleRepository.updateStatus(BleStatus.ERROR)
            closeGatt(appContext, associationId)
        }
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(appContext, false, gattCallback(appContext, associationId), BluetoothDevice.TRANSPORT_LE)
        activeGatts[associationId] = gatt
    }

    fun disconnect(context: Context, associationId: Int) {
        val appContext = context.applicationContext
        if (hasConnectPermission(appContext)) {
            activeGatts[associationId]?.disconnect()
        } else {
            closeGatt(appContext, associationId)
        }
    }

    private fun gattCallback(context: Context, associationId: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJobs.remove(associationId)?.cancel()
                    if (!hasConnectPermission(context)) return
                    BleRepository.updateStatus(BleStatus.DISCOVERING_SERVICES)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeoutJobs.remove(associationId)?.cancel()
                    BleRepository.updateStatus(BleStatus.DISCONNECTED)
                    closeGatt(context, associationId)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BleRepository.updateStatus(BleStatus.ERROR)
                disconnect(context, associationId)
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)
            if (characteristic == null) {
                BleRepository.updateStatus(BleStatus.ERROR)
                disconnect(context, associationId)
                return
            }
            if (!hasConnectPermission(context)) return
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
                    deviceName = if (hasConnectPermission(context)) gatt.device.name else null,
                    deviceAddress = gatt.device.address,
                    timestampText = timestampText
                )
                BleRepository.updateStatus(BleStatus.READ_SUCCESS)
            } else {
                BleRepository.updateStatus(BleStatus.ERROR)
            }
            disconnect(context, associationId)
        }
    }

    private fun closeGatt(context: Context, associationId: Int) {
        connectTimeoutJobs.remove(associationId)?.cancel()
        val gatt = activeGatts.remove(associationId) ?: return
        if (hasConnectPermission(context)) {
            gatt.close()
        }
    }

    private fun hasConnectPermission(context: Context) =
        ContextCompat.checkSelfPermission(
            context,
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
