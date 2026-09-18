package com.adrexample.blescannerdemo_cdm.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adrexample.blescannerdemo_cdm.MainActivity
import com.adrexample.blescannerdemo_cdm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleForegroundService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanLoopJob: Job? = null
    private var connectTimeoutJob: Job? = null

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // True while a scan is actively running.
    private val isScanning = AtomicBoolean(false)

    // True from the moment a matching device is found until the connection attempt
    // (success or failure) fully resolves. Blocks new scans while set.
    private val isBusy = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BleRepository.setRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BleConstants.ACTION_STOP) {
            ServiceRunPrefs.setShouldRun(this, false)
            shutDown()
            return START_NOT_STICKY
        }

        // Required by the platform any time the service is started via startForegroundService(),
        // including an automatic system restart with a null intent — so this always runs first.
        startForegroundWithNotification()

        if (!ServiceRunPrefs.shouldRun(this)) {
            // Respawned (e.g. after the user stopped it, or after a task swipe) without the user
            // asking for it again. Don't resume scanning — shut back down immediately.
            shutDown()
            return START_NOT_STICKY
        }

        if (scanLoopJob == null) {
            startScanLoop()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away from Recents: treat this the same as pressing "Stop".
        ServiceRunPrefs.setShouldRun(this, false)
        shutDown()
        super.onTaskRemoved(rootIntent)
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanLoopJob?.cancel()
        connectTimeoutJob?.cancel()
        stopBleScan()
        closeGatt()
        serviceScope.cancel()
        BleRepository.updateStatus(BleStatus.IDLE)
        BleRepository.setRunning(false)
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        // IMPORTANCE_LOW ("Silent") was found to also suppress the status bar icon on some
        // devices, not just the sound. IMPORTANCE_DEFAULT keeps the icon reliably visible;
        // sound/vibration are disabled explicitly since setStatus() calls notify() frequently.
        val channel = NotificationChannel(
            BleConstants.NOTIFICATION_CHANNEL_ID,
            "BLE Scanner Service",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BLE Scanner")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        try {
            startForeground(
                BleConstants.NOTIFICATION_ID,
                buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            Log.d(TAG, "startForeground() succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() threw", e)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(BleConstants.NOTIFICATION_ID, buildNotification(text))
    }

    private fun setStatus(status: BleStatus, notificationText: String = status.label) {
        BleRepository.updateStatus(status)
        updateNotification(notificationText)
    }

    // ---- Scan loop: scan for SCAN_DURATION_MS every SCAN_CYCLE_MS ----

    private fun startScanLoop() {
        scanLoopJob = serviceScope.launch {
            while (isActive) {
                if (!isBusy.get()) {
                    startBleScan()
                    delay(BleConstants.SCAN_DURATION_MS)
                    stopBleScan()
                    if (!isBusy.get()) {
                        setStatus(BleStatus.IDLE, "Waiting for next scan window")
                    }
                }
                delay(BleConstants.SCAN_CYCLE_MS - BleConstants.SCAN_DURATION_MS)
            }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun hasConnectPermission() = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun startBleScan() {
        if (isScanning.get() || isBusy.get()) return
        if (!hasScanPermission()) {
            setStatus(BleStatus.ERROR, "Missing BLUETOOTH_SCAN permission")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus(BleStatus.ERROR, "Bluetooth is disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        bleScanner = scanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning.set(true)
        setStatus(BleStatus.SCANNING)
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopBleScan() {
        if (!isScanning.getAndSet(false)) return
        if (!hasScanPermission()) return
        bleScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isBusy.getAndSet(true)) return
            stopBleScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.set(false)
            setStatus(BleStatus.ERROR, "Scan failed: code $errorCode")
        }
    }

    // ---- Connect / discover / read ----

    // The stable connectGatt(Context, Boolean, Callback, transport) overload is deprecated in
    // favor of a BluetoothGattConnectionSettings API that sits behind a feature flag not
    // guaranteed to be enabled on all API 36 devices, so the stable overload is used deliberately.
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            setStatus(BleStatus.ERROR, "Missing BLUETOOTH_CONNECT permission")
            isBusy.set(false)
            return
        }
        setStatus(BleStatus.CONNECTING, "Connecting to ${device.address}")
        connectTimeoutJob = serviceScope.launch {
            delay(BleConstants.CONNECT_TIMEOUT_MS)
            setStatus(BleStatus.ERROR, "Connection timed out")
            closeGatt()
            isBusy.set(false)
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    if (!hasConnectPermission()) return
                    setStatus(BleStatus.DISCOVERING_SERVICES, "Discovering services on ${gatt.device.address}")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeoutJob?.cancel()
                    setStatus(BleStatus.DISCONNECTED, "Disconnected from ${gatt.device.address}")
                    closeGatt()
                    isBusy.set(false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus(BleStatus.ERROR, "Service discovery failed: status $status")
                disconnectGatt()
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)

            if (characteristic == null) {
                setStatus(BleStatus.ERROR, "Target characteristic not found")
                disconnectGatt()
                return
            }
            if (!hasConnectPermission()) return
            setStatus(BleStatus.READING_CHARACTERISTIC)
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
                    deviceName = if (hasConnectPermission()) gatt.device.name else null,
                    deviceAddress = gatt.device.address,
                    timestampText = timestampText
                )
                setStatus(BleStatus.READ_SUCCESS, "Read: $timestampText")
            } else {
                setStatus(BleStatus.ERROR, "Characteristic read failed: status $status")
            }
            disconnectGatt()
        }
    }

    private fun disconnectGatt() {
        if (hasConnectPermission()) {
            bluetoothGatt?.disconnect()
        } else {
            closeGatt()
            isBusy.set(false)
        }
    }

    private fun closeGatt() {
        if (hasConnectPermission()) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
    }

    // ---- Timestamp decoding ----

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
