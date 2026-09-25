package com.example.ble_attendance_sys

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class BleServerClient(
    context: Context,
    private val baseUrl: String
) {
    private val prefs = context.getSharedPreferences(
        "ble_upload_queue_v1",
        Context.MODE_PRIVATE
    )
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val pending = mutableListOf<JSONObject>()
    private var activeSession: JSONObject? = null

    init {
        val saved = prefs.getString("pending", "[]") ?: "[]"

        try {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                pending.add(array.getJSONObject(i))
            }
        } catch (error: Exception) {
            Log.e("BLE_UPLOAD", "Could not read upload queue", error)
        }

        flushAsync()
    }

    fun beginSession(
        classId: String,
        beaconId: String,
        startEpochMs: Long,
        startElapsedNs: Long
    ) {
        activeSession = JSONObject().apply {
            put("protocol_version", 1)
            put("session_id", UUID.randomUUID().toString())
            put("class_id", classId)
            put("beacon_id", beaconId)
            put("start_epoch_ms", startEpochMs)
            put("start_elapsed_ns", startElapsedNs)
        }

        Log.d(
            "BLE_UPLOAD",
            "New session: ${activeSession!!.getString("session_id")}"
        )
    }

    fun enqueue(window: BleWindow) {
        val session = activeSession ?: return

        val record = JSONObject().apply {
            put("session", JSONObject(session.toString()))

            put("window", JSONObject().apply {
                put("window_index", window.windowIndex)
                put("scan_state", window.scanState)
                put("sample_count", window.sampleCount)
                put(
                    "mean_rssi_dbm",
                    window.meanRssiDbm ?: JSONObject.NULL
                )
                put(
                    "std_rssi_dbm",
                    window.stdRssiDbm ?: JSONObject.NULL
                )
                put(
                    "last_packet_elapsed_ns",
                    window.lastPacketElapsedNs ?: JSONObject.NULL
                )
            })
        }

        synchronized(lock) {
            pending.add(record)
            saveQueueLocked()
        }

        flushAsync()
    }

    private fun saveQueueLocked() {
        val array = JSONArray()
        pending.forEach { array.put(it) }

        if (
            !prefs.edit()
                .putString("pending", array.toString())
                .commit()
        ) {
            Log.e("BLE_UPLOAD", "Could not persist upload queue")
        }
    }

    private fun flushAsync() {
        if (!worker.isShutdown) {
            worker.execute { flushQueue() }
        }
    }

    private fun flushQueue() {
        while (true) {
            val record = synchronized(lock) {
                pending.firstOrNull()
            } ?: return

            try {
                val session = record.getJSONObject("session")
                val window = record.getJSONObject("window")

                post("/v1/collection-sessions", session)

                post(
                    "/v1/ble-windows:batch",
                    JSONObject().apply {
                        put("protocol_version", 1)
                        put(
                            "session_id",
                            session.getString("session_id")
                        )
                        put(
                            "beacon_id",
                            session.getString("beacon_id")
                        )
                        put(
                            "windows",
                            JSONArray().put(window)
                        )
                    }
                )

                synchronized(lock) {
                    pending.removeAt(0)
                    saveQueueLocked()
                }

                Log.d(
                    "BLE_UPLOAD",
                    "Uploaded window ${window.getLong("window_index")}"
                )
            } catch (error: Exception) {
                Log.e(
                    "BLE_UPLOAD",
                    "Upload failed; window kept for retry",
                    error
                )
                return
            }
        }
    }

    private fun post(path: String, body: JSONObject) {
        val connection = URL(
            baseUrl.trimEnd('/') + path
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.outputStream.use {
                it.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }

                throw IllegalStateException(
                    "HTTP $status $path: $detail"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun close() {
        worker.shutdown()
    }
}