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

        // Mouth-aspect-ratio (yawn): inner lip centre (vertical) over mouth corners (horizontal)
        private const val LIP_TOP    = 13    // upper inner lip
        private const val LIP_BOTTOM = 14    // lower inner lip
        private const val MOUTH_L    = 61    // left mouth corner
        private const val MOUTH_R    = 291   // right mouth corner

        // Head-pitch proxy landmarks (nod): nose tip vs eye line, scaled by eye span
        private const val NOSE_TIP   = 1
        private const val EYE_OUT_L  = 33
        private const val EYE_OUT_R  = 263

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

        // Yawn signal: mouth-aspect-ratio in pixel space (scale-correct for non-square frames)
        val mar = computeMar(landmarks, imgW, imgH)

        // Nod signal: nose tip vertical offset from the eye line, scaled by eye span.
        // A pure-landmark proxy (no 3D matrix); HeadNodTracker uses it relative to a baseline.
        val headPitch = computeHeadPitch(landmarks, imgW, imgH)

        return DetectionResult(
            eyeState     = eyeState,
            confidence   = confidence,
            earSmoothed  = earSmoothed,
            faceDetected = true,
            leftEyeNorm  = leftEyeNorm,
            rightEyeNorm = rightEyeNorm,
            mar          = mar,
            headPitch    = headPitch
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

    // ------------------------------------------------------------------
    // Yawn — mouth aspect ratio = vertical lip gap / horizontal mouth width
    // ~0.05 closed · ~0.3 talking · >0.5 yawn
    // ------------------------------------------------------------------

    private fun computeMar(
        landmarks: List<NormalizedLandmark>,
        w: Float,
        h: Float
    ): Float {
        val top    = Pair(landmarks[LIP_TOP].x() * w,    landmarks[LIP_TOP].y() * h)
        val bottom = Pair(landmarks[LIP_BOTTOM].x() * w, landmarks[LIP_BOTTOM].y() * h)
        val left   = Pair(landmarks[MOUTH_L].x() * w,    landmarks[MOUTH_L].y() * h)
        val right  = Pair(landmarks[MOUTH_R].x() * w,    landmarks[MOUTH_R].y() * h)
        return dist(top, bottom) / (dist(left, right) + 1e-6f)
    }

    // ------------------------------------------------------------------
    // Head pitch proxy — nose-tip vertical offset below the eye line,
    // normalized by the outer-eye span (scale + distance invariant).
    // Sign/absolute value isn't trusted; HeadNodTracker compares to a baseline.
    // ------------------------------------------------------------------

    private fun computeHeadPitch(
        landmarks: List<NormalizedLandmark>,
        w: Float,
        h: Float
    ): Float {
        val noseY    = landmarks[NOSE_TIP].y() * h
        val eyeLineY = (landmarks[EYE_OUT_L].y() + landmarks[EYE_OUT_R].y()) / 2f * h
        val eyeSpan  = hypot(
            (landmarks[EYE_OUT_R].x() - landmarks[EYE_OUT_L].x()) * w,
            (landmarks[EYE_OUT_R].y() - landmarks[EYE_OUT_L].y()) * h
        )
        return (noseY - eyeLineY) / (eyeSpan + 1e-6f)
    }
}
