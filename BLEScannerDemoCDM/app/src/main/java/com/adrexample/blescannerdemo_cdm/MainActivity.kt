package com.adrexample.blescannerdemo_cdm

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.adrexample.blescannerdemo_cdm.ble.BleGattClientManager
import com.adrexample.blescannerdemo_cdm.ble.BleRepository
import com.adrexample.blescannerdemo_cdm.ble.BleStatus
import com.adrexample.blescannerdemo_cdm.ble.TimestampLogEntry
import com.adrexample.blescannerdemo_cdm.ble.associateBlePeripheral
import com.adrexample.blescannerdemo_cdm.ble.handleAssociationCreated
import com.adrexample.blescannerdemo_cdm.ble.startPresenceObservation
import com.adrexample.blescannerdemo_cdm.ui.theme.BLEScannerDemoTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var deviceManager: CompanionDeviceManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var chooserLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var connectPermissionLauncher: ActivityResultLauncher<String>
    private var pendingAssociate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deviceManager = getSystemService(CompanionDeviceManager::class.java)
        bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        chooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.w(TAG, "User canceled companion association: ${result.resultCode}")
            }
        }
        connectPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted && pendingAssociate) startAssociation()
            pendingAssociate = false
        }

        restoreAssociations()

        setContent {
            BLEScannerDemoTheme {
                BleScannerScreen(
                    onStartClick = ::requestConnectPermissionAndAssociate,
                    onStopClick = ::disassociate
                )
            }
        }
    }

    private fun restoreAssociations() {
        val associations = deviceManager.myAssociations
        if (associations.isEmpty()) return
        BleRepository.setAssociated(true)
        BleRepository.updateStatus(BleStatus.OBSERVING)
        associations.forEach { startPresenceObservation(deviceManager, it) }
    }

    private fun requestConnectPermissionAndAssociate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startAssociation()
        } else {
            pendingAssociate = true
            connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun startAssociation() {
        val existing = deviceManager.myAssociations.firstOrNull()
        if (existing != null) {
            Log.d(TAG, "startAssociation: reusing existing association id=${existing.id}")
            onAssociated(existing)
            return
        }
        Log.d(TAG, "startAssociation: launching new association")
        associateBlePeripheral(this, deviceManager, chooserLauncher, BleConstants.SERVICE_UUID) {
            Log.d(TAG, "startAssociation: onAssociated callback fired, id=${it.id}")
            onAssociated(it)
        }
    }

    private fun onAssociated(associationInfo: AssociationInfo) {
        BleRepository.setAssociated(true)
        handleAssociationCreated(this, deviceManager, bluetoothAdapter, associationInfo)
    }

    private fun disassociate() {
        deviceManager.myAssociations.forEach { deviceManager.disassociate(it.id) }
        BleGattClientManager.disconnectAndClose()
        BleRepository.setAssociated(false)
        BleRepository.updateStatus(BleStatus.IDLE)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BleScannerScreen(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
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

                    onClick = onStartClick,
                    enabled = !isAssociated,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pair Device")
                }
                Button(
                    onClick = onStopClick,
                    enabled = isAssociated,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Unpair")
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
