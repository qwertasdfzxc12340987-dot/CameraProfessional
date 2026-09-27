package com.example.cameraprofessional

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PerformanceMonitor
 *
 * Bahut halka, in-memory stats collector — khud is monitor ka kaam bhi UI thread
 * ko kabhi block nahi karta (sirf ek queue me number add karta hai).
 * DiagnosticsActivity (Settings ke andar) isko padh ke dikhata hai.
 */
object PerformanceMonitor {

    private val captureLatencies = ConcurrentLinkedQueue<Long>()
    private const val MAX_SAMPLES = 20

    fun recordCaptureLatency(ms: Long) {
        captureLatencies.add(ms)
        while (captureLatencies.size > MAX_SAMPLES) captureLatencies.poll()
    }

    fun averageCaptureLatencyMs(): Long {
        if (captureLatencies.isEmpty()) return -1
        return captureLatencies.sum() / captureLatencies.size
    }

    fun lastCaptureLatencyMs(): Long = captureLatencies.lastOrNull() ?: -1

    fun sampleCount(): Int = captureLatencies.size
}
