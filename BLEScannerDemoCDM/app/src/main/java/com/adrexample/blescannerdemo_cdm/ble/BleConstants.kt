package com.adrexample.blescannerdemo_cdm.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("00000001-0000-1000-1000-000000000000")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("00000002-0000-1000-1000-000000000000")

    const val CONNECT_TIMEOUT_MS = 10_000L
}
