package com.example.ble_attendance_sys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.ble_attendance_sys.ui.theme.BLE_Attendance_SysTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateListOf
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BleDeviceUi(
    val name: String,
    val address: String,
    val rssi: Int
)

data class RssiSample(
    val timestamp: Long,
    val rssi: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkBluetoothPermissions()
        initializeBluetooth()

        setContent {
            BLE_Attendance_SysTheme {

                val visibleDevices = scannedDevices.values
                    .filter { device -> device.name != "Unknown" }
                val selectedDevice = selectedDeviceAddress?.let { address ->
                    scannedDevices[address]
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "BLE Attendance System"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isScanning) {
                            "상태: 스캔 중"
                        } else {
                            "상태: 스캔 중지"
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row {

                        Button(
                            onClick = {
                                startBleScan()
                            }
                        ) {
                            Text("스캔 시작")
                        }

                        Spacer(modifier = Modifier.padding(6.dp))

                        Button(
                            onClick = {
                                stopBleScan()
                            }
                        ) {
                            Text("스캔 중지")
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (selectedDevice != null) {

                        Text(
                            text = "선택된 장치: ${selectedDevice.name}"
                        )

                        Text(
                            text = "현재 RSSI: ${selectedDevice.rssi} dBm"
                        )

                        Text(
                            text = selectedDevice.address
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (isCollecting) {
                                "데이터 수집 중 (${rssiSamples.size}개)"
                            } else {
                                "데이터 수집 중지 (${rssiSamples.size}개)"
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row {
                            Button(
                                onClick = {
                                    startRssiCollection()
                                },
                                enabled = !isCollecting
                            ) {
                                Text("수집 시작")
                            }

                            Spacer(modifier = Modifier.padding(6.dp))

                            Button(
                                onClick = {
                                    stopRssiCollection()
                                },
                                enabled = isCollecting
                            ) {
                                Text("수집 중지")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                saveRssiSamplesToCsv()
                            },
                            enabled = !isCollecting && rssiSamples.isNotEmpty()
                        ) {
                            Text("CSV 저장")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    Text(
                        text = "발견된 장치: ${visibleDevices.size}개"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        items(
                            items = visibleDevices,
                            key = { device -> device.address }
                        ) { device ->

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDeviceAddress = device.address
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(text = device.name)
                                Text(text = device.address)
                                Text(text = "RSSI: ${device.rssi} dBm")
                            }

                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning by mutableStateOf(false)
    private val scannedDevices = mutableStateMapOf<String, BleDeviceUi>()
    private var selectedDeviceAddress by mutableStateOf<String?>(null)
    private var isCollecting by mutableStateOf(false)
    private val rssiSamples = mutableStateListOf<RssiSample>()

    private val requestBluetoothPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val scanGranted =
                permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false

            val connectGranted =
                permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false

            if (scanGranted && connectGranted) {
                println("Bluetooth permissions granted")
            } else {
                println("Bluetooth permissions denied")
            }
        }

    private fun checkBluetoothPermissions() {

        val scanPermission =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)

        val connectPermission =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)

        if (
            scanPermission != PackageManager.PERMISSION_GRANTED ||
            connectPermission != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
    }

    private fun initializeBluetooth() {
        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            if (
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val deviceName = result.device.name ?: "Unknown"
            val deviceAddress = result.device.address
            if (
                isCollecting &&
                deviceAddress != selectedDeviceAddress
            ) {
                return
            }
            val rssi = result.rssi

            scannedDevices[deviceAddress] = BleDeviceUi(
                name = deviceName,
                address = deviceAddress,
                rssi = rssi
            )
            if (isCollecting) {
                rssiSamples.add(
                    RssiSample(
                        timestamp = System.currentTimeMillis(),
                        rssi = rssi
                    )
                )
            }

            Log.d(
                "BLE_SCAN",
                "Device: $deviceName, Address: $deviceAddress, RSSI: $rssi"
            )
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)

            Log.e(
                "BLE_SCAN",
                "Scan failed. Error code: $errorCode"
            )
        }
    }

    private fun startBleScan() {

        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d("BLE_SCAN", "Bluetooth is turned off")
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Log.e("BLE_SCAN", "BLE Scanner is not available")
            return
        }

        if (!isScanning) {

            scannedDevices.clear()
            selectedDeviceAddress = null

            bluetoothLeScanner?.startScan(scanCallback)
            isScanning = true

            Log.d("BLE_SCAN", "BLE scan started")
        }
    }
    private fun stopBleScan() {

        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false

            Log.d("BLE_SCAN", "BLE scan stopped")
        }
    }

    private fun startRssiCollection() {

        val address = selectedDeviceAddress ?: return

        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 기존 전체 장치 스캔 중지
        bluetoothLeScanner?.stopScan(scanCallback)

        rssiSamples.clear()
        isCollecting = true

        // 선택된 장치만 고속 스캔
        startTargetScan(address)

        Log.d("RSSI_DATA", "RSSI collection started")
    }
    private fun stopRssiCollection() {

        isCollecting = false

        Log.d(
            "RSSI_DATA",
            "RSSI collection stopped. Samples: ${rssiSamples.size}"
        )
    }

    private fun startTargetScan(address: String) {

        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        bluetoothLeScanner?.startScan(
            listOf(filter),
            settings,
            scanCallback
        )

        isScanning = true

        Log.d("BLE_SCAN", "Target scan started: $address")
    }

    private fun saveRssiSamplesToCsv() {

        if (rssiSamples.isEmpty()) {
            Log.d("RSSI_DATA", "No RSSI samples to save")
            return
        }

        val fileName = "rssi_${
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(Date())
        }.csv"

        val contentValues = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                "text/csv"
            )
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/BLEAttendanceSystem"
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            contentValues
        )

        if (uri == null) {
            Log.e("RSSI_DATA", "Failed to create CSV file")
            return
        }

        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->

            writer.write(
                "timestamp_ms,datetime,rssi,interval_ms,device_name,device_address\n"
            )

            var previousTimestamp: Long? = null

            for (sample in rssiSamples) {

                val dateTime = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
                ).format(Date(sample.timestamp))

                val interval =
                    if (previousTimestamp == null) {
                        0
                    } else {
                        sample.timestamp - previousTimestamp!!
                    }

                val address = selectedDeviceAddress ?: ""
                val name = scannedDevices[address]?.name ?: "Unknown"

                writer.write(
                    "${sample.timestamp}," +
                            "$dateTime," +
                            "${sample.rssi}," +
                            "$interval," +
                            "$name," +
                            "$address\n"
                )

                previousTimestamp = sample.timestamp
            }
        }

        Log.d(
            "RSSI_DATA",
            "CSV saved: $fileName, samples: ${rssiSamples.size}"
        )
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BLE_Attendance_SysTheme {
        Greeting("Android")
    }
}