package com.smarthelm.mobile.detection

data class DetectionResult(
    val eyeState: String,       // "OPEN", "CLOSED", "UNKNOWN"
    val confidence: Float,
    val earSmoothed: Float,
    val faceDetected: Boolean,
    // Normalized (0-1) MediaPipe eye landmark coords — 6 points each
    val leftEyeNorm:  List<Pair<Float, Float>> = emptyList(),
    val rightEyeNorm: List<Pair<Float, Float>> = emptyList()
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
