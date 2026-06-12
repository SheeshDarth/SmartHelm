package com.smarthelm.mobile.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Manages all alert output: audio beep burst, vibration, and SMS via Twilio.
 *
 * SMS path:
 *   trigger() → sendViaTwilio() → Twilio Programmable SMS REST API
 *   Sends to emergency contact + fleet manager phone when drowsiness is confirmed.
 *
 * Credentials come from BuildConfig, injected at build time
 * from local.properties (never committed to source control).
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG                  = "AlertManager"
        private const val BEEP_COOLDOWN_MS     = 1_500L
        private const val SMS_INITIAL_DELAY_MS = 15_000L          // 15 s of sustained alert before first SMS
        private const val SMS_COOLDOWN_MS      = 5 * 60 * 1_000L // then at most once every 5 min
        private const val BEEP_COUNT           = 4
        private const val BEEP_GAP_MS          = 50L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var alertActive  = false
    @Volatile private var lastBeepMs   = 0L
    @Volatile private var lastSmsMs    = 0L
    @Volatile private var alertStartMs = 0L   // when the current alert episode began
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

        // ── SMS via Twilio ────────────────────────────────────────────
        // Track when this alert episode began so we can apply the initial delay.
        if (alertStartMs == 0L) alertStartMs = now
        val sinceStart = now - alertStartMs
        val sinceLast  = now - lastSmsMs
        // First SMS fires after 15 s of sustained alerting; repeats at most every 5 min.
        if (sinceStart >= SMS_INITIAL_DELAY_MS && (lastSmsMs == 0L || sinceLast >= SMS_COOLDOWN_MS)) {
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
                scope.launch { sendViaTwilio(recipients.toList(), riderName) }
            }
        }
    }

    fun clear() {
        alertActive  = false
        alertStartMs = 0L   // reset so the next episode gets a fresh 15-second window
    }

    fun isActive() = alertActive

    fun release() {
        scope.cancel()
        soundPool.release()
    }

    // ------------------------------------------------------------------
    // Twilio Programmable SMS
    // ------------------------------------------------------------------

    private fun sendViaTwilio(numbers: List<String>, riderName: String) {
        val sid   = BuildConfig.TWILIO_ACCOUNT_SID
        val token = BuildConfig.TWILIO_AUTH_TOKEN
        val from  = BuildConfig.TWILIO_FROM_NUMBER
        if (sid.isBlank() || token.isBlank() || from.isBlank()) {
            Log.w(TAG, "Twilio credentials not set — SMS skipped")
            return
        }

        val message = "SmartHelm Alert: $riderName is showing signs of drowsiness or fatigue. " +
                      "Please check in immediately."
        val url     = "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json"
        val creds   = Base64.encodeToString(
            "$sid:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

        for (phone in numbers) {
            val to      = if (phone.startsWith("+")) phone else "+$phone"
            val payload = "To=${URLEncoder.encode(to, "UTF-8")}" +
                          "&From=${URLEncoder.encode(from, "UTF-8")}" +
                          "&Body=${URLEncoder.encode(message, "UTF-8")}"

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Basic $creds")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connectTimeout = 10_000
                    readTimeout    = 10_000
                    doOutput       = true
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code     = conn.responseCode
                val response = (if (code < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""

                if (code in 200..299) {
                    Log.i(TAG, "Twilio sent to $to → HTTP $code")
                } else {
                    Log.w(TAG, "Twilio failed: HTTP $code | body: $response | to: $to")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Twilio request failed to $to: ${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
            } finally {
                conn?.disconnect()
            }
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
