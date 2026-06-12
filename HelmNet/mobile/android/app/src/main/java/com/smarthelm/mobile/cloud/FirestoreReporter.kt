package com.smarthelm.mobile.cloud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.smarthelm.mobile.detection.DetectionResult
import com.smarthelm.mobile.detection.FatigueResult
import com.smarthelm.mobile.detection.PerclosResult
import java.io.ByteArrayOutputStream

/**
 * Publishes rider status + annotated eye-region frames to Firestore.
 *
 * Video pipeline (privacy + clarity):
 *   1. Rotate full frame by imageProxy.rotationDegrees → upright face
 *   2. Transform landmark coords into the rotated frame's coordinate space
 *   3. Draw MediaPipe eye outlines + EAR lines on the upright frame
 *   4. Crop ONLY the eye strip (both eyes + padding) — no face, no body
 *   5. Scale to 180×72 at 55% JPEG quality → ~5–10 KB, crisp and private
 *   6. Push to Firestore at 350 ms normal / 100 ms alert
 */
class FirestoreReporter(
    private val db: FirebaseFirestore,
    private val deviceId: String,
    private val riderName: String,
    private val emergencyContact: String = "",
    private val managerId: String = ""
) {

    companion object {
        private const val TAG                = "FirestoreReporter"
        private const val THROTTLE_MS        = 2_000L
        private const val SNAPSHOT_NORMAL_MS = 350L    // ~3 fps
        private const val SNAPSHOT_ALERT_MS  = 100L    // ~10 fps
        private const val SNAPSHOT_W         = 180     // eye strip output width
        private const val SNAPSHOT_H         = 72      // eye strip output height
        private const val SNAPSHOT_QUALITY   = 55
        private const val NO_FACE_W          = 80      // fallback when no face detected
        private const val NO_FACE_H          = 60
        private const val NO_FACE_QUALITY    = 28
    }

    private var lastPushMs      = 0L
    private var lastSnapshotMs  = 0L
    private var prevAlertActive = false
    private var lastLocation: GeoPoint? = null
    private var lastSpeedKmph: Float = -1f   // -1 = unknown
    private var lastBearing: Float   = -1f

    // Trip / liveness state (F2 — app-off-mid-trip safety concern)
    private var tripId: String? = null
    private var tripActive = false
    private var appState = "FOREGROUND"      // FOREGROUND | BACKGROUND | ENDED

    // Reusable paints — allocated once
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.8f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val earPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 220, 0); strokeWidth = 1.6f; style = Paint.Style.STROKE
    }
    private val tintPaint = Paint()

    fun setLastLocation(lat: Double, lng: Double, speedKmph: Float = -1f, bearing: Float = -1f) {
        lastLocation  = GeoPoint(lat, lng)
        lastSpeedKmph = speedKmph
        lastBearing   = bearing
    }

    // ── Trip / heartbeat (F2) ──────────────────────────────────────────────────
    // The dashboard distinguishes a graceful trip end (appState=ENDED / connected=false)
    // from a rider going dark mid-trip (tripActive=true, appState!=ENDED, heartbeat stale).

    fun setAppState(state: String) { appState = state }

    fun startTrip(id: String) {
        tripId = id
        tripActive = true
        pushTrip(mapOf(
            "tripActive"     to true,
            "tripId"         to id,
            "tripStartedAt"  to FieldValue.serverTimestamp(),
            "appState"       to appState
        ))
    }

    /** Lightweight liveness ping — keeps lastHeartbeatAt fresh even when frames stall. */
    fun heartbeat() {
        if (!tripActive) return
        pushTrip(mapOf("tripActive" to true, "appState" to appState))
    }

    /** Graceful trip end — must NOT raise a safety concern on the dashboard. */
    fun endTrip() {
        tripActive = false
        appState = "ENDED"
        pushTrip(mapOf("tripActive" to false, "appState" to "ENDED"))
    }

    private fun pushTrip(extra: Map<String, Any?>) {
        val doc = HashMap<String, Any?>(extra)
        doc["connected"]       = true
        doc["lastHeartbeatAt"] = FieldValue.serverTimestamp()
        doc["updatedAt"]       = FieldValue.serverTimestamp()
        db.collection("riders").document(deviceId)
            .set(doc, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Trip push failed: ${e.message}") }
    }

    // ── Status push ──────────────────────────────────────────────────────────

    /**
     * @param fatigue the fused [FatigueResult]; when present its [FatigueResult.alertActive] is the
     *   authoritative alert (already debounced + speed-gated), so the dashboard and the on-device
     *   alarm agree. Falls back to [PerclosResult.alertActive] for older callers.
     */
    fun onFrame(det: DetectionResult, perc: PerclosResult, fatigue: FatigueResult? = null) {
        val now            = SystemClock.elapsedRealtime()
        val effectiveAlert = fatigue?.alertActive ?: perc.alertActive
        val alertEdge      = effectiveAlert != prevAlertActive
        prevAlertActive    = effectiveAlert

        if (!alertEdge && (now - lastPushMs) < THROTTLE_MS) return
        lastPushMs = now

        val leftFlat  = det.leftEyeNorm.flatMap  { listOf(it.first, it.second) }
        val rightFlat = det.rightEyeNorm.flatMap { listOf(it.first, it.second) }
        val alertType = fatigue?.cause?.ifBlank { perc.alertType } ?: perc.alertType

        val status = hashMapOf<String, Any?>(
            "riderName"            to riderName,
            "managerId"            to managerId,
            "emergencyContact"     to emergencyContact,
            "eyeState"             to det.eyeState,
            "perclos"              to perc.perclos,
            "continuousClosureSec" to perc.continuousClosureSec,
            "alertActive"          to effectiveAlert,
            "alertType"            to alertType,
            "faceDetected"         to det.faceDetected,
            "eyeLandmarksLeft"     to leftFlat,
            "eyeLandmarksRight"    to rightFlat,
            "location"             to lastLocation,
            "tripActive"           to tripActive,
            "appState"             to appState,
            "lastHeartbeatAt"      to FieldValue.serverTimestamp(),
            "connected"            to true,
            "updatedAt"            to FieldValue.serverTimestamp()
        )
        tripId?.let { status["tripId"] = it }
        if (lastSpeedKmph >= 0f) status["speedKmph"] = lastSpeedKmph
        if (lastBearing   >= 0f) status["heading"]   = lastBearing
        if (fatigue != null) {
            status["fatigueScore"]     = fatigue.score
            status["fatigueLevel"]     = fatigue.level
            status["fatigueBreakdown"] = fatigue.breakdown
            status["yawnCount"]        = fatigue.yawnCount
            status["blinkRate"]        = fatigue.blinkRate
        }
        if (alertEdge) status["smsNeeded"] = effectiveAlert

        db.collection("riders").document(deviceId)
            .set(status, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Status push failed: ${e.message}") }

        if (alertEdge && effectiveAlert) {
            val alertDoc = hashMapOf<String, Any?>(
                "type"          to alertType.ifBlank { if (perc.alertContinuous) "CONTINUOUS_CLOSURE" else "PERCLOS" },
                "perclos"       to perc.perclos,
                "continuousSec" to perc.continuousClosureSec,
                "fatigueScore"  to (fatigue?.score ?: 0f),
                "location"      to lastLocation,
                "timestamp"     to FieldValue.serverTimestamp()
            )
            db.collection("riders").document(deviceId).collection("alerts").add(alertDoc)
        }
    }

    // ── Snapshot pipeline ────────────────────────────────────────────────────

    fun maybeUpdateSnapshot(
        bitmap: Bitmap,
        rotationDegrees: Int,
        alertActive: Boolean,
        detection: DetectionResult
    ) {
        val now      = SystemClock.elapsedRealtime()
        val interval = if (alertActive) SNAPSHOT_ALERT_MS else SNAPSHOT_NORMAL_MS
        if ((now - lastSnapshotMs) < interval) return
        lastSnapshotMs = now

        try {
            // ── Step 1: Rotate to correct display orientation ────────────────
            val rotated = rotateFrame(bitmap, rotationDegrees)

            // ── Step 2: Transform landmark coords into rotated-frame space ───
            val leftRot  = transformLandmarks(detection.leftEyeNorm,  rotationDegrees)
            val rightRot = transformLandmarks(detection.rightEyeNorm, rotationDegrees)

            // ── Step 3: Draw inference overlay on upright frame ──────────────
            val annotated = rotated.copy(Bitmap.Config.ARGB_8888, true)
            rotated.recycle()
            if (detection.faceDetected && leftRot.size >= 6) {
                drawOverlay(annotated, leftRot, rightRot, detection.eyeState, alertActive)
            }

            // ── Step 4: Crop to eye-only strip (privacy) ─────────────────────
            val (outW, outH, quality) = if (detection.faceDetected && leftRot.size >= 6) {
                Triple(SNAPSHOT_W, SNAPSHOT_H, SNAPSHOT_QUALITY)
            } else {
                Triple(NO_FACE_W, NO_FACE_H, NO_FACE_QUALITY)
            }

            val cropped = if (detection.faceDetected && leftRot.size >= 6) {
                cropEyeStrip(annotated, leftRot, rightRot)
            } else {
                null
            }
            val source = cropped ?: annotated

            // ── Step 5: Scale + encode ───────────────────────────────────────
            val scaled = Bitmap.createScaledBitmap(source, outW, outH, true)
            if (cropped != null) { cropped.recycle(); annotated.recycle() }
            else                 { annotated.recycle() }

            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            scaled.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

            // ── Step 6: Push ─────────────────────────────────────────────────
            db.collection("riders").document(deviceId)
                .update(
                    "snapshot",          b64,
                    "snapshotUpdatedAt", FieldValue.serverTimestamp(),
                    "updatedAt",         FieldValue.serverTimestamp()
                )
                .addOnFailureListener { e -> Log.w(TAG, "Snapshot push failed: ${e.message}") }

        } catch (e: Exception) {
            Log.w(TAG, "Snapshot pipeline failed: ${e.message}")
        }
    }

    fun flush() {
        db.collection("riders").document(deviceId).update(
            mapOf("connected" to false, "smsNeeded" to false, "updatedAt" to FieldValue.serverTimestamp())
        )
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    private fun rotateFrame(bmp: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * Transform normalized (0–1) landmark coords from original-frame space
     * into the rotated-frame space. Required before cropping from the rotated bitmap.
     *
     *  0°   (nx, ny) → (nx, ny)
     *  90°  (nx, ny) → (1-ny, nx)      [90° CW]
     * 180°  (nx, ny) → (1-nx, 1-ny)
     * 270°  (nx, ny) → (ny,   1-nx)    [270° CW = 90° CCW]
     */
    private fun transformLandmarks(
        pts: List<Pair<Float, Float>>,
        deg: Int
    ): List<Pair<Float, Float>> = pts.map { (nx, ny) ->
        when (deg % 360) {
            90  -> Pair(1f - ny, nx)
            180 -> Pair(1f - nx, 1f - ny)
            270 -> Pair(ny, 1f - nx)
            else -> Pair(nx, ny)
        }
    }

    // ── Eye strip crop ────────────────────────────────────────────────────────

    private fun cropEyeStrip(
        bmp: Bitmap,
        leftEye: List<Pair<Float, Float>>,
        rightEye: List<Pair<Float, Float>>
    ): Bitmap {
        val all = leftEye + rightEye
        val bW  = bmp.width.toFloat()
        val bH  = bmp.height.toFloat()

        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }

        val spanX = (maxX - minX).coerceAtLeast(0.05f)
        val spanY = (maxY - minY).coerceAtLeast(0.015f)

        // Horizontal: 50% padding each side (capture full eye width + brow)
        // Vertical: 250% padding (eyes are tiny vertical strip; need forehead + cheek context)
        val padX = spanX * 0.50f
        val padY = spanY * 2.50f

        val left   = ((minX - padX) * bW).coerceIn(0f, bW - 1f).toInt()
        val top    = ((minY - padY) * bH).coerceIn(0f, bH - 1f).toInt()
        val right  = ((maxX + padX) * bW).coerceIn(left.toFloat() + 1f, bW).toInt()
        val bottom = ((maxY + padY) * bH).coerceIn(top.toFloat() + 1f, bH).toInt()

        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    // ── Inference overlay ─────────────────────────────────────────────────────

    private fun drawOverlay(
        bmp: Bitmap,
        leftEye: List<Pair<Float, Float>>,
        rightEye: List<Pair<Float, Float>>,
        eyeState: String,
        alertActive: Boolean
    ) {
        val canvas = Canvas(bmp)
        val W = bmp.width.toFloat(); val H = bmp.height.toFloat()

        val color = when (eyeState) {
            "OPEN"   -> Color.rgb(34, 204, 68)
            "CLOSED" -> Color.rgb(255, 68,  68)
            else     -> Color.rgb(120, 120, 120)
        }
        eyePaint.color = color; dotPaint.color = color

        listOf(leftEye, rightEye).forEach { pts ->
            if (pts.size < 6) return@forEach
            val path = Path()
            pts.forEachIndexed { i, (nx, ny) ->
                val px = nx * W; val py = ny * H
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close(); canvas.drawPath(path, eyePaint)
            pts.forEach { (nx, ny) -> canvas.drawCircle(nx * W, ny * H, 3.2f, dotPaint) }
        }

        // EAR vertical measurement lines
        listOf(leftEye, rightEye).forEach { pts ->
            if (pts.size >= 6) {
                canvas.drawLine(pts[1].first*W, pts[1].second*H, pts[5].first*W, pts[5].second*H, earPaint)
                canvas.drawLine(pts[2].first*W, pts[2].second*H, pts[4].first*W, pts[4].second*H, earPaint)
            }
        }

        if (alertActive) {
            tintPaint.color = Color.argb(40, 255, 68, 68)
            canvas.drawRect(0f, 0f, W, H, tintPaint)
        }
    }
}
