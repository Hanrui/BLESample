package com.adrexample.blescannerdemo_cdm

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import android.os.ParcelUuid
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
import com.adrexample.blescannerdemo_cdm.ble.AssociationPrefs
import com.adrexample.blescannerdemo_cdm.ble.BleConstants
import com.adrexample.blescannerdemo_cdm.ble.BleRepository
import com.adrexample.blescannerdemo_cdm.ble.BleStatus
import com.adrexample.blescannerdemo_cdm.ble.GattTimestampReader
import com.adrexample.blescannerdemo_cdm.ble.TimestampLogEntry
import com.adrexample.blescannerdemo_cdm.ui.theme.BLEScannerDemoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val companionDeviceManager: CompanionDeviceManager by lazy {
        getSystemService(CompanionDeviceManager::class.java)
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingStart = false
    private var pendingBondAssociationId: Int? = null

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (bondState == BluetoothDevice.BOND_BONDED) {
                pendingBondAssociationId?.let { startObserving(it) }
                pendingBondAssociationId = null
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

        BleRepository.setAssociated(AssociationPrefs.getAssociationId(this) != null)
        BleRepository.setObserving(AssociationPrefs.isObserving(this))

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (pendingStart && grants.values.all { it }) {
                startAssociationFlow()
            }
            pendingStart = false
        }

        intentSenderLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val info = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
            if (result.resultCode == RESULT_OK && info != null) {
                handleAssociationInfo(info)
            } else {
                BleRepository.updateStatus(BleStatus.ERROR)
            }
        }

        setContent {
            BLEScannerDemoTheme {
                BleScannerScreen(
                    onStartClick = ::requestPermissionsAndStart,
                    onStopClick = ::stopObserving,
                    onForgetClick = ::forgetDevice
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(bondStateReceiver)
        activityScope.cancel()
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            startAssociationFlow()
        } else {
            pendingStart = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun findExistingAssociation(associationId: Int): AssociationInfo? =
        companionDeviceManager.myAssociations.find { it.id == associationId }

    private fun startAssociationFlow() {
        val existingId = AssociationPrefs.getAssociationId(this)
        val existingInfo = existingId?.let { id -> findExistingAssociation(id) }
        if (existingInfo != null) {
            bondAndObserve(existingInfo)
            return
        }

        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                    .build()
            )
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        companionDeviceManager.associate(
            request,
            ContextCompat.getMainExecutor(this),
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    handleAssociationInfo(associationInfo)
                }

                override fun onFailure(error: CharSequence?) {
                    BleRepository.updateStatus(BleStatus.ERROR)
                }
            }
        )
    }

    private fun handleAssociationInfo(info: AssociationInfo) {
        AssociationPrefs.setAssociationId(this, info.id)
        BleRepository.setAssociated(true)
        bondAndObserve(info)
    }

    private fun bondAndObserve(info: AssociationInfo) {
        val macAddress = info.deviceMacAddress?.toString()?.uppercase(Locale.US)
        if (macAddress == null || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device == null) {
            BleRepository.updateStatus(BleStatus.ERROR)
            return
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            startObservingAndConnect(info.id, device)
            return
        }

        pendingBondAssociationId = info.id
        // createBond() returns false without a follow-up ACTION_BOND_STATE_CHANGED broadcast
        // when bonding can't be (re)started -- most commonly because the device is already
        // bonded (e.g. the CDM picker bonded it during selection). Fall back to the current
        // state so a single button press always finishes the flow instead of hanging forever
        // on a broadcast that will never arrive.
        if (!device.createBond()) {
            pendingBondAssociationId = null
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                startObservingAndConnect(info.id, device)
            } else {
                BleRepository.updateStatus(BleStatus.ERROR)
            }
            return
        }

        // The ACTION_BOND_STATE_CHANGED broadcast is the fast path, but it isn't always
        // delivered promptly (or at all) for every OEM/peripheral combination. Poll the bond
        // state directly as a fallback so a single button press reliably converges instead of
        // silently waiting forever on a broadcast that never arrives.
        BleRepository.updateStatus(BleStatus.BONDING)
        pollUntilBonded(info.id, device)
    }

    private fun pollUntilBonded(associationId: Int, device: BluetoothDevice) {
        activityScope.launch {
            val deadline = System.currentTimeMillis() + BleConstants.BOND_TIMEOUT_MS
            while (pendingBondAssociationId == associationId && System.currentTimeMillis() < deadline) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    pendingBondAssociationId = null
                    startObservingAndConnect(associationId, device)
                    return@launch
                }
                delay(500)
            }
            if (pendingBondAssociationId == associationId) {
                pendingBondAssociationId = null
                BleRepository.updateStatus(BleStatus.ERROR)
            }
        }
    }

    private fun startObserving(associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        companionDeviceManager.startObservingDevicePresence(request)
        AssociationPrefs.setObserving(this, true)
        BleRepository.setObserving(true)
        BleRepository.updateStatus(BleStatus.OBSERVING)
    }

    // The device is already right here the moment bonding finishes -- connect and read now
    // instead of waiting for CDM's independent presence engine to fire EVENT_BLE_APPEARED,
    // which may not happen until the device leaves and re-enters range.
    private fun startObservingAndConnect(associationId: Int, device: BluetoothDevice) {
        startObserving(associationId)
        GattTimestampReader.connectAndRead(this, associationId, device)
    }

    private fun stopObserving() {
        val associationId = AssociationPrefs.getAssociationId(this) ?: return
        if (findExistingAssociation(associationId) != null) {
            stopObservingRequest(associationId)
        }
        AssociationPrefs.setObserving(this, false)
        BleRepository.setObserving(false)
        BleRepository.updateStatus(BleStatus.IDLE)
    }

    private fun stopObservingRequest(associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        companionDeviceManager.stopObservingDevicePresence(request)
    }

    private fun forgetDevice() {
        val associationId = AssociationPrefs.getAssociationId(this) ?: return
        // The stored association id may be stale (e.g. it was already removed on the system
        // side). CDM throws IllegalArgumentException from these calls for an unknown id instead
        // of no-oping, so only touch the system association if it's still actually there.
        if (findExistingAssociation(associationId) != null) {
            stopObservingRequest(associationId)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                companionDeviceManager.removeBond(associationId)
            }
            companionDeviceManager.disassociate(associationId)
        }
        pendingBondAssociationId = null
        AssociationPrefs.clear(this)
        BleRepository.setObserving(false)
        BleRepository.setAssociated(false)
        BleRepository.updateStatus(BleStatus.IDLE)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BleScannerScreen(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onForgetClick: () -> Unit
) {
    val status by BleRepository.status.collectAsState()
    val logs by BleRepository.logs.collectAsState()
    val isObserving by BleRepository.isObserving.collectAsState()
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
                    onClick = onStartClick,
                    enabled = !isObserving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Observing")
                }
                Button(
                    onClick = onStopClick,
                    enabled = isObserving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop Observing")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
