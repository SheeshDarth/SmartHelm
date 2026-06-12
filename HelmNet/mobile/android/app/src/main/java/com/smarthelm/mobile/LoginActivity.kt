package com.smarthelm.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.smarthelm.mobile.databinding.ActivityLoginBinding
import com.smarthelm.mobile.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phone number sign-in via Twilio OTP + Firebase Anonymous Auth.
 *
 * Flow:
 *   1. Enter phone number with country code (+91...)
 *   2. "Send OTP" → Twilio sends a 6-digit code via SMS
 *   3. Enter 6-digit code → verified locally → Firebase Anonymous session created
 *   4. Proceed to SetupActivity or MainActivity
 *
 * Trial account note: Twilio free trial can only send to numbers verified in the
 * Twilio console (twilio.com → Verified Caller IDs). Add the rider's number there
 * before the first login demo.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    private var pendingOtp: String? = null
    private var otpExpiresAt: Long  = 0L
    private var pendingPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // Already signed in — skip straight to app
        if (auth.currentUser != null) {
            proceed()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSendOtp.setOnClickListener { onSendOtpClicked() }
        binding.btnVerify.setOnClickListener  { onVerifyClicked() }
        binding.btnResend.setOnClickListener  { onResendClicked() }
    }

    // ------------------------------------------------------------------
    // Click handlers
    // ------------------------------------------------------------------

    private fun onSendOtpClicked() {
        val phone = binding.etPhone.text?.toString()?.trim() ?: ""
        if (phone.length < 10 || !phone.startsWith("+")) {
            showError("Enter your full number with country code, e.g. +919876543210")
            return
        }
        clearError()
        pendingPhone = phone
        sendOtpViaTwilio(phone)
    }

    private fun onVerifyClicked() {
        val code = binding.etOtp.text?.toString()?.trim() ?: ""
        if (code.length != 6) {
            showError("Enter the complete 6-digit code")
            return
        }
        if (System.currentTimeMillis() > otpExpiresAt) {
            showError("OTP expired — tap Resend")
            return
        }
        if (code != pendingOtp) {
            showError("Incorrect code — try again")
            return
        }
        clearError()
        setLoading(true)
        // OTP verified — create a Firebase Anonymous session for Firestore access
        auth.signInAnonymously()
            .addOnSuccessListener {
                Prefs.setRiderPhone(this, pendingPhone)
                setLoading(false)
                proceed()
            }
            .addOnFailureListener { e ->
                setLoading(false)
                showError("Session error: ${e.message}")
            }
    }

    private fun onResendClicked() {
        binding.layoutOtp.visibility   = View.GONE
        binding.layoutPhone.visibility = View.VISIBLE
        clearError()
        val phone = binding.etPhone.text?.toString()?.trim() ?: ""
        if (phone.isNotBlank()) {
            pendingPhone = phone
            sendOtpViaTwilio(phone)
        }
    }

    // ------------------------------------------------------------------
    // OTP send via Twilio
    // ------------------------------------------------------------------

    private fun sendOtpViaTwilio(phone: String) {
        val sid   = BuildConfig.TWILIO_ACCOUNT_SID
        val token = BuildConfig.TWILIO_AUTH_TOKEN
        val from  = BuildConfig.TWILIO_FROM_NUMBER
        if (sid.isBlank()) {
            showError("SMS not configured — contact support")
            return
        }

        val otp  = (100_000..999_999).random().toString()
        val body = "Your SmartHelm OTP is: $otp. Valid for 10 minutes."
        val to   = if (phone.startsWith("+")) phone else "+$phone"

        pendingOtp   = otp
        otpExpiresAt = System.currentTimeMillis() + 10 * 60 * 1_000L

        setLoading(true)
        Thread {
            val success = postTwilioSms(sid, token, from, to, body)
            runOnUiThread {
                setLoading(false)
                if (success) {
                    showOtpSection()
                    Toast.makeText(this, "OTP sent ✓", Toast.LENGTH_SHORT).show()
                } else {
                    showError("Failed to send OTP. Make sure your number is verified in Twilio console.")
                    pendingOtp = null
                }
            }
        }.start()
    }

    private fun postTwilioSms(
        sid: String, token: String, from: String, to: String, body: String
    ): Boolean {
        val url     = "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json"
        val creds   = Base64.encodeToString(
            "$sid:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val payload = "To=${URLEncoder.encode(to, "UTF-8")}" +
                      "&From=${URLEncoder.encode(from, "UTF-8")}" +
                      "&Body=${URLEncoder.encode(body, "UTF-8")}"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Basic $creds")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 10_000
                readTimeout    = 10_000
                doOutput       = true
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Twilio OTP failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private fun proceed() {
        startActivity(
            if (!Prefs.isSetupComplete(this)) Intent(this, SetupActivity::class.java)
            else Intent(this, MainActivity::class.java)
        )
        finish()
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun showOtpSection() {
        binding.layoutPhone.visibility = View.GONE
        binding.layoutOtp.visibility   = View.VISIBLE
        binding.tvOtpHint.text = "Code sent to $pendingPhone — check your messages"
        binding.etOtp.requestFocus()
    }

    private fun setLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSendOtp.isEnabled   = !show
        binding.btnVerify.isEnabled    = !show
        binding.btnResend.isEnabled    = !show
    }

    private fun showError(msg: String) {
        binding.tvError.text       = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun clearError() { binding.tvError.visibility = View.GONE }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
