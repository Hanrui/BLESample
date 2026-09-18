package com.adrexample.blescannerdemo_cdm.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class BleStatus(val label: String) {
    IDLE("Idle"),
    SCANNING("Scanning..."),
    CONNECTING("Connecting..."),
    DISCOVERING_SERVICES("Discovering services..."),
    READING_CHARACTERISTIC("Reading characteristic..."),
    READ_SUCCESS("Read successful"),
    DISCONNECTED("Disconnected"),
    ERROR("Error")
}

data class TimestampLogEntry(
    val id: Long,
    val deviceName: String?,
    val deviceAddress: String,
    val timestampText: String,
    val receivedAtText: String
)

/**
 * In-process bridge between the foreground service and the Compose UI.
 * Both sides observe the same StateFlow instances, so no broadcast/binder plumbing is needed.
 */
object BleRepository {
    private val _status = MutableStateFlow(BleStatus.IDLE)
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<TimestampLogEntry>>(emptyList())
    val logs: StateFlow<List<TimestampLogEntry>> = _logs.asStateFlow()

    private val idGenerator = AtomicLong(0)
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun updateStatus(status: BleStatus) {
        _status.value = status
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun addLog(deviceName: String?, deviceAddress: String, timestampText: String) {
        val entry = TimestampLogEntry(
            id = idGenerator.incrementAndGet(),
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            timestampText = timestampText,
            receivedAtText = timeFormatter.format(Date())
        )
        _logs.value = _logs.value + entry
    }
}
