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
 *   - Fleet code (6-char, from the manager's dashboard)
 *
 * The fleet code is looked up in /fleets/{code}; the manager phone and fleet
 * emergency contact stored there are cached locally and used for alert SMS.
 * The rider never types the manager's or emergency numbers directly.
 *
 * Re-openable from MainActivity settings so the rider can change fleets.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill from stored values (for re-edits after initial setup)
        binding.etName.setText(Prefs.getRiderName(this))
        binding.etFleetCode.setText(Prefs.getFleetCode(this))

        // Show verified phone (read-only — from Firebase Auth)
        val riderPhone = Prefs.getRiderPhone(this)
            .ifBlank { FirebaseAuth.getInstance().currentUser?.phoneNumber ?: "" }
        binding.etRiderPhone.setText(riderPhone)

        binding.btnGetStarted.setOnClickListener { onGetStarted() }
    }

    private fun onGetStarted() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val code = binding.etFleetCode.text?.toString()?.trim()?.uppercase() ?: ""

        if (name.isEmpty()) {
            binding.etName.error = "Your name is required"
            binding.etName.requestFocus()
            return
        }

        // Fleet code is optional — a rider can finish setup without one and add it later.
        if (code.isBlank()) {
            persistProfile(name, code = "", managerPhone = "", emergency = "")
            return
        }
        if (code.length != 6) {
            binding.etFleetCode.error = "The fleet code is 6 characters"
            binding.etFleetCode.requestFocus()
            return
        }

        setLoading(true)
        // Resolve the code → manager phone + fleet emergency contact
        db.collection("fleets").document(code).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    setLoading(false)
                    binding.etFleetCode.error = "Code not found — check with your manager"
                    binding.etFleetCode.requestFocus()
                    return@addOnSuccessListener
                }
                val managerPhone = snap.getString("managerPhone") ?: ""
                val emergency    = snap.getString("emergencyContact") ?: ""
                persistProfile(name, code, managerPhone, emergency)
            }
            .addOnFailureListener {
                setLoading(false)
                binding.etFleetCode.error = "Couldn't verify code — check your connection"
            }
    }

    private fun persistProfile(name: String, code: String, managerPhone: String, emergency: String) {
        setLoading(true)

        Prefs.setRiderName(this, name)
        Prefs.setFleetCode(this, code)
        Prefs.setFleetManagerPhone(this, managerPhone)
        Prefs.setEmergencyContact(this, emergency)
        Prefs.setSmsEnabled(this, managerPhone.isNotBlank() || emergency.isNotBlank())

        // Push profile to Firestore (non-blocking — proceed regardless of outcome)
        val deviceId   = Prefs.getDeviceId(this)
        val riderPhone = Prefs.getRiderPhone(this)
        db.collection("riders").document(deviceId)
            .set(
                hashMapOf<String, Any?>(
                    "riderName"         to name,
                    "riderPhone"        to riderPhone,
                    "managerId"         to code,
                    "fleetManagerPhone" to managerPhone,
                    "emergencyContact"  to emergency,
                    "connected"         to false,
                    "profileUpdatedAt"  to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnCompleteListener { finishSetup() }
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
