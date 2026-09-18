package com.adrexample.blescannerdemo_cdm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adrexample.blescannerdemo_cdm.ble.BleConstants
import com.adrexample.blescannerdemo_cdm.ble.BleRepository
import com.adrexample.blescannerdemo_cdm.ble.BleStatus
import com.adrexample.blescannerdemo_cdm.ble.TimestampLogEntry
import com.adrexample.blescannerdemo_cdm.ui.theme.BLEScannerDemoTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val companionDeviceManager by lazy {
        getSystemService(CompanionDeviceManager::class.java)
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingPair = false

    // CDM's presence observer relies on Bluetooth identity resolution, which only works for
    // bonded devices. An association alone (BluetoothLeDeviceFilter match) doesn't bond the
    // device, so onDeviceAppeared() never fires unless we explicitly bond it here.
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            Log.d(TAG, "ACTION_BOND_STATE_CHANGED: device=$device newState=$newState")
            when (newState) {
                BluetoothDevice.BOND_BONDED -> BleRepository.updateStatus(BleStatus.WAITING_FOR_DEVICE)
                BluetoothDevice.BOND_NONE ->
                    if (BleRepository.status.value == BleStatus.BONDING) {
                        BleRepository.updateStatus(BleStatus.ERROR)
                    }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            bondStateReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (pendingPair && grants.values.all { it }) {
                startAssociation()
            }
            pendingPair = false
        }

        // The final AssociationInfo arrives via associationCallback.onAssociationCreated() once
        // CompanionDeviceManager finishes resolving the picked device — not from this result.
        // Parsing EXTRA_ASSOCIATION here too used to race that callback and produce two separate,
        // divergent associations (one with a null MAC address) for a single pairing attempt.
        intentSenderLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                BleRepository.updateStatus(BleStatus.ERROR)
            }
        }

        restoreExistingAssociation()

        setContent {
            BLEScannerDemoTheme {
                BleScannerScreen(
                    onPairClick = ::requestPermissionsAndPair,
                    onForgetClick = ::forgetDevice
                )
            }
        }
    }

    private fun restoreExistingAssociation() {
        val existing = companionDeviceManager?.myAssociations.orEmpty()
        if (existing.isNotEmpty()) {
            BleRepository.setAssociated(true)
            BleRepository.updateStatus(BleStatus.WAITING_FOR_DEVICE)
            existing.forEach {
                ensureObservingPresence(it.id)
                bondDeviceIfNeeded(it)
            }
        }
    }

    private fun requestPermissionsAndPair() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            startAssociation()
        } else {
            pendingPair = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startAssociation() {
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                    .build()
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        companionDeviceManager?.associate(
            request,
            associationCallback,
            Handler(Looper.getMainLooper())
        )
    }

    private val associationCallback = object : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) {
            Log.d(TAG, "onAssociationPending")
            intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }

        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            Log.d(TAG, "onAssociationCreated: $associationInfo")
            onAssociated(associationInfo)
        }

        override fun onFailure(error: CharSequence?) {
            Log.e(TAG, "associate() onFailure: $error")
            BleRepository.updateStatus(BleStatus.ERROR)
        }
    }

    private fun onAssociated(associationInfo: AssociationInfo) {
        Log.d(TAG, "onAssociated: id=${associationInfo.id} mac=${associationInfo.deviceMacAddress}")
        ensureObservingPresence(associationInfo.id)
        BleRepository.setAssociated(true)
        bondDeviceIfNeeded(associationInfo)
    }

    private fun bondDeviceIfNeeded(associationInfo: AssociationInfo) {
        // MacAddress.toString() is lowercase, but BluetoothAdapter.getRemoteDevice() only
        // accepts uppercase hex — passing it through as-is throws IllegalArgumentException.
        val address = associationInfo.deviceMacAddress?.toString()?.uppercase()
        val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "bondDeviceIfNeeded: address=$address hasConnect=$hasConnect")
        if (address == null || !hasConnect) {
            BleRepository.updateStatus(BleStatus.WAITING_FOR_DEVICE)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.getRemoteDevice(address) }
            .onFailure { Log.e(TAG, "getRemoteDevice failed", it) }
            .getOrNull()
        Log.d(TAG, "bondDeviceIfNeeded: device=$device bondState=${device?.bondState}")
        if (device != null && device.bondState == BluetoothDevice.BOND_NONE) {
            BleRepository.updateStatus(BleStatus.BONDING)
            device.createBond()
        } else {
            BleRepository.updateStatus(BleStatus.WAITING_FOR_DEVICE)
        }
    }

    private fun ensureObservingPresence(associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        companionDeviceManager?.startObservingDevicePresence(request)
    }

    private fun forgetDevice() {
        val manager = companionDeviceManager ?: return
        manager.myAssociations.forEach { info ->
            manager.stopObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
            )
            manager.disassociate(info.id)
        }
        BleRepository.setAssociated(false)
        BleRepository.updateStatus(BleStatus.NOT_PAIRED)
    }

    override fun onDestroy() {
        unregisterReceiver(bondStateReceiver)
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BleScannerScreen(
    onPairClick: () -> Unit,
    onForgetClick: () -> Unit
) {
    val status by BleRepository.status.collectAsState()
    val logs by BleRepository.logs.collectAsState()
    val isAssociated by BleRepository.isAssociated.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("BLE Scanner Demo - CDM") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Status: ${status.label}",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPairClick,
                    enabled = !isAssociated,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pair Device")
                }
                Button(
                    onClick = onForgetClick,
                    enabled = isAssociated,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Forget Device")
                }
            }

            HorizontalDivider()

            Text(
                text = "Timestamp Logs",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs.asReversed(), key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
fun LogRow(entry: TimestampLogEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.deviceName?.let { "$it (${entry.deviceAddress})" } ?: entry.deviceAddress,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Timestamp: ${entry.timestampText}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Received at: ${entry.receivedAtText}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
