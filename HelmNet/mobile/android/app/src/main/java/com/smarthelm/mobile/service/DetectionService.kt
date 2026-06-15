package com.smarthelm.mobile.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.smarthelm.mobile.MainActivity
import com.smarthelm.mobile.R
import com.smarthelm.mobile.alert.AlertManager
import com.smarthelm.mobile.cloud.FirestoreReporter
import com.smarthelm.mobile.detection.EyeDetector
import com.smarthelm.mobile.detection.FatigueScorer
import com.smarthelm.mobile.detection.PerclosTracker
import com.smarthelm.mobile.overlay.OverlayManager
import com.smarthelm.mobile.util.Prefs
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Core background service.
 *
 * Extends LifecycleService — this is the LifecycleOwner that CameraX binds to,
 * so the camera unbinds automatically when the service is destroyed.
 *
 * Threading:
 *   - Main thread:  service lifecycle, overlay updates, location callbacks
 *   - CameraX executor (single thread): frame analysis
 *
 * Start order in onStartCommand (CRITICAL — crashes if reversed):
 *   1. startForeground()
 *   2. startCamera()
 *   3. startLocationUpdates()
 */
class DetectionService : LifecycleService() {

    companion object {
        const val CHANNEL_DETECTION = "smarthelm_detection"
        const val CHANNEL_ALERTS    = "smarthelm_alerts"
        private const val NOTIF_ID  = 1001
        private const val TAG       = "DetectionService"
        private const val HEARTBEAT_MS = 15_000L   // F2 liveness ping cadence
    }

    private lateinit var eyeDetector:    EyeDetector
    private lateinit var perclosTracker: PerclosTracker
    private lateinit var fatigueScorer:  FatigueScorer
    private lateinit var alertManager:   AlertManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var firestoreReporter: FirestoreReporter
    private lateinit var locationClient: FusedLocationProviderClient

    private val mainHandler     = Handler(Looper.getMainLooper())
    private val cameraExecutor  = Executors.newSingleThreadExecutor()

    // Thermal throttling — skip every 3rd frame under THERMAL_STATUS_SEVERE
    @Volatile private var thermalThrottle = false
    private var frameCounter = 0

    // Latest speed (km/h) from the location stream; -1 = unknown (no speed-gating)
    @Volatile private var currentSpeedKmph = -1f

    // Trip + heartbeat (F2). A unique trip id per service start; heartbeat keeps the rider
    // "alive" on the dashboard so a silent gap mid-trip is detectable as a safety concern.
    private val tripId = UUID.randomUUID().toString()
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            firestoreReporter.heartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { firestoreReporter.setAppState("FOREGROUND") }
        override fun onStop(owner: LifecycleOwner)  { firestoreReporter.setAppState("BACKGROUND") }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DetectionService onCreate")

        eyeDetector    = EyeDetector(this)
        perclosTracker = PerclosTracker()
        fatigueScorer  = FatigueScorer()
        alertManager   = AlertManager(this)
        overlayManager = OverlayManager(this)

        val deviceId         = Prefs.getDeviceId(this)
        val riderName        = Prefs.getRiderName(this)
        val emergencyContact = Prefs.getEmergencyContact(this)
        // The rider entered the fleet code directly in setup; it IS the managerId the
        // dashboard filters on. Manager phone + emergency contact were resolved from the
        // /fleets/{code} document at setup time and cached in Prefs.
        val managerId        = Prefs.getFleetCode(this)
        firestoreReporter = FirestoreReporter(
            FirebaseFirestore.getInstance(), deviceId, riderName, emergencyContact, managerId
        )

        locationClient = LocationServices.getFusedLocationProviderClient(this)

        // Show overlay only if permission granted; otherwise fall back to notification
        if (Settings.canDrawOverlays(this)) {
            overlayManager.show()
        }

        setupThermalMonitor()

        // Track whether the app is in the foreground so the dashboard can tell a graceful
        // background from a rider going dark mid-trip.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // CRITICAL: startForeground FIRST — crash on API 34+ if camera starts before this
        startForeground(NOTIF_ID, buildNotification())

        startCamera()
        startLocationUpdates()

        // Begin the trip + heartbeat (F2)
        firestoreReporter.startTrip(tripId)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)

        Prefs.setDetectionRunning(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "DetectionService onDestroy")
        // LifecycleService moves lifecycle to DESTROYED here — CameraX unbinds automatically
        super.onDestroy()

        cameraExecutor.shutdown()
        eyeDetector.release()
        alertManager.release()
        overlayManager.hide()

        try { locationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}

        // Graceful trip end — marks appState=ENDED so the dashboard does NOT flag a concern
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        try { ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver) } catch (_: Exception) {}
        firestoreReporter.endTrip()
        firestoreReporter.flush()

        Prefs.setDetectionRunning(this, false)
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cameraProvider = future.get()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(320, 240),   // 480x360→320x240: ~2× less pixels = lower latency
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )).build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

                cameraProvider.unbindAll()
                // Bind to THIS service's lifecycle — CameraX releases camera when service dies
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
                Log.i(TAG, "Camera bound to LifecycleService")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            // Skip frames when device is thermally throttling
            if (thermalThrottle && (++frameCounter % 3 != 0)) return

            val tsMs   = imageProxy.imageInfo.timestamp / 1_000_000L  // ns → ms
            val bitmap = imageProxy.toBitmap()                         // single decode
            val mpImage = BitmapImageBuilder(bitmap).build()

            val detection     = eyeDetector.process(mpImage, tsMs)
            val perclosResult = perclosTracker.update(detection.eyeState)
            // Fused, debounced, speed-gated verdict — the single source of truth for alerting
            val fatigue       = fatigueScorer.update(detection, perclosResult, currentSpeedKmph)

            if (fatigue.alertActive) alertManager.trigger() else alertManager.clear()

            // Overlay update must run on main thread
            mainHandler.post { overlayManager.update(detection, perclosResult) }

            // Firestore status push (smart-throttled internally) — fatigue is authoritative
            firestoreReporter.onFrame(detection, perclosResult, fatigue)

            // Live snapshot: annotated frame with landmarks, orientation-corrected
            firestoreReporter.maybeUpdateSnapshot(
                bitmap,
                imageProxy.imageInfo.rotationDegrees,
                fatigue.alertActive,
                detection
            )
            bitmap.recycle()   // safe: MediaPipe already consumed it synchronously

            // Update notification if overlay isn't showing (fallback mode)
            if (!Settings.canDrawOverlays(this)) {
                updateFallbackNotification(detection.eyeState, perclosResult.perclos, perclosResult.alertActive)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        } finally {
            imageProxy.close()   // CRITICAL — must always close to receive next frame
        }
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val speedKmph = if (loc.hasSpeed())   loc.speed * 3.6f else -1f
            val bearing   = if (loc.hasBearing()) loc.bearing      else -1f
            currentSpeedKmph = speedKmph
            firestoreReporter.setLastLocation(loc.latitude, loc.longitude, speedKmph, bearing)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        try {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Thermal monitoring (API 29+)
    // ------------------------------------------------------------------

    private fun setupThermalMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            pm?.addThermalStatusListener(ContextCompat.getMainExecutor(this)) { status ->
                thermalThrottle = status >= PowerManager.THERMAL_STATUS_SEVERE
                Log.d(TAG, "Thermal status=$status throttle=$thermalThrottle")
            }
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_DETECTION)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle("SmartHelm Active")
            .setContentText("Drowsiness detection running")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateFallbackNotification(eyeState: String, perclos: Float, alert: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = if (alert) CHANNEL_ALERTS else CHANNEL_DETECTION
        val title   = if (alert) "⚠️ DROWSINESS DETECTED" else "SmartHelm Active"
        val text    = "Eye: $eyeState | PERCLOS: ${"%.1f".format(perclos)}%"
        nm.notify(NOTIF_ID,
            NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_dot)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build()
        )
    }
}
