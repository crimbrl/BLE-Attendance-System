package com.example.ble_attendance_sys

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class BleServerClient(
    context: Context,
    private val baseUrl: String,
    private val onStoppedByServer: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())

    private val endpointKey = MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.trimEnd('/').toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val prefs = context.getSharedPreferences(
        "ble_upload_queue_v2_$endpointKey",
        Context.MODE_PRIVATE
    )

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private val pending = mutableListOf<JSONObject>()

    @Volatile
    private var activeSession: JSONObject? = null

    private var registered = false

    @Volatile
    private var notified = false

    init {
        try {
            val saved = JSONArray(prefs.getString("pending", "[]"))

            for (i in 0 until saved.length()) {
                pending.add(saved.getJSONObject(i))
            }
        } catch (error: Exception) {
            Log.e("BLE_UPLOAD", "Queue could not be loaded", error)

            main.post {
                CollectionUiState.uploadMessage =
                    "전송 대기열 읽기 실패: 앱 데이터를 지우지 말고 확인하세요"
            }
        }

        val pendingCount = pending.size

        val rejectedCount = runCatching {
            JSONArray(prefs.getString("rejected", "[]")).length()
        }.getOrDefault(0)

        main.post {
            CollectionUiState.pendingWindows = pendingCount
            CollectionUiState.rejectedWindows = rejectedCount
        }

        worker.scheduleWithFixedDelay(
            { tick() },
            0,
            5,
            TimeUnit.SECONDS
        )
    }

    fun beginSession(
        classId: String,
        studentId: String,
        studentName: String,
        beaconId: String,
        startEpochMs: Long,
        startElapsedNs: Long
    ) {
        activeSession = JSONObject().apply {
            put("protocol_version", 1)
            put("session_id", UUID.randomUUID().toString())
            put("class_id", classId)
            put("student_id", studentId)
            put("student_name", studentName)
            put("beacon_id", beaconId)
            put("start_epoch_ms", startEpochMs)
            put("start_elapsed_ns", startElapsedNs)
        }

        main.post {
            CollectionUiState.uploadMessage = "수업 참여 확인 중"
        }

        worker.execute {
            tick()
        }
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
    }

    private fun saveQueueLocked() {
        val items = JSONArray()

        pending.forEach {
            items.put(it)
        }

        if (!prefs.edit()
                .putString("pending", items.toString())
                .commit()
        ) {
            throw IllegalStateException("전송 대기열 저장 실패")
        }

        val count = pending.size

        main.post {
            CollectionUiState.pendingWindows = count
        }
    }

    private fun updateMessage(text: String) {
        main.post {
            if (!notified) {
                CollectionUiState.uploadMessage = text
            }
        }
    }

    private fun stopFromServer(message: String) {
        if (notified) return

        notified = true

        main.post {
            CollectionUiState.uploadMessage = message
            onStoppedByServer(message)
        }
    }

    private fun tick() {
        try {
            val session = activeSession
            var stopMessage: String? = null

            if (session != null) {
                try {
                    val lecture = request(
                        "GET",
                        "/v1/classes/${session.getString("class_id")}/status"
                    )

                    if (lecture.getString("status") != "ACTIVE") {
                        stopMessage =
                            if (lecture.getString("status") == "ENDED") {
                                "수업이 종료되었습니다"
                            } else {
                                "교수자가 수업을 시작한 뒤 참여하세요"
                            }
                    }

                    if (stopMessage == null && !registered) {
                        request(
                            "POST",
                            "/v1/collection-sessions",
                            session
                        )

                        registered = true
                    }
                } catch (e: HttpFailure) {
                    if (e.status in 400..499 && e.status != 429) {
                        stopMessage = e.message
                    } else {
                        throw e
                    }
                }
            }

            flushQueue()

            if (stopMessage != null) {
                stopFromServer(stopMessage)
            }
        } catch (error: Exception) {
            Log.e(
                "BLE_UPLOAD",
                "Upload pending; retry scheduled",
                error
            )

            updateMessage("서버 연결/전송 대기 · ${error.message}")
        }
    }

    private fun flushQueue() {
        val batch = synchronized(lock) {
            val first = pending.firstOrNull() ?: return
            val id = first.getJSONObject("session")
                .getString("session_id")

            pending.takeWhile {
                it.getJSONObject("session")
                    .getString("session_id") == id
            }.take(120)
        }

        val session = batch.first().getJSONObject("session")

        try {
            request(
                "POST",
                "/v1/collection-sessions",
                session
            )

            val result = request(
                "POST",
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
                        JSONArray().apply {
                            batch.forEach {
                                put(it.getJSONObject("window"))
                            }
                        }
                    )
                }
            )

            synchronized(lock) {
                repeat(batch.size) {
                    pending.removeAt(0)
                }

                saveQueueLocked()
            }

            main.post {
                CollectionUiState.lastUploadEpochMs =
                    System.currentTimeMillis()
            }

            updateMessage("서버 전송 완료")

            if (
                result.optBoolean("session_closed") &&
                session.getString("session_id") ==
                activeSession?.getString("session_id")
            ) {
                stopFromServer(
                    if (result.optString("close_reason") == "SUPERSEDED") {
                        "동일 학생의 새 수집 세션이 시작되어 종료합니다"
                    } else {
                        "수업이 종료되었습니다"
                    }
                )
            }
        } catch (e: HttpFailure) {
            if (e.status !in 400..499 || e.status == 429) {
                throw e
            }

            synchronized(lock) {
                val rejected = JSONArray(
                    prefs.getString("rejected", "[]")
                )

                batch.forEach {
                    rejected.put(
                        JSONObject()
                            .put("record", it)
                            .put("error", e.message)
                    )
                }

                if (!prefs.edit()
                        .putString("rejected", rejected.toString())
                        .commit()
                ) {
                    throw e
                }

                repeat(batch.size) {
                    pending.removeAt(0)
                }

                saveQueueLocked()

                val count = rejected.length()

                main.post {
                    CollectionUiState.rejectedWindows = count
                }
            }

            updateMessage("서버 거절 기록 별도 보관: ${e.message}")

            if (
                session.getString("session_id") ==
                activeSession?.getString("session_id")
            ) {
                stopFromServer("수업 참여 오류: ${e.message}")
            }
        }
    }

    private class HttpFailure(
        val status: Int,
        message: String
    ) : Exception(message)

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject {
        val c = URL(
            baseUrl.trimEnd('/') + path
        ).openConnection() as HttpURLConnection

        try {
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 5000

            if (body != null) {
                c.doOutput = true

                c.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )

                c.outputStream.use {
                    it.write(
                        body.toString().toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val status = c.responseCode

            val text = (
                    if (status in 200..299) {
                        c.inputStream
                    } else {
                        c.errorStream
                    }
                    )?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

            if (status !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("detail", text)
                } catch (_: Exception) {
                    text
                }

                throw HttpFailure(
                    status,
                    "HTTP $status: $detail"
                )
            }

            return if (text.isBlank()) {
                JSONObject()
            } else {
                JSONObject(text)
            }
        } finally {
            c.disconnect()
        }
    }

    fun close() {
        if (!worker.isShutdown) {
            worker.execute {
                try {
                    flushQueue()
                } catch (e: Exception) {
                    Log.e(
                        "BLE_UPLOAD",
                        "Final upload kept for next collection",
                        e
                    )
                }
            }
        }

        worker.shutdown()
    }
}