package com.adrexample.blescannerdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
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
import com.adrexample.blescannerdemo.ble.BleConstants
import com.adrexample.blescannerdemo.ble.BleForegroundService
import com.adrexample.blescannerdemo.ble.BleRepository
import com.adrexample.blescannerdemo.ble.ServiceRunPrefs
import com.adrexample.blescannerdemo.ble.TimestampLogEntry
import com.adrexample.blescannerdemo.ui.theme.BLEScannerDemoTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (pendingStart && grants.values.all { it }) {
                startBleService()
            }
            pendingStart = false
        }

        setContent {
            BLEScannerDemoTheme {
                BleScannerScreen(
                    onStartClick = ::requestPermissionsAndStart,
                    onStopClick = ::stopBleService
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            startBleService()
        } else {
            pendingStart = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startBleService() {
        ServiceRunPrefs.setShouldRun(this, true)
        ContextCompat.startForegroundService(this, Intent(this, BleForegroundService::class.java))
    }

    private fun stopBleService() {
        ServiceRunPrefs.setShouldRun(this, false)
        val intent = Intent(this, BleForegroundService::class.java).apply {
            action = BleConstants.ACTION_STOP
        }
        startService(intent)
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("BLE Scanner Demo") }) }
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
                Button(onClick = onStartClick, modifier = Modifier.weight(1f)) {
                    Text("Start Service")
                }
                Button(onClick = onStopClick, modifier = Modifier.weight(1f)) {
                    Text("Stop Service")
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
