package com.smarthelm.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.smarthelm.mobile.databinding.ActivitySetupBinding
import com.smarthelm.mobile.util.Prefs

/**
 * First-time profile setup:
 *   - Rider name
 *   - Fleet manager phone (receives SMS on every alert)
 *   - Emergency contact phone (family/friend, also alerted)
 *
 * Re-openable from MainActivity settings so rider can update contacts.
 * Saves locally to Prefs AND pushes the profile document to Firestore
 * so the fleet dashboard shows correct contact info.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill from stored values (for re-edits after initial setup)
        binding.etName.setText(Prefs.getRiderName(this))
        binding.etFleetManager.setText(Prefs.getFleetManagerPhone(this))
        binding.etEmergency.setText(Prefs.getEmergencyContact(this))

        // Show verified phone (read-only — from Firebase Auth)
        val riderPhone = Prefs.getRiderPhone(this)
            .ifBlank { FirebaseAuth.getInstance().currentUser?.phoneNumber ?: "" }
        binding.etRiderPhone.setText(riderPhone)

        binding.btnGetStarted.setOnClickListener { onGetStarted() }
    }

    private fun onGetStarted() {
        val name       = binding.etName.text?.toString()?.trim() ?: ""
        val fleetPhone = binding.etFleetManager.text?.toString()?.trim() ?: ""
        val emergency  = binding.etEmergency.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            binding.etName.error = "Your name is required"
            binding.etName.requestFocus()
            return
        }

        setLoading(true)

        // Persist locally
        Prefs.setRiderName(this, name)
        Prefs.setFleetManagerPhone(this, fleetPhone)
        Prefs.setEmergencyContact(this, emergency)
        // Enable SMS if either alert number is provided
        Prefs.setSmsEnabled(this, fleetPhone.isNotBlank() || emergency.isNotBlank())

        // Push profile to Firestore (non-blocking — proceed regardless of outcome)
        val deviceId   = Prefs.getDeviceId(this)
        val riderPhone = Prefs.getRiderPhone(this)
        FirebaseFirestore.getInstance()
            .collection("riders").document(deviceId)
            .set(
                hashMapOf<String, Any?>(
                    "riderName"         to name,
                    "riderPhone"        to riderPhone,
                    "fleetManagerPhone" to fleetPhone,
                    "emergencyContact"  to emergency,
                    "connected"         to false,
                    "profileUpdatedAt"  to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnCompleteListener {
                // Always proceed — Firestore push failure is non-fatal
                finishSetup()
            }
    }

    private fun finishSetup() {
        Prefs.setSetupComplete(this, true)
        setLoading(false)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoading(show: Boolean) {
        binding.progressBar.visibility  = if (show) View.VISIBLE else View.GONE
        binding.btnGetStarted.isEnabled = !show
    }
}
