package com.example.ble_attendance_sys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local values displayed by MainActivity. The service owns collection. */
object CollectionUiState {
    var isCollecting by mutableStateOf(false)
    var deviceAddress by mutableStateOf<String?>(null)
    var scanInterrupted by mutableStateOf(false)
    val rssiSamples = mutableStateListOf<RssiSample>()
    val closedWindows = mutableStateListOf<BleWindow>()
}
