package com.smarthelm.mobile.detection

import android.os.SystemClock

/**
 * Detects yawns from the mouth-aspect-ratio (MAR) and counts them over a rolling window.
 *
 * Robustness: a yawn is only registered when the mouth stays wide ([MAR_OPEN]) for at least
 * [YAWN_MIN_MS]. This rejects talking, laughing, and brief mouth movements. A single yawn never
 * raises an alert on its own — [FatigueScorer] only weights the *rate* of yawning over [WINDOW_MS].
 *
 * NOT thread-safe — own one instance per inference thread.
 */
class YawnTracker {

    companion object {
        private const val MAR_OPEN     = 0.55f       // MAR above this = mouth wide open
        private const val MAR_RESET    = 0.35f       // must drop below this before another yawn counts
        private const val YAWN_MIN_MS  = 500L        // sustained-open duration to qualify as a yawn
        private const val WINDOW_MS    = 120_000L    // count yawns over the last 2 minutes
        private const val SATURATE_AT  = 3f          // yawnScore reaches 1.0 at this many yawns/window
    }

    private val yawnTimestamps = ArrayDeque<Long>()
    private var openSinceMs: Long? = null
    private var armed = true   // false until MAR drops below MAR_RESET (debounce)

    /** Feed one frame's MAR. Returns true exactly on the frame a yawn is first registered. */
    fun update(mar: Float, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        var registered = false

        if (mar >= MAR_OPEN) {
            if (openSinceMs == null) openSinceMs = nowMs
            if (armed && (nowMs - openSinceMs!!) >= YAWN_MIN_MS) {
                yawnTimestamps.addLast(nowMs)
                armed = false
                registered = true
            }
        } else {
            openSinceMs = null
            if (mar < MAR_RESET) armed = true   // mouth closed — ready to count the next yawn
        }

        prune(nowMs)
        return registered
    }

    /** Number of yawns in the rolling window. */
    fun count(nowMs: Long = SystemClock.elapsedRealtime()): Int {
        prune(nowMs)
        return yawnTimestamps.size
    }

    /** 0..1 contribution to the fatigue score. */
    fun score(nowMs: Long = SystemClock.elapsedRealtime()): Float =
        (count(nowMs) / SATURATE_AT).coerceIn(0f, 1f)

    fun reset() {
        yawnTimestamps.clear()
        openSinceMs = null
        armed = true
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (yawnTimestamps.isNotEmpty() && yawnTimestamps.first() < cutoff) {
            yawnTimestamps.removeFirst()
        }
    }
}
