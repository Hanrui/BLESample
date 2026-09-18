package com.adrexample.bleperipheraldemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adrexample.bleperipheraldemo.ble.BlePeripheralController
import com.adrexample.bleperipheraldemo.ui.theme.BLEPeripheralDemoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT
)

private const val ADVERTISE_DURATION_MS = 10_000L
private const val PAUSE_DURATION_MS = 50_000L

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLEPeripheralDemoTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text("BLE Peripheral Demo") })
                    }
                ) { innerPadding ->
                    BlePeripheralScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun BlePeripheralScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logEntries = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var isRunning by remember { mutableStateOf(false) }
    var cycleJob by remember { mutableStateOf<Job?>(null) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        logEntries.add("[$timestamp] $message")
        scope.launch { listState.animateScrollToItem((logEntries.size - 1).coerceAtLeast(0)) }
    }

    val controller = remember { BlePeripheralController(context) { message -> appendLog(message) } }

    fun beginCycle() {
        isRunning = true
        cycleJob = scope.launch {
            while (isActive) {
                controller.startAdvertising()
                delay(ADVERTISE_DURATION_MS)
                controller.stopAdvertising()
                delay(PAUSE_DURATION_MS)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            beginCycle()
        } else {
            appendLog("Advertising failed to start: required Bluetooth permissions were denied")
        }
    }

    fun onStartClicked() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            beginCycle()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun onStopClicked() {
        cycleJob?.cancel()
        cycleJob = null
        isRunning = false
        controller.shutdown()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { onStartClicked() }, enabled = !isRunning) {
                Text("Start")
            }
            Button(onClick = { onStopClicked() }, enabled = isRunning) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(logEntries) { entry ->
                Text(text = entry, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
