package com.smarthelm.mobile.util

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

object Prefs {

    private const val NAME = "smarthelm_prefs"

    // ── Device identity ──────────────────────────────────────────────

    fun getDeviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString("device_id", it) }
        }
    }

    // ── Rider profile ────────────────────────────────────────────────

    fun getRiderName(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("rider_name", "") ?: ""

    fun setRiderName(ctx: Context, name: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putString("rider_name", name) }

    /** Phone number from Firebase Auth (e.g. "+919876543210"). */
    fun getRiderPhone(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("rider_phone", "") ?: ""

    fun setRiderPhone(ctx: Context, phone: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putString("rider_phone", phone) }

    // ── Fleet manager ────────────────────────────────────────────────

    /** Fleet manager's phone — receives SMS on every alert. */
    fun getFleetManagerPhone(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("fleet_manager_phone", "") ?: ""

    fun setFleetManagerPhone(ctx: Context, phone: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putString("fleet_manager_phone", phone) }

    // ── Emergency contact ────────────────────────────────────────────

    fun getEmergencyContact(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("emergency_contact", "") ?: ""

    fun setEmergencyContact(ctx: Context, number: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putString("emergency_contact", number) }

    fun isSmsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("sms_enabled", false)

    fun setSmsEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putBoolean("sms_enabled", enabled) }

    // ── Setup / onboarding ───────────────────────────────────────────

    /** True once the user has completed the SetupActivity profile form. */
    fun isSetupComplete(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("setup_complete", false)

    fun setSetupComplete(ctx: Context, done: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putBoolean("setup_complete", done) }

    // ── EAR calibration ──────────────────────────────────────────────

    fun isCalibrated(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("calibrated", false)

    fun getEarOpenThreshold(ctx: Context): Float =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getFloat("ear_open", 0.25f)

    fun getEarClosedThreshold(ctx: Context): Float =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getFloat("ear_closed", 0.20f)

    fun setEarThresholds(ctx: Context, open: Float, closed: Float) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit {
            putFloat("ear_open", open)
            putFloat("ear_closed", closed)
            putBoolean("calibrated", true)
        }

    // ── Overlay position ─────────────────────────────────────────────

    fun getOverlayX(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("overlay_x", 16)

    fun getOverlayY(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("overlay_y", 100)

    fun setOverlayPosition(ctx: Context, x: Int, y: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit {
            putInt("overlay_x", x)
            putInt("overlay_y", y)
        }

    // ── Detection running state ───────────────────────────────────────

    fun isDetectionRunning(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("detection_running", false)

    fun setDetectionRunning(ctx: Context, running: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putBoolean("detection_running", running) }
}
