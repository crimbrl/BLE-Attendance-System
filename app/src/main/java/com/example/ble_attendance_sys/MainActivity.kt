package com.example.ble_attendance_sys

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ble_attendance_sys.ui.theme.BLE_Attendance_SysTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class RssiSample(
    val timestamp: Long,
    val rssi: Int
)

class MainActivity : ComponentActivity() {
    // Replace with the PC IPv4 address that worked in your phone browser.
    private var serverBaseUrl by mutableStateOf(
        "http://192.168.35.96:8000"
    )

    private var classCode by mutableStateOf("")
    private var studentId by mutableStateOf("")
    private var studentName by mutableStateOf("")
    private val joinWorker = Executors.newSingleThreadExecutor()
    private var joining by mutableStateOf(false)
    private var showSettings by mutableStateOf(true)
    private val closedWindows get() = CollectionUiState.closedWindows

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val savedSettings = getSharedPreferences(
            "student_settings",
            MODE_PRIVATE
        )

        serverBaseUrl =
            savedSettings.getString("server", serverBaseUrl) ?: serverBaseUrl

        classCode = savedSettings.getString("class", "").orEmpty()
        studentId = savedSettings.getString("student", "").orEmpty()
        studentName = savedSettings.getString("name", "").orEmpty()

        checkBluetoothPermissions()
        initializeBluetooth()

        setContent {
            AttendanceGreenTheme {
                AttendanceScreen(
                    studentName = studentName,
                    studentId = if (isCollecting) CollectionUiState.activeStudentId else studentId,
                    classCode = if (isCollecting) CollectionUiState.activeClassId else classCode,
                    joining = joining,
                    isCollecting = isCollecting,
                    scanInterrupted = CollectionUiState.scanInterrupted,
                    classroomName = CollectionUiState.classroomName,
                    beaconName = CollectionUiState.beaconName,
                    deviceAddress = CollectionUiState.deviceAddress,
                    uploadMessage = CollectionUiState.uploadMessage,
                    pendingWindows = CollectionUiState.pendingWindows,
                    rejectedWindows = CollectionUiState.rejectedWindows,
                    sampleCount = rssiSamples.size,
                    windowCount = closedWindows.size,
                    lastRssi = rssiSamples.lastOrNull()?.rssi,
                    lastWindowSummary = closedWindows.lastOrNull()?.let { last ->
                        "최근 Window ${last.windowIndex}: ${last.scanState}, " +
                                "패킷 ${last.sampleCount}개, 평균 RSSI ${last.meanRssiDbm ?: "없음"}"
                    },
                    onSettings = { showSettings = true },
                    onStart = { startRssiCollection() },
                    onStop = { stopRssiCollection() },
                    onExport = { saveRssiSamplesToCsv() }
                )
                if (showSettings && !isCollecting && !joining) {
                    AlertDialog(
                        onDismissRequest = {
                            showSettings = false
                        },
                        title = {
                            Text("수업 참여 설정")
                        },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(
                                    rememberScrollState()
                                )
                            ) {
                                OutlinedTextField(
                                    value = serverBaseUrl,
                                    onValueChange = {
                                        serverBaseUrl = it
                                    },
                                    label = {
                                        Text("서버 주소")
                                    },
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = studentId,
                                    onValueChange = {
                                        studentId = it.take(100)
                                    },
                                    label = {
                                        Text("학생 ID")
                                    },
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = studentName,
                                    onValueChange = {
                                        studentName = it.take(100)
                                    },
                                    label = {
                                        Text("이름")
                                    },
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = classCode,
                                    onValueChange = {
                                        classCode = it.uppercase().take(8)
                                    },
                                    label = {
                                        Text("8자리 수업 코드")
                                    },
                                    singleLine = true
                                )

                                Text("교수자 화면에서 수업을 시작한 뒤 참여하세요.")
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showSettings = false
                                }
                            ) {
                                Text("입력 완료")
                            }
                        }
                    )
                }
            }
        }

    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val isCollecting get() = CollectionUiState.isCollecting
    private val rssiSamples get() = CollectionUiState.rssiSamples

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("BLE_SCAN", "Notification permission granted: $granted")
    }

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
    }

    private fun startRssiCollection() {
        if (joining || isCollecting) return

        // 1. 학생 입력값 확인
        val parsed = runCatching {
            URI(serverBaseUrl.trim())
        }.getOrNull()

        if (
            parsed?.host.isNullOrBlank() ||
            parsed?.scheme !in listOf("http", "https") ||
            !classCode.matches(Regex("[A-F0-9]{8}")) ||
            studentId.isBlank() ||
            studentName.isBlank()
        ) {
            Toast.makeText(
                this,
                "서버 주소, 학생 ID·이름, 8자리 수업 코드를 확인하세요",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        serverBaseUrl = serverBaseUrl.trim().trimEnd('/')

        getSharedPreferences("student_settings", MODE_PRIVATE)
            .edit()
            .putString("server", serverBaseUrl)
            .putString("class", classCode)
            .putString("student", studentId.trim())
            .putString("name", studentName.trim())
            .apply()

        showSettings = false

        // 2. 블루투스 권한과 활성화 여부 확인
        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            checkBluetoothPermissions()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(
                this,
                "블루투스를 켜고 다시 참여하세요",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 이번 요청에 사용할 입력값을 고정
        val endpoint = serverBaseUrl
        val code = classCode
        val id = studentId.trim()
        val name = studentName.trim()

        joining = true
        CollectionUiState.uploadMessage = "수업과 강의실 비콘 확인 중"

        // 3. 별도 스레드에서 수업 정보 조회
        joinWorker.execute {
            val result = runCatching {
                val connection =
                    URL("$endpoint/v1/classes/$code/status")
                        .openConnection() as HttpURLConnection

                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val status = connection.responseCode

                    val body = (
                            if (status in 200..299) {
                                connection.inputStream
                            } else {
                                connection.errorStream
                            }
                            )?.bufferedReader()?.use {
                            it.readText()
                        }.orEmpty()

                    val lecture = JSONObject(body)

                    check(status in 200..299) {
                        lecture.optString(
                            "detail",
                            "수업 조회 실패 ($status)"
                        )
                    }

                    check(lecture.getString("status") == "ACTIVE") {
                        "교수자가 수업을 시작한 뒤 참여하세요 (종료된 수업은 새 코드 필요)"
                    }

                    val address =
                        lecture.optString("beacon_address").uppercase()

                    check(BluetoothAdapter.checkBluetoothAddress(address)) {
                        "강의실 비콘이 지정되지 않았습니다. 새 수업을 생성하세요"
                    }

                    lecture
                } finally {
                    connection.disconnect()
                }
            }

            // 4. UI 스레드로 돌아와 서비스 실행
            runOnUiThread {
                joining = false

                if (!isFinishing && !isDestroyed) {
                    result.onSuccess { lecture ->
                        if (
                            !lifecycle.currentState.isAtLeast(
                                androidx.lifecycle.Lifecycle.State.RESUMED
                            )
                        ) {
                            CollectionUiState.uploadMessage =
                                "앱으로 돌아와 수업 참여를 다시 눌러주세요"
                        } else {
                            runCatching {
                                val address =
                                    lecture.getString("beacon_address")
                                        .uppercase()

                                val intent = Intent(
                                    this,
                                    BleCollectionService::class.java
                                ).apply {
                                    action = BleCollectionService.ACTION_START

                                    putExtra(
                                        BleCollectionService.EXTRA_ADDRESS,
                                        address
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_SERVER_URL,
                                        endpoint
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_CLASS_ID,
                                        code
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_STUDENT_ID,
                                        id
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_STUDENT_NAME,
                                        name
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_CLASSROOM_NAME,
                                        lecture.getString("classroom_name")
                                    )
                                    putExtra(
                                        BleCollectionService.EXTRA_BEACON_NAME,
                                        lecture.getString("beacon_name")
                                    )
                                }

                                startForegroundService(intent)

                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestNotificationPermission.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            }.onFailure {
                                CollectionUiState.uploadMessage =
                                    "수집 시작 실패: ${it.message}"
                            }
                        }
                    }.onFailure {
                        CollectionUiState.uploadMessage =
                            "수업 참여 실패: ${it.message}"

                        Toast.makeText(
                            this,
                            CollectionUiState.uploadMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    private fun stopRssiCollection() {
        startService(Intent(this, BleCollectionService::class.java).apply {
            action = BleCollectionService.ACTION_STOP
        })
    }

    override fun onDestroy() {
        joinWorker.shutdownNow()
        super.onDestroy()
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

                val address = CollectionUiState.deviceAddress.orEmpty()
                val name =
                    "\"" + CollectionUiState.beaconName.replace("\"", "\"\"") + "\""

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
