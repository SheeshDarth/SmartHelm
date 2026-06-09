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
    val alertActive: Boolean
)
