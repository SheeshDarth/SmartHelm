package com.smarthelm.mobile.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.smarthelm.mobile.BuildConfig
import com.smarthelm.mobile.R
import com.smarthelm.mobile.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages all alert output: audio beep burst, vibration, and SMS via MSG91.
 *
 * SMS path:
 *   trigger() → sendViaMSG91() → MSG91 transactional route 4
 *   Route 4 bypasses DND registration — delivered even to DND numbers.
 *
 * Auth key + sender ID come from BuildConfig, injected at build time
 * from local.properties (never committed to source control).
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG              = "AlertManager"
        private const val BEEP_COOLDOWN_MS = 1_500L
        private const val SMS_COOLDOWN_MS  = 5 * 60 * 1_000L
        private const val BEEP_COUNT       = 4
        private const val BEEP_GAP_MS      = 50L
        private const val MSG91_URL        = "https://control.msg91.com/api/v5/sms"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var alertActive  = false
    @Volatile private var lastBeepMs   = 0L
    @Volatile private var lastSmsMs    = 0L
    @Volatile private var beepInFlight = false

    private val soundPool: SoundPool
    private var soundId     = 0
    private var soundLoaded = false

    private val vibrator     = context.getSystemService(Vibrator::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    init {
        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttr)
            .build()
        soundPool.setOnLoadCompleteListener { _, _, status ->
            soundLoaded = (status == 0)
            Log.i(TAG, "SoundPool loaded: status=$status")
        }
        soundId = soundPool.load(context, R.raw.alert, 1)
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun trigger() {
        alertActive = true
        val now = SystemClock.elapsedRealtime()

        // ── Audio + vibration ─────────────────────────────────────────
        if (!beepInFlight && (now - lastBeepMs) >= BEEP_COOLDOWN_MS) {
            lastBeepMs   = now
            beepInFlight = true
            scope.launch { playBeepsAndVibrate() }
        }

        // ── SMS via MSG91 ─────────────────────────────────────────────
        if ((now - lastSmsMs) >= SMS_COOLDOWN_MS) {
            val emergency = Prefs.getEmergencyContact(context).trim()
            val fleetMgr  = Prefs.getFleetManagerPhone(context).trim()
            val recipients = buildSet {
                if (emergency.isNotBlank()) add(emergency)
                if (fleetMgr.isNotBlank())  add(fleetMgr)
            }
            if (recipients.isEmpty()) {
                Log.w(TAG, "SMS skipped: no recipients configured")
            } else {
                lastSmsMs = now
                val riderName = Prefs.getRiderName(context).ifBlank { "the rider" }
                scope.launch { sendViaMSG91(recipients.toList(), riderName) }
            }
        }
    }

    fun clear() { alertActive = false }

    fun isActive() = alertActive

    fun release() {
        scope.cancel()
        soundPool.release()
    }

    // ------------------------------------------------------------------
    // MSG91 transactional SMS
    // ------------------------------------------------------------------

    private fun sendViaMSG91(numbers: List<String>, riderName: String) {
        if (BuildConfig.MSG91_AUTH_KEY.isBlank()) {
            Log.w(TAG, "MSG91_AUTH_KEY not set — SMS skipped")
            return
        }

        // MSG91 expects numbers without leading + (e.g. 919876543210)
        val mobiles = numbers.map { it.trimStart('+') }

        val payload = JSONObject().apply {
            put("sender",  BuildConfig.MSG91_SENDER_ID)
            put("route",   "4")       // transactional — bypasses DND
            put("country", "91")
            put("sms", JSONArray().put(
                JSONObject().apply {
                    put("message",
                        "SmartHelm Alert: $riderName is showing signs of drowsiness. " +
                        "Please check in immediately.")
                    put("to", JSONArray(mobiles))
                }
            ))
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MSG91_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("authkey",      BuildConfig.MSG91_AUTH_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("accept",       "application/json")
                connectTimeout = 10_000
                readTimeout    = 10_000
                doOutput       = true
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code     = conn.responseCode
            val response = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""

            if (code in 200..299) {
                Log.i(TAG, "MSG91 SMS sent to ${mobiles.joinToString()} — response: $response")
            } else {
                Log.w(TAG, "MSG91 error $code: $response")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MSG91 request failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Audio + vibration
    // ------------------------------------------------------------------

    private fun playBeepsAndVibrate() {
        try {
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val pattern = longArrayOf(0, 200, 80, 200, 80, 200, 80, 200)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))

            repeat(BEEP_COUNT) { i ->
                if (soundLoaded) soundPool.play(soundId, 1f, 1f, 1, 0, 1.3f)
                else Log.w(TAG, "Sound not loaded — skipping beep $i")
                if (i < BEEP_COUNT - 1) Thread.sleep(200L + BEEP_GAP_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Beep/vibrate failed", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        } finally {
            beepInFlight = false
        }
    }
}
