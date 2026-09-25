package com.example.ble_attendance_sys

import kotlin.math.sqrt

data class BleWindow(
    val windowIndex: Long,
    val scanState: String,
    val sampleCount: Int,
    val meanRssiDbm: Double?,
    val stdRssiDbm: Double?,
    val lastPacketElapsedNs: Long?
)

class BleWindowAggregator(
    private val startElapsedNs: Long,
    private val windowNs: Long = 5_000_000_000L
) {
    private var index = 0L
    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var lastPacketNs: Long? = null
    private var scanRunning = true
    private var interrupted = false

    var latePacketsDropped = 0L
        private set

    private fun closeWindow(): BleWindow {
        val valid = !interrupted
        val window = BleWindow(
            windowIndex = index,
            scanState = if (valid) "RUNNING" else "INTERRUPTED",
            sampleCount = if (valid) count else 0,
            meanRssiDbm = if (valid && count > 0) mean else null,
            stdRssiDbm = if (valid && count > 0) sqrt(m2 / count) else null,
            lastPacketElapsedNs = if (valid) lastPacketNs else null
        )

        index++
        count = 0
        mean = 0.0
        m2 = 0.0
        lastPacketNs = null
        interrupted = !scanRunning
        return window
    }

    @Synchronized
    fun advanceTo(nowNs: Long): List<BleWindow> {
        require(nowNs >= startElapsedNs)
        val closed = ArrayList<BleWindow>()

        while (nowNs - startElapsedNs >= (index + 1) * windowNs) {
            closed.add(closeWindow())
        }
        return closed
    }

    @Synchronized
    fun addPacket(observedElapsedNs: Long, rssiDbm: Int): List<BleWindow> {
        if (observedElapsedNs < startElapsedNs + index * windowNs) {
            latePacketsDropped++
            return emptyList()
        }

        val closed = advanceTo(observedElapsedNs)
        if (!scanRunning || interrupted) return closed

        count++
        val delta = rssiDbm - mean
        mean += delta / count
        m2 += delta * (rssiDbm - mean)

        if (lastPacketNs == null || observedElapsedNs > lastPacketNs!!) {
            lastPacketNs = observedElapsedNs
        }
        return closed
    }

    @Synchronized
    fun setScanRunning(running: Boolean, nowNs: Long): List<BleWindow> {
        val closed = advanceTo(nowNs)
        if (!running) interrupted = true
        scanRunning = running
        return closed
    }
}