package com.smarthelm.mobile.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.util.concurrent.Executors

/**
 * Manages all alert output: a sustained alarm (sound + vibration) and SMS via Twilio.
 *
 * Alarm design (robust while another app — e.g. Google Maps — is in the foreground):
 *   - A looping MediaPlayer on the ALARM stream so it keeps buzzing the whole time the
 *     rider is drowsy and stops the instant they recover (proper alarm, not a one-shot).
 *   - Requests transient audio focus so the alarm ducks Maps' navigation voice and routes
 *     to the active output, including a Bluetooth helmet headset.
 *   - Raising the ALARM volume is isolated in its own try/catch: under Do Not Disturb /
 *     Driving Mode that call throws a SecurityException, and it must NOT abort playback.
 *     The ALARM stream still sounds under DND, so the alarm is heard regardless.
 *
 * SMS path:
 *   trigger() → (after 15 s sustained) sendViaTwilio() → Twilio Programmable SMS.
 *
 * trigger()/clear() are invoked from the single camera-analysis thread, so start/stop
 * ordering is deterministic; the actual audio work runs on a dedicated single-thread
 * executor so it never blocks frame analysis and start/stop can't race.
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG                  = "AlertManager"
        private const val SMS_INITIAL_DELAY_MS = 15_000L          // 15 s of sustained alert before first SMS
        private const val SMS_COOLDOWN_MS      = 5 * 60 * 1_000L  // then at most once every 5 min
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var alertActive  = false
    @Volatile private var lastSmsMs    = 0L
    @Volatile private var alertStartMs = 0L   // when the current alert episode began
    @Volatile private var alarmPlaying = false

    private val vibrator     = context.getSystemService(Vibrator::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var mediaPlayer:  MediaPlayer?      = null
    private var focusRequest: AudioFocusRequest? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun trigger() {
        alertActive = true
        startAlarm()

        // ── SMS via Twilio ────────────────────────────────────────────
        val now = SystemClock.elapsedRealtime()
        if (alertStartMs == 0L) alertStartMs = now
        val sinceStart = now - alertStartMs
        val sinceLast  = now - lastSmsMs
        // First SMS fires after 15 s of sustained alerting; repeats at most once every 5 min.
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
        stopAlarm()
    }

    fun isActive() = alertActive

    fun release() {
        stopAlarm()
        scope.cancel()
        audioExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    // Alarm — looping sound + vibration, audio-focus aware, DND-safe
    // ------------------------------------------------------------------

    private fun startAlarm() {
        if (alarmPlaying) return        // already buzzing — trigger() is called every frame
        alarmPlaying = true
        audioExecutor.execute {
            requestAlarmFocus()

            // Raising ALARM volume can throw SecurityException under Do Not Disturb /
            // Driving Mode. Isolate it — the alarm stream still plays, just not louder.
            try {
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't raise alarm volume (DND / driving mode?): ${e.message}")
            }

            // Looping alarm sound on the ALARM stream — sounds even on silent/DND.
            try {
                val afd = context.resources.openRawResourceFd(R.raw.alert)
                val mp  = MediaPlayer()
                mp.setAudioAttributes(alarmAttributes)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                Log.i(TAG, "Alarm started")
            } catch (e: Exception) {
                Log.e(TAG, "Alarm playback failed", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }

            // Repeating buzz (repeat from index 0) until stopAlarm() cancels it.
            try {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250), 0))
            } catch (e: Exception) {
                Log.w(TAG, "Vibrate failed: ${e.message}")
            }
        }
    }

    private fun stopAlarm() {
        if (!alarmPlaying) return
        alarmPlaying = false
        audioExecutor.execute {
            try {
                mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
            } catch (e: Exception) {
                Log.w(TAG, "Alarm stop failed: ${e.message}")
            }
            mediaPlayer = null
            try { vibrator?.cancel() } catch (_: Exception) {}
            abandonAlarmFocus()
        }
    }

    private fun requestAlarmFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes)
                .setOnAudioFocusChangeListener { /* alarms are insistent — ignore changes */ }
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    private fun abandonAlarmFocus() {
        try { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
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
}
