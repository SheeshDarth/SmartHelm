package com.smarthelm.mobile.detection

import android.os.SystemClock

/**
 * Counts blinks and reports a per-minute blink rate.
 *
 * A blink is a CLOSED→OPEN transition whose closed phase was shorter than [MAX_BLINK_MS];
 * anything longer is a deliberate/drowsy closure handled by [PerclosTracker], not a blink.
 * Blink rate is reported for explainability and trend; it is deliberately NOT a strong alert
 * signal (rate alone is a noisy fatigue indicator).
 *
 * NOT thread-safe — own one instance per inference thread.
 */
class BlinkTracker {

    companion object {
        private const val MAX_BLINK_MS = 400L        // closures longer than this aren't blinks
        private const val WINDOW_MS    = 60_000L     // report rate over the last minute
    }

    private val blinkTimestamps = ArrayDeque<Long>()
    private var closedSinceMs: Long? = null
    private var prevClosed = false

    /** Feed one frame's eyeState. Returns the current blinks-per-minute. */
    fun update(eyeState: String, nowMs: Long = SystemClock.elapsedRealtime()): Int {
        val isClosed = eyeState == "CLOSED"

        if (isClosed && !prevClosed) {
            closedSinceMs = nowMs                    // closure began
        } else if (!isClosed && prevClosed) {
            val dur = closedSinceMs?.let { nowMs - it } ?: Long.MAX_VALUE
            if (dur in 1L..MAX_BLINK_MS) blinkTimestamps.addLast(nowMs)
            closedSinceMs = null
        }
        // UNKNOWN frames don't change the closed/open edge state
        if (eyeState != "UNKNOWN") prevClosed = isClosed

        prune(nowMs)
        return blinkTimestamps.size
    }

    fun reset() {
        blinkTimestamps.clear()
        closedSinceMs = null
        prevClosed = false
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (blinkTimestamps.isNotEmpty() && blinkTimestamps.first() < cutoff) {
            blinkTimestamps.removeFirst()
        }
    }
}
