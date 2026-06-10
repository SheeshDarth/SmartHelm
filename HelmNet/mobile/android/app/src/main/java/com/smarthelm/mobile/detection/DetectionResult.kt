package com.smarthelm.mobile.detection

data class DetectionResult(
    val eyeState: String,       // "OPEN", "CLOSED", "UNKNOWN"
    val confidence: Float,
    val earSmoothed: Float,
    val faceDetected: Boolean,
    // Normalized (0-1) MediaPipe eye landmark coords — 6 points each
    val leftEyeNorm:  List<Pair<Float, Float>> = emptyList(),
    val rightEyeNorm: List<Pair<Float, Float>> = emptyList(),
    // Mouth-aspect-ratio (yawn signal): ~0 closed, ~0.3 talking, >0.5 yawn
    val mar: Float = 0f,
    // Head pitch in degrees from MediaPipe facial transform matrix (nod signal).
    // Used as a relative signal vs a rolling baseline — absolute sign is not relied on.
    val headPitch: Float = 0f
)

data class PerclosResult(
    val perclos: Float,
    val continuousClosureSec: Float,
    val alertPerclos: Boolean,
    val alertContinuous: Boolean,
    val alertActive: Boolean,
    /**
     * Human-readable cause of the current alert, used in SMS body and alert logs.
     * "PERCLOS"            — rolling PERCLOS score exceeded the threshold.
     * "CONTINUOUS_CLOSURE" — sustained eye closure exceeded the duration threshold.
     * ""                   — no active alert.
     */
    val alertType: String = when {
        alertContinuous -> "CONTINUOUS_CLOSURE"
        alertPerclos    -> "PERCLOS"
        else            -> ""
    }
)

/**
 * Output of [FatigueScorer] — the fused, debounced drowsiness verdict.
 *
 * [alertActive] is the single source of truth for whether to alarm: it already
 * applies hysteresis, a confirmation window, and speed-gating, so a lone blink,
 * a single yawn, or a squint will NOT set it true.
 */
data class FatigueResult(
    val score: Float,            // 0-100 fused fatigue score
    val level: String,           // "OK" | "CAUTION" | "ALERT"
    val alertActive: Boolean,    // confirmed, debounced, speed-gated
    val cause: String,           // dominant contributor, e.g. "CONTINUOUS_CLOSURE", "YAWNING"
    val yawnCount: Int,          // yawns in the rolling window
    val blinkRate: Int,          // blinks per minute
    val speedGated: Boolean,     // true = would-be alert suppressed because rider is stopped
    // Per-signal contribution (0-1) for dashboard explainability
    val breakdown: Map<String, Float> = emptyMap()
)
