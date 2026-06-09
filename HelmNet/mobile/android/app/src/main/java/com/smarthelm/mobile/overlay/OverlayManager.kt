package com.smarthelm.mobile.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.smarthelm.mobile.R
import com.smarthelm.mobile.detection.DetectionResult
import com.smarthelm.mobile.detection.PerclosResult
import com.smarthelm.mobile.util.Prefs
import kotlin.math.roundToInt

/**
 * Manages the floating overlay widget drawn over other apps.
 *
 * Widget states:
 *   Idle (eyes open)  → 22dp green dot
 *   Caution (>15%)    → 22dp yellow dot
 *   Alert             → 240dp red pill with "DROWSY — PULL OVER"
 *   No face / UNKNOWN → 22dp grey dot
 *
 * Falls back to no-op on systems where SYSTEM_ALERT_WINDOW is denied
 * (DetectionService calls show() only after checking canDrawOverlays).
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler   = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing  = false
    private var isExpanded = false

    // Auto-collapse timer handle
    private val collapseRunnable = Runnable { collapse() }

    // ------------------------------------------------------------------
    // Public API — must be called on the main thread
    // ------------------------------------------------------------------

    fun show() {
        if (isShowing) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = Prefs.getOverlayX(context)
            y = Prefs.getOverlayY(context)
        }
        layoutParams = params

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_widget, null)
        overlayView = view

        setupTouchListener(view, params)
        windowManager.addView(view, params)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.removeCallbacks(collapseRunnable)
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        isShowing   = false
    }

    /**
     * Update widget appearance. Can be called from any thread — posts to main.
     */
    fun update(detection: DetectionResult, perclos: PerclosResult) {
        mainHandler.post { applyState(detection, perclos) }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun applyState(detection: DetectionResult, perclos: PerclosResult) {
        val view = overlayView ?: return
        val dot      = view.findViewById<View>(R.id.dot_view)
        val pill     = view.findViewById<View>(R.id.expanded_view)
        val pillText = view.findViewById<TextView>(R.id.pill_text)
        val pillSub  = view.findViewById<TextView>(R.id.pill_sub)

        if (perclos.alertActive) {
            if (!isExpanded) expand()
            dot.visibility  = View.GONE
            pill.visibility = View.VISIBLE
            pillText.text   = "DROWSY — PULL OVER"
            pillSub.text    = "PERCLOS ${perclos.perclos.roundToInt()}%"
        } else {
            if (isExpanded) collapse()
            dot.visibility  = View.VISIBLE
            pill.visibility = View.GONE

            val color = when {
                !detection.faceDetected        -> 0xFF808080.toInt()
                perclos.perclos >= 15f          -> 0xFFFFA500.toInt()
                detection.eyeState == "UNKNOWN" -> 0xFF808080.toInt()
                else                            -> 0xFF22CC44.toInt()
            }
            dot.setBackgroundColor(color)
        }
    }

    private fun expand() {
        isExpanded = true
        mainHandler.removeCallbacks(collapseRunnable)
    }

    private fun collapse() {
        isExpanded = false
        val view = overlayView ?: return
        view.findViewById<View>(R.id.dot_view).visibility     = View.VISIBLE
        view.findViewById<View>(R.id.expanded_view).visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0;  var initialY = 0
        var touchX   = 0f; var touchY   = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX   = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (dx * dx + dy * dy > 25) isDragging = true  // 5px threshold
                    if (isDragging) {
                        params.x = initialX - dx   // END gravity: subtract for RTL feel
                        params.y = initialY + dy
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Persist position
                        Prefs.setOverlayPosition(context, params.x, params.y)
                    } else {
                        // Tap: expand briefly to show stats
                        val pill = view.findViewById<View>(R.id.expanded_view)
                        if (!isExpanded && pill.visibility == View.GONE) {
                            view.findViewById<View>(R.id.dot_view).visibility = View.GONE
                            pill.visibility = View.VISIBLE
                            mainHandler.postDelayed(collapseRunnable, 3_000L)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
