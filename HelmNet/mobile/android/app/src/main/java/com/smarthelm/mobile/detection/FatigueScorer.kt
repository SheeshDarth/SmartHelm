package com.smarthelm.mobile.detection

import android.os.SystemClock

/**
 * Fuses every drowsiness signal into a single 0-100 fatigue score and a debounced alert decision.
 *
 * This is the component that makes detection *robust* — it is deliberately hard to fool with a
 * single natural event:
 *
 *  - Eye closure is ramped: closures shorter than [CLOSE_RAMP_LOW] (a blink) contribute nothing;
 *    only sustained closure builds the score.
 *  - A squint (EAR between thresholds) is classified UNKNOWN upstream and never counts as closed.
 *  - A single yawn only nudges the score; [YawnTracker] weights the *rate* of yawning.
 *  - Head nods must persist (see [HeadNodTracker]) before they count.
 *  - Hysteresis (separate [ENTER]/[EXIT] thresholds) stops the alert flapping.
 *  - A confirmation window ([CONFIRM_MS]) means a one-frame spike never alarms.
 *  - Speed-gating suppresses alerts while the rider is stopped (e.g. waiting at a light).
 *
 * A genuine microsleep — continuous closure >= [MICROSLEEP_SEC] — bypasses the confirmation window
 * and always alarms (subject only to speed-gating), satisfying the safety requirement.
 *
 * NOT thread-safe — own one instance per inference thread.
 */
class FatigueScorer {

    companion object {
        // Eye-closure and PERCLOS are PRIMARY: either, when strong + sustained, can alert on its
        // own (the score takes the stronger of the two). Yawning and nodding are SECONDARY: they
        // only add a bonus on top, so a lone yawn or nod can reach CAUTION but never ALERT alone.
        private const val YAWN_BONUS = 0.25f
        private const val NOD_BONUS  = 0.20f

        // Closure ramp (seconds): below LOW = ignored (blink), full weight at HIGH
        private const val CLOSE_RAMP_LOW  = 0.40f
        private const val CLOSE_RAMP_HIGH = 1.50f

        // PERCLOS ramp (%): below LOW = ignored, full weight at HIGH
        private const val PERCLOS_RAMP_LOW  = 15f
        private const val PERCLOS_RAMP_HIGH = 35f

        // Decision thresholds (0-100) with hysteresis
        private const val ENTER  = 60f
        private const val EXIT   = 38f
        private const val CAUTION = 40f

        private const val CONFIRM_MS     = 700L     // score must hold above ENTER this long
        private const val MICROSLEEP_SEC = 1.5f     // continuous closure that always alarms
        private const val MIN_SPEED_KMPH = 5f       // below this = treated as stopped (speed-gated)
    }

    private val yawnTracker = YawnTracker()
    private val nodTracker  = HeadNodTracker()
    private val blinkTracker = BlinkTracker()

    private var active = false
    private var aboveSinceMs: Long? = null

    /**
     * @param speedKmph current speed in km/h, or a negative value if unknown (then no speed-gating).
     */
    fun update(
        det: DetectionResult,
        perc: PerclosResult,
        speedKmph: Float,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): FatigueResult {
        // ── Per-signal sub-scores (0..1) ──────────────────────────────────────
        val closureScore = ramp(perc.continuousClosureSec, CLOSE_RAMP_LOW, CLOSE_RAMP_HIGH)
        val perclosScore = ramp(perc.perclos, PERCLOS_RAMP_LOW, PERCLOS_RAMP_HIGH)
        yawnTracker.update(if (det.faceDetected) det.mar else 0f, nowMs)
        val yawnScore    = yawnTracker.score(nowMs)
        val nodScore     = nodTracker.update(det.headPitch, det.faceDetected, nowMs)
        val blinkRate    = blinkTracker.update(det.eyeState, nowMs)

        // Primary = stronger eye signal; secondary signals add a bounded bonus on top.
        val primary = maxOf(closureScore, perclosScore)
        val fused   = (primary + YAWN_BONUS * yawnScore + NOD_BONUS * nodScore).coerceIn(0f, 1f)
        val score   = fused * 100f

        // ── Decision: hysteresis + confirmation + microsleep override ─────────
        val microsleep = perc.continuousClosureSec >= MICROSLEEP_SEC
        val enterCond  = score >= ENTER || microsleep

        if (enterCond) {
            if (aboveSinceMs == null) aboveSinceMs = nowMs
        } else {
            aboveSinceMs = null
        }
        val confirmed = microsleep || (aboveSinceMs?.let { (nowMs - it) >= CONFIRM_MS } ?: false)

        if (!active && confirmed) active = true
        if (active && score < EXIT && !microsleep) active = false

        // ── Speed-gating ──────────────────────────────────────────────────────
        // negative speed = unknown (e.g. no GPS fix yet) = not gated, so the demo still alerts
        val speedGated = speedKmph >= 0f && speedKmph < MIN_SPEED_KMPH
        val alertActive = active && !speedGated

        // ── Dominant cause (for SMS body / dashboard) ─────────────────────────
        val cause = when {
            microsleep                                      -> "CONTINUOUS_CLOSURE"
            else -> listOf(
                "CONTINUOUS_CLOSURE" to closureScore,
                "PERCLOS"            to perclosScore,
                "YAWNING"            to YAWN_BONUS * yawnScore,
                "HEAD_NODDING"       to NOD_BONUS * nodScore
            ).maxByOrNull { it.second }?.takeIf { it.second > 0f }?.first ?: ""
        }

        val level = when {
            alertActive             -> "ALERT"
            score >= CAUTION        -> "CAUTION"
            else                    -> "OK"
        }

        return FatigueResult(
            score      = score,
            level      = level,
            alertActive = alertActive,
            cause      = cause,
            yawnCount  = yawnTracker.count(nowMs),
            blinkRate  = blinkRate,
            speedGated = speedGated && active,   // only meaningful when an alert was suppressed
            breakdown  = mapOf(
                "closure" to closureScore,
                "perclos" to perclosScore,
                "yawn"    to yawnScore,
                "nod"     to nodScore
            )
        )
    }

    fun reset() {
        yawnTracker.reset()
        nodTracker.reset()
        blinkTracker.reset()
        active = false
        aboveSinceMs = null
    }

    /** Linear ramp: 0 below [lo], 1 above [hi], proportional between. */
    private fun ramp(v: Float, lo: Float, hi: Float): Float =
        ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
}
