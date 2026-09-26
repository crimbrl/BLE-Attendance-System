package com.example.ble_attendance_sys

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Owns BLE scanning, 5-second Windows, and uploads while the UI is not visible. */
class BleCollectionService : Service() {
    companion object {
        const val ACTION_START = "com.example.ble_attendance_sys.START_COLLECTION"
        const val ACTION_STOP = "com.example.ble_attendance_sys.STOP_COLLECTION"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_SERVER_URL = "server_url"
        private const val CHANNEL_ID = "ble_collection"
        private const val NOTIFICATION_ID = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var aggregator: BleWindowAggregator? = null
    private var uploader: BleServerClient? = null
    private var targetAddress: String? = null
    private var collecting = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!collecting) return
            saveWindows(aggregator?.advanceTo(SystemClock.elapsedRealtimeNanos()).orEmpty())
            handler.postDelayed(this, 1000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // All collection and UI state changes happen on the main thread.
            handler.post {
                if (!collecting || result.device.address != targetAddress) return@post
                saveWindows(aggregator?.addPacket(result.timestampNanos, result.rssi).orEmpty())
                CollectionUiState.rssiSamples.add(
                    RssiSample(System.currentTimeMillis(), result.rssi)
                )
                Log.d("BLE_SCAN", "Target RSSI: ${result.rssi}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                if (!collecting) return@post
                saveWindows(aggregator?.setScanRunning(
                    false, SystemClock.elapsedRealtimeNanos()
                ).orEmpty())
                CollectionUiState.scanInterrupted = true
                Log.e("BLE_SCAN", "Background scan failed: $errorCode")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCollection()
                stopSelf()
            }
            ACTION_START -> {
                if (collecting) return START_NOT_STICKY
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                if (address.isNullOrBlank() || serverUrl.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e("BLE_SCAN", "Bluetooth permissions are missing")
                    stopSelf()
                    return START_NOT_STICKY
                }

                createNotificationChannel()
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                startCollection(address, serverUrl)
            }
            else -> stopSelf()
        }
        // A killed service never invents missing Windows on restart.
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "BLE 수집", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "수업 중 BLE 신호 수집 상태" }
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BleCollectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BLE 출석 신호 수집 중")
            .setContentText("화면을 닫아도 5초 Window를 수집합니다")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "수집 중지", stop)
            .setOngoing(true)
            .build()
    }

    private fun startCollection(address: String, serverUrl: String) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE_SCAN", "Bluetooth permissions are missing")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) {
            Log.e("BLE_SCAN", "Bluetooth is off")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLE_SCAN", "BLE scanner is unavailable")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val startElapsedNs = SystemClock.elapsedRealtimeNanos()
        val startEpochMs = System.currentTimeMillis()
        aggregator = BleWindowAggregator(startElapsedNs)
        uploader = BleServerClient(applicationContext, serverUrl).apply {
            beginSession("class-demo-01", "room-demo-01", startEpochMs, startElapsedNs)
        }
        targetAddress = address
        CollectionUiState.deviceAddress = address
        CollectionUiState.scanInterrupted = false

        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            collecting = true
            CollectionUiState.isCollecting = true
            handler.post(ticker)
            Log.d("BLE_SCAN", "Foreground service scanning $address")
        } catch (error: Exception) {
            Log.e("BLE_SCAN", "Could not start BLE scan", error)
            stopCollection()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveWindows(windows: List<BleWindow>) {
        for (window in windows) {
            CollectionUiState.closedWindows.add(window)
            uploader?.enqueue(window)
            Log.d("BLE_WINDOW", "index=${window.windowIndex} state=${window.scanState} " +
                "count=${window.sampleCount} mean=${window.meanRssiDbm}")
        }
    }

    private fun stopCollection() {
        if (!collecting) return
        handler.removeCallbacks(ticker)
        saveWindows(aggregator?.advanceTo(SystemClock.elapsedRealtimeNanos()).orEmpty())
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (error: Exception) {
                Log.e("BLE_SCAN", "Could not stop BLE scan", error)
            }
        }
        uploader?.close()
        uploader = null
        scanner = null
        aggregator = null
        collecting = false
        CollectionUiState.isCollecting = false
        CollectionUiState.scanInterrupted = false
        Log.d("BLE_SCAN", "Foreground collection stopped")
    }

    override fun onDestroy() {
        stopCollection()
        super.onDestroy()
    }
}
