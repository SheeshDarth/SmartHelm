package com.smarthelm.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.smarthelm.mobile.databinding.ActivityLoginBinding
import com.smarthelm.mobile.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Phone number sign-in via Firebase Phone Auth (OTP).
 *
 * Flow:
 *   1. Enter phone number with country code (+91...)
 *   2. "Send OTP" → Firebase sends SMS
 *   3. Enter 6-digit code → verified → proceed to SetupActivity or MainActivity
 *
 * Firebase setup required (one-time, in Firebase Console):
 *   - Authentication → Sign-in methods → Phone → Enable
 *   - Project settings → Android app → Add debug SHA-1 fingerprint
 *     (run: keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android)
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

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
        sendOtp(phone)
    }

    private fun onVerifyClicked() {
        val code = binding.etOtp.text?.toString()?.trim() ?: ""
        if (code.length != 6) {
            showError("Enter the complete 6-digit code")
            return
        }
        val id = verificationId
        if (id == null) {
            showError("Session expired — tap 'Resend OTP'")
            return
        }
        clearError()
        setLoading(true)
        signInWithCredential(PhoneAuthProvider.getCredential(id, code))
    }

    private fun onResendClicked() {
        // Go back to phone input so user can edit or resend
        binding.layoutOtp.visibility   = View.GONE
        binding.layoutPhone.visibility = View.VISIBLE
        clearError()
        val phone = binding.etPhone.text?.toString()?.trim() ?: ""
        if (phone.isNotBlank()) {
            sendOtp(phone, resendToken)
        }
    }

    // ------------------------------------------------------------------
    // OTP send / verify
    // ------------------------------------------------------------------

    private fun sendOtp(phone: String, token: PhoneAuthProvider.ForceResendingToken? = null) {
        setLoading(true)
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(verificationCallbacks)
        if (token != null) builder.setForceResendingToken(token)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    private val verificationCallbacks =
        object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            /** Instant verification (rare on real devices) — skip OTP screen. */
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                setLoading(false)
                showError("Failed to send OTP: ${e.message}")
            }

            override fun onCodeSent(
                verId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                verificationId = verId
                resendToken    = token
                setLoading(false)
                showOtpSection()
                Toast.makeText(this@LoginActivity, "OTP sent ✓", Toast.LENGTH_SHORT).show()
            }
        }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        setLoading(true)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                setLoading(false)
                if (task.isSuccessful) {
                    // Persist phone number locally for profile display
                    Prefs.setRiderPhone(this, auth.currentUser?.phoneNumber ?: "")
                    proceed()
                } else {
                    showError("Verification failed: ${task.exception?.message}")
                }
            }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private fun proceed() {
        val next = if (!Prefs.isSetupComplete(this)) {
            Intent(this, SetupActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun showOtpSection() {
        binding.layoutPhone.visibility = View.GONE
        binding.layoutOtp.visibility   = View.VISIBLE
        val phone = binding.etPhone.text?.toString() ?: ""
        binding.tvOtpHint.text = "Code sent to $phone — check your messages"
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

    private fun clearError() {
        binding.tvError.visibility = View.GONE
    }
}
