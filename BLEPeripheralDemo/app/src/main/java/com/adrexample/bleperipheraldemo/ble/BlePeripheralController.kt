package com.adrexample.bleperipheraldemo.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Custom GATT service exposing the current time. */
val TIME_SERVICE_UUID: UUID = UUID.fromString("00000001-0000-1000-1000-000000000000")
val TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000002-0000-1000-1000-000000000000")

/**
 * Wraps the BLE peripheral APIs: connectable advertising plus a GATT server that
 * serves the current time to any device that connects and reads the characteristic,
 * then drops the connection. Reports every lifecycle event through [onLog].
 */
class BlePeripheralController(
    context: Context,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            onLog("Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            onLog("Advertising failed to start (${errorCodeToString(errorCode)})")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onLog("Device connected: ${device.address}")
                BluetoothProfile.STATE_DISCONNECTED -> onLog("Device disconnected: ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != TIME_CHARACTERISTIC_UUID) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            val timeString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val fullValue = timeString.toByteArray(Charsets.UTF_8)
            if (offset > fullValue.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }

            onLog("Sent current time to ${device.address}: $timeString")
            server.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                fullValue.copyOfRange(offset, fullValue.size)
            )
            server.cancelConnection(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattServer(): BluetoothGattServer? {
        gattServer?.let { return it }
        val server = bluetoothManager?.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            onLog("Advertising failed to start: could not open GATT server")
            return null
        }
        val characteristic = BluetoothGattCharacteristic(
            TIME_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val service = BluetoothGattService(TIME_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
        return server
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val bleAdapter = adapter
        if (bleAdapter == null || !bleAdapter.isEnabled) {
            onLog("Advertising failed to start: Bluetooth is not enabled")
            return
        }
        val bleAdvertiser = advertiser
        if (bleAdvertiser == null) {
            onLog("Advertising failed to start: BLE advertising is not supported on this device")
            return
        }
        if (ensureGattServer() == null) {
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(TIME_SERVICE_UUID))
            .build()

        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val bleAdvertiser = advertiser ?: return
        bleAdvertiser.stopAdvertising(advertiseCallback)
        onLog("Advertising stopped")
    }

    /** Stops advertising and tears down the GATT server; call when halting the cycle entirely. */
    @SuppressLint("MissingPermission")
    fun shutdown() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
    }

    private fun errorCodeToString(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        else -> "error code $errorCode"
    }
}
