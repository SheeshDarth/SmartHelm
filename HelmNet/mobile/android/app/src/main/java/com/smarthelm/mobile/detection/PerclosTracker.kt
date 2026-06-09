package com.smarthelm.mobile.detection

import android.os.SystemClock

/**
 * PERCLOS tracker — exact Kotlin port of perclos.py.
 *
 * Two independent alert triggers:
 *   1. alert_perclos    — PERCLOS >= threshold over rolling window
 *   2. alert_continuous — eyes closed continuously >= threshold seconds
 *
 * NOT thread-safe — own one instance per inference thread.
 */
class PerclosTracker(
    private val windowSeconds: Int   = 60,
    private val perclosThreshold: Float = 30f,
    private val closureThreshold: Float = 0.8f,   // 1.5s→0.8s: alert sooner
    private val clearDelayMs: Long      = 800L     // 2s→0.8s: clear faster
) {

    private val window = ArrayDeque<Pair<Long, Boolean>>()  // (timestampMs, isClosed)

    private var closureStartMs: Long? = null
    private var continuousClosureSec: Float = 0f

    private var openSinceMs: Long? = null
    private var alertWasActive: Boolean = false

    /**
     * Call once per frame with the current eyeState.
     * Returns a PerclosResult describing the current drowsiness state.
     */
    fun update(eyeState: String, nowMs: Long = SystemClock.elapsedRealtime()): PerclosResult {
        val isClosed = eyeState == "CLOSED"
        window.addLast(Pair(nowMs, isClosed))
        pruneWindow(nowMs)
        updateClosureTimer(eyeState, nowMs)

        val total       = window.size
        val closedCount = window.count { it.second }
        val perclos     = if (total > 0) closedCount.toFloat() / total * 100f else 0f

        val alertPerclos    = perclos >= perclosThreshold
        val alertContinuous = continuousClosureSec >= closureThreshold

        // OR of both triggers, with auto-clear grace period
        var alertActive = alertPerclos || alertContinuous
        if (alertActive) {
            openSinceMs = null
            alertWasActive = true
        } else if (alertWasActive) {
            if (openSinceMs == null) openSinceMs = nowMs
            val elapsedOpen = nowMs - openSinceMs!!
            if (elapsedOpen >= clearDelayMs) {
                alertWasActive = false
                openSinceMs    = null
            } else {
                alertActive = true   // still within grace period
            }
        }

        return PerclosResult(
            perclos              = perclos,
            continuousClosureSec = continuousClosureSec,
            alertPerclos         = alertPerclos,
            alertContinuous      = alertContinuous,
            alertActive          = alertActive
        )
    }

    fun reset() {
        window.clear()
        closureStartMs       = null
        continuousClosureSec = 0f
        openSinceMs          = null
        alertWasActive       = false
    }

    // ------------------------------------------------------------------

    private fun pruneWindow(nowMs: Long) {
        val cutoff = nowMs - windowSeconds * 1_000L
        while (window.isNotEmpty() && window.first().first < cutoff) {
            window.removeFirst()
        }
    }

    /**
     * UNKNOWN frames do NOT reset the timer — conservative design that avoids
     * false clears during brief tracking loss (same as perclos.py).
     */
    private fun updateClosureTimer(eyeState: String, nowMs: Long) {
        when (eyeState) {
            "CLOSED" -> {
                if (closureStartMs == null) closureStartMs = nowMs
                continuousClosureSec = (nowMs - closureStartMs!!) / 1_000f
            }
            "OPEN" -> {
                closureStartMs       = null
                continuousClosureSec = 0f
            }
            // "UNKNOWN": leave timer unchanged
        }
    }
}
