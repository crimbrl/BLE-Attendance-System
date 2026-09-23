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

data class BleDeviceUi(
    val name: String,
    val address: String,
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

                            Text(text = device.name)
                            Text(text = device.address)
                            Text(text = "RSSI: ${device.rssi} dBm")

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
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
            val rssi = result.rssi

            scannedDevices[deviceAddress] = BleDeviceUi(
                name = deviceName,
                address = deviceAddress,
                rssi = rssi
            )

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