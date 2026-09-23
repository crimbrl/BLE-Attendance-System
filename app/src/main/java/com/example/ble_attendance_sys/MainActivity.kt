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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkBluetoothPermissions()
        initializeBluetooth()

        setContent {
            BLE_Attendance_SysTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "BLE Attendance System"
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            startBleScan()
                        }
                    ) {
                        Text("스캔 시작")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            stopBleScan()
                        }
                    ) {
                        Text("스캔 중지")
                    }
                }
            }
        }
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

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