package com.smarthelm.mobile.detection

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.smarthelm.mobile.util.Prefs
import kotlin.math.hypot

/**
 * Wraps MediaPipe FaceLandmarker (Tasks API) for real-time eye-state detection.
 *
 * Mirror of the Python detector.py backend:
 *   - Same 468-point face_landmarker.task model
 *   - Same LEFT_EYE_INDICES / RIGHT_EYE_INDICES from config.py
 *   - Same EAR formula
 *   - RunningMode.VIDEO for temporal tracking (better than IMAGE per frame)
 *
 * NOT thread-safe — own one instance per inference thread.
 * Call release() in the owning component's onDestroy().
 */
class EyeDetector(private val context: Context) {

    companion object {
        private const val TAG = "EyeDetector"
        private const val MODEL_ASSET = "face_landmarker.task"

        // Identical to config.py LEFT_EYE_INDICES / RIGHT_EYE_INDICES
        val LEFT_EYE_INDICES  = intArrayOf(362, 385, 387, 263, 373, 380)
        val RIGHT_EYE_INDICES = intArrayOf(33,  160, 158, 133, 153, 144)

        private const val EAR_SMOOTHING_WINDOW = 3   // 5→3: faster EAR response
    }

    private val detector: FaceLandmarker
    private val earBuffer = ArrayDeque<Float>(EAR_SMOOTHING_WINDOW)

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.4f)   // lower → fewer missed frames
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setRunningMode(RunningMode.VIDEO)
            .build()
        detector = FaceLandmarker.createFromOptions(context, options)
        Log.i(TAG, "EyeDetector ready — model=$MODEL_ASSET")
    }

    /**
     * Process one frame in VIDEO mode.
     * @param image MPImage built from CameraX ImageProxy (no Bitmap allocation)
     * @param timestampMs monotonic timestamp in milliseconds
     */
    fun process(image: MPImage, timestampMs: Long): DetectionResult {
        val result = detector.detectForVideo(image, timestampMs)
        val faces = result.faceLandmarks()

        if (faces.isEmpty()) {
            return DetectionResult(
                eyeState = "UNKNOWN",
                confidence = 0f,
                earSmoothed = 0f,
                faceDetected = false
            )
        }

        val landmarks = faces[0]

        // EAR computation — normalize coordinates using image dimensions
        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()

        val earLeft  = computeEar(landmarks, LEFT_EYE_INDICES,  imgW, imgH)
        val earRight = computeEar(landmarks, RIGHT_EYE_INDICES, imgW, imgH)
        val earAvg   = (earLeft + earRight) / 2f

        // Rolling average smoothing (same as Python EAR_SMOOTHING_WINDOW = 5)
        if (earBuffer.size >= EAR_SMOOTHING_WINDOW) earBuffer.removeFirst()
        earBuffer.addLast(earAvg)
        val earSmoothed = earBuffer.average().toFloat()

        val (eyeState, confidence) = classifyEar(earSmoothed)

        // Extract normalized landmark coords (0-1) for dashboard inference overlay
        val leftEyeNorm  = LEFT_EYE_INDICES.map  { Pair(landmarks[it].x(), landmarks[it].y()) }
        val rightEyeNorm = RIGHT_EYE_INDICES.map { Pair(landmarks[it].x(), landmarks[it].y()) }

        return DetectionResult(
            eyeState     = eyeState,
            confidence   = confidence,
            earSmoothed  = earSmoothed,
            faceDetected = true,
            leftEyeNorm  = leftEyeNorm,
            rightEyeNorm = rightEyeNorm
        )
    }

    fun release() {
        detector.close()
        Log.i(TAG, "EyeDetector released")
    }

    // ------------------------------------------------------------------
    // EAR formula: (||P2-P6|| + ||P3-P5||) / (2 * ||P1-P4||)
    // Identical to detector.py _compute_ear()
    // ------------------------------------------------------------------

    private fun computeEar(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray,
        w: Float,
        h: Float
    ): Float {
        val pts = indices.map { Pair(landmarks[it].x() * w, landmarks[it].y() * h) }
        // Kotlin list destructuring only goes to component5(); use index access for 6 points
        val p1 = pts[0]; val p2 = pts[1]; val p3 = pts[2]
        val p4 = pts[3]; val p5 = pts[4]; val p6 = pts[5]
        return (dist(p2, p6) + dist(p3, p5)) / (2f * dist(p1, p4) + 1e-6f)
    }

    private fun classifyEar(ear: Float): Pair<String, Float> {
        val earOpen   = Prefs.getEarOpenThreshold(context)
        val earClosed = Prefs.getEarClosedThreshold(context)
        return when {
            ear > earOpen   -> {
                val conf = minOf(1f, 0.5f + (ear - earOpen) / 0.15f)
                Pair("OPEN", conf)
            }
            ear < earClosed -> {
                val conf = minOf(1f, 0.5f + (earClosed - ear) / 0.15f)
                Pair("CLOSED", conf)
            }
            else -> Pair("UNKNOWN", 0f)
        }
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) =
        hypot(a.first - b.first, a.second - b.second)
}
