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
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
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
import com.adrexample.blescannerdemo_cdm.ble.TimestampLogEntry
import com.adrexample.blescannerdemo_cdm.ui.theme.BLEScannerDemoTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val companionDeviceManager: CompanionDeviceManager by lazy {
        getSystemService(CompanionDeviceManager::class.java)
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var associationLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingStart = false

    private val associationCallback = object : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) {
            associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }

        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            onAssociated(associationInfo)
        }

        override fun onFailure(error: CharSequence?) {
            BleRepository.updateStatus(BleStatus.ERROR, error?.toString() ?: "Association failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (pendingStart && grants.values.all { it }) {
                beginAssociationFlow()
            }
            pendingStart = false
        }

        // The final AssociationInfo arrives via associationCallback.onAssociationCreated() once
        // CompanionDeviceManager finishes resolving the picked device — not from this result.
        // Parsing EXTRA_ASSOCIATION here too raced that callback and fired onAssociated() (and
        // therefore createBond()) twice for a single pairing attempt, so this only checks for
        // cancellation/failure now.
        associationLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                BleRepository.updateStatus(BleStatus.ERROR, "Association was cancelled")
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

        restoreExistingAssociation()
    }

    // On a fresh process (app relaunch, or the process being restarted by the system), an
    // association from a previous run may already exist but not yet be bonded — bonding never
    // completes on its own, so without this the presence trigger below would stay silent forever.
    private fun restoreExistingAssociation() {
        val existingId = AssociationPrefs.getAssociationId(this) ?: return
        val associationInfo = companionDeviceManager.myAssociations.firstOrNull { it.id == existingId }
            ?: return
        startObservingPresence(existingId)
        bondDeviceIfNeeded(associationInfo)
    }

    private fun requestPermissionsAndStart() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            beginAssociationFlow()
        } else {
            pendingStart = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun beginAssociationFlow() {
        val existingId = AssociationPrefs.getAssociationId(this)
        val existingAssociation = existingId?.let { id ->
            companionDeviceManager.myAssociations.firstOrNull { it.id == id }
        }
        if (existingAssociation != null) {
            startObservingPresence(existingAssociation.id)
            bondDeviceIfNeeded(existingAssociation)
            return
        }

        BleRepository.updateStatus(BleStatus.ASSOCIATING)
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        val associationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()
        companionDeviceManager.associate(associationRequest, associationCallback, null)
    }

    private fun onAssociated(associationInfo: AssociationInfo) {
        AssociationPrefs.setAssociationId(this, associationInfo.id)
        startObservingPresence(associationInfo.id)
        bondDeviceIfNeeded(associationInfo)
    }

    // CDM's presence-observation trigger only fires once the peripheral is bonded (bonding is
    // what lets the controller resolve the peripheral's rotating BLE address back to a stable
    // identity, and on this stack it's also what establishes the LE connection CDM reports
    // presence from). An association alone never bonds the device, so without this call
    // BleCompanionDeviceService would never be woken up.
    private fun bondDeviceIfNeeded(associationInfo: AssociationInfo) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // MacAddress.toString() is lowercase; normalize to uppercase to match the hex format
        // BluetoothAdapter.getRemoteDevice() expects.
        val address = associationInfo.deviceMacAddress?.toString()?.uppercase() ?: return
        val device = getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
            ?: return
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            BleRepository.updateStatus(BleStatus.BONDING, "Bonding with $address")
            device.createBond()
        }
    }

    private fun startObservingPresence(associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        companionDeviceManager.startObservingDevicePresence(request)
        BleRepository.setObserving(true)
        BleRepository.updateStatus(BleStatus.OBSERVING)
    }

    private fun stopObserving() {
        val associationId = AssociationPrefs.getAssociationId(this) ?: return
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        companionDeviceManager.stopObservingDevicePresence(request)
        BleRepository.setObserving(false)
        BleRepository.updateStatus(BleStatus.IDLE)
    }

    private fun forgetDevice() {
        val associationId = AssociationPrefs.getAssociationId(this)
        if (associationId != null) {
            val request = ObservingDevicePresenceRequest.Builder()
                .setAssociationId(associationId)
                .build()
            companionDeviceManager.stopObservingDevicePresence(request)
            cancelBondIfInProgress(associationId)
            companionDeviceManager.disassociate(associationId)
        }
        AssociationPrefs.clear(this)
        BleRepository.setObserving(false)
        BleRepository.updateStatus(BleStatus.IDLE, "Device forgotten")
    }

    // A createBond() left in flight (e.g. the peripheral never answered the pairing request)
    // blocks every future createBond() call system-wide with a BUSY error until it resolves —
    // cancel it explicitly so forgetting a stuck device doesn't wedge the next pairing attempt.
    private fun cancelBondIfInProgress(associationId: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val address = companionDeviceManager.myAssociations
            .firstOrNull { it.id == associationId }
            ?.deviceMacAddress?.toString()?.uppercase()
            ?: return
        val device = getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
            ?: return
        if (Build.VERSION.SDK_INT >= 37 && device.bondState == BluetoothDevice.BOND_BONDING) {
            device.cancelBondProcess()
        }
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
    val statusDetail by BleRepository.statusDetail.collectAsState()
    val logs by BleRepository.logs.collectAsState()
    val isObserving by BleRepository.isObserving.collectAsState()

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
                text = "Status: ${status.label}" + (statusDetail?.let { " ($it)" } ?: ""),
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
                    enabled = !isObserving,
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
