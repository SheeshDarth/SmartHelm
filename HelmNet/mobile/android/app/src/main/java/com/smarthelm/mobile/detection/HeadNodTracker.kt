package com.smarthelm.mobile.detection

import android.os.SystemClock
import kotlin.math.abs

/**
 * Detects "nodding off" — a sustained head-pitch deviation from the rider's normal posture.
 *
 * It learns a slow rolling baseline (EMA) of the head-pitch proxy from [EyeDetector] while the
 * rider is upright, then flags when the current pitch deviates from that baseline by more than
 * [NOD_DELTA] for at least [NOD_MIN_MS]. Using deviation-from-baseline (not an absolute angle)
 * makes it robust to mounting angle and the proxy's unknown sign.
 *
 * The baseline only adapts when NOT nodding, so a genuine sustained nod can't be "learned away".
 *
 * NOT thread-safe — own one instance per inference thread.
 */
class HeadNodTracker {

    companion object {
        private const val BASELINE_ALPHA = 0.02f     // slow EMA — ~seconds to adapt
        private const val NOD_DELTA      = 0.18f     // proxy units of deviation = a real nod
        private const val NOD_MIN_MS     = 600L      // must persist this long to count
        private const val SATURATE_MS    = 2_500L    // nodScore reaches 1.0 after this long nodding
    }

    private var baseline: Float? = null
    private var nodSinceMs: Long? = null

    /** Feed one frame's head-pitch proxy. Returns the 0..1 nod contribution. */
    fun update(headPitch: Float, faceDetected: Boolean,
               nowMs: Long = SystemClock.elapsedRealtime()): Float {
        if (!faceDetected) { nodSinceMs = null; return 0f }

        val base = baseline ?: headPitch.also { baseline = it }
        val deviating = abs(headPitch - base) >= NOD_DELTA

        if (deviating) {
            if (nodSinceMs == null) nodSinceMs = nowMs
            // Hold the baseline while nodding so the deviation isn't absorbed.
        } else {
            nodSinceMs = null
            baseline = base + BASELINE_ALPHA * (headPitch - base)   // adapt only when upright
        }

        val held = nodSinceMs?.let { nowMs - it } ?: 0L
        return if (held >= NOD_MIN_MS) (held.toFloat() / SATURATE_MS).coerceIn(0f, 1f) else 0f
    }

    fun reset() {
        baseline = null
        nodSinceMs = null
    }
}
