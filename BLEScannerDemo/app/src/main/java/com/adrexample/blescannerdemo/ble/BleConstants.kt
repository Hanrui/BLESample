package com.adrexample.blescannerdemo.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("00000001-0000-1000-1000-000000000000")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("00000002-0000-1000-1000-000000000000")

    const val SCAN_DURATION_MS = 5_000L
    const val SCAN_CYCLE_MS = 20_000L
    const val CONNECT_TIMEOUT_MS = 10_000L

    const val NOTIFICATION_CHANNEL_ID = "ble_scanner_channel"
    const val NOTIFICATION_ID = 1001

    const val ACTION_STOP = "com.adrexample.blescannerdemo.action.STOP"
}
