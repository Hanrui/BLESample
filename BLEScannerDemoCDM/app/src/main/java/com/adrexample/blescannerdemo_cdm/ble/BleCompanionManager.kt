package com.adrexample.blescannerdemo_cdm.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

private const val TAG = "BleCompanionManager"

fun associateBlePeripheral(
    activity: ComponentActivity,
    deviceManager: CompanionDeviceManager,
    chooserLauncher: ActivityResultLauncher<IntentSenderRequest>,
    serviceUuid: UUID,
    onAssociated: (AssociationInfo) -> Unit
) {
    val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
    val leFilter = BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build()
    val pairingRequest = AssociationRequest.Builder()
        .addDeviceFilter(leFilter)
        .setSingleDevice(true)
        .build()

    deviceManager.associate(
        pairingRequest,
        Executor { runnable -> runnable.run() },
        object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                chooserLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                onAssociated(associationInfo)
            }

            override fun onFailure(errorMessage: CharSequence?) {
                Log.e(TAG, "Companion association failed: $errorMessage")
            }
        }
    )
}

fun resolveBluetoothDevice(
    bluetoothAdapter: BluetoothAdapter,
    associationInfo: AssociationInfo
): BluetoothDevice? {
    val viaScanResult = associationInfo.associatedDevice?.bleDevice?.device
    Log.d(TAG, "resolveBluetoothDevice: associatedDevice=${associationInfo.associatedDevice}, viaScanResult=$viaScanResult, deviceMacAddress=${associationInfo.deviceMacAddress}")
    viaScanResult?.let { return it }
    val uppercaseAddress = associationInfo.deviceMacAddress
        ?.toString()
        ?.uppercase(Locale.ROOT)
        ?: return null
    return bluetoothAdapter.getRemoteDevice(uppercaseAddress)
}

fun startPresenceObservation(deviceManager: CompanionDeviceManager, associationInfo: AssociationInfo) {
    val request = ObservingDevicePresenceRequest.Builder()
        .setAssociationId(associationInfo.id)
        .build()
    deviceManager.startObservingDevicePresence(request)
}

// Guards against the Activity's post-association flow and the CompanionDeviceService's
// presence-appeared handler both calling createBond() for the same device at nearly the same
// moment, which happens because the system can deliver EVENT_BLE_APPEARED within milliseconds
// of onAssociationCreated(). Confined to the main thread, same as BleGattClientManager's state.
private val pendingBondAddresses = mutableSetOf<String>()

/**
 * Bonding exchanges the Identity Resolving Key the system needs to recognize this peripheral's
 * rotating Resolvable Private Address across advertisements, which background presence detection
 * depends on. [onBonded] runs immediately if the device is already bonded.
 *
 * Pairing with a peripheral that hasn't customized its IO capabilities (e.g. a plain
 * BluetoothGattServer like BLEPeripheralDemo) uses PASSKEY_CONFIRMATION, which requires a human
 * to confirm a matching passkey dialog on *both* devices within the Bluetooth stack's ~30s SMP
 * timeout. If that confirmation doesn't happen on the peripheral's screen in time, bonding fails
 * with BOND_NONE and [onBonded] never runs — surfaced here as BleStatus.ERROR so it's visible
 * instead of silently stalling.
 */
@SuppressLint("MissingPermission")
fun ensureBondedThenConnect(context: Context, device: BluetoothDevice, onBonded: (BluetoothDevice) -> Unit) {
    Log.d(TAG, "ensureBondedThenConnect: ${device.address} bondState=${device.bondState}")
    if (device.bondState == BluetoothDevice.BOND_BONDED) {
        onBonded(device)
        return
    }
    if (!pendingBondAddresses.add(device.address)) {
        Log.d(TAG, "ensureBondedThenConnect: bonding already in progress for ${device.address}")
        return
    }
    val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val target = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            Log.d(TAG, "ACTION_BOND_STATE_CHANGED target=${target?.address} bondState=$bondState")
            if (target?.address != device.address) return
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondAddresses.remove(device.address)
                    ctx.unregisterReceiver(this)
                    onBonded(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w(TAG, "Bonding failed/timed out for ${device.address}")
                    pendingBondAddresses.remove(device.address)
                    ctx.unregisterReceiver(this)
                    BleRepository.updateStatus(BleStatus.ERROR)
                }
            }
        }
    }
    context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    val started = device.createBond()
    Log.d(TAG, "createBond() returned $started")
}

fun handleAssociationCreated(
    context: Context,
    deviceManager: CompanionDeviceManager,
    bluetoothAdapter: BluetoothAdapter,
    associationInfo: AssociationInfo
) {
    Log.d(TAG, "handleAssociationCreated: id=${associationInfo.id}")
    startPresenceObservation(deviceManager, associationInfo)
    BleRepository.updateStatus(BleStatus.OBSERVING)
    val device = resolveBluetoothDevice(bluetoothAdapter, associationInfo)
    if (device == null) {
        Log.w(TAG, "handleAssociationCreated: resolveBluetoothDevice returned null")
        return
    }
    Log.d(TAG, "handleAssociationCreated: resolved ${device.address}")
    ensureBondedThenConnect(context, device) {
        Log.d(TAG, "handleAssociationCreated: bonded, opening GATT")
        BleGattClientManager.connect(context.applicationContext, device)
    }
}
