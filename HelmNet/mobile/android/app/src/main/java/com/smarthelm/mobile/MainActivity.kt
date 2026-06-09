package com.smarthelm.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.smarthelm.mobile.databinding.ActivityMainBinding
import com.smarthelm.mobile.service.DetectionService
import com.smarthelm.mobile.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Permission launchers ─────────────────────────────────────────

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Camera permission is required")
        refreshPermissionChecklist()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionChecklist() }

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionChecklist() }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionChecklist() }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Auth gate: not logged in → LoginActivity ──────────────────
        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // ── Setup gate: profile not filled in → SetupActivity ─────────
        if (!Prefs.isSetupComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay permission — there is no callback when user grants it in Settings
        refreshPermissionChecklist()

        // Sync toggle state with actual service state
        binding.btnStart.text = if (Prefs.isDetectionRunning(this)) "Stop Detection" else "Start Detection"
    }

    // ------------------------------------------------------------------
    // UI setup
    // ------------------------------------------------------------------

    private fun setupUi() {
        // Rider name
        binding.etRiderName.setText(Prefs.getRiderName(this))

        // Emergency contact
        binding.etEmergencyContact.setText(Prefs.getEmergencyContact(this))

        // SMS toggle
        binding.switchSms.isChecked = Prefs.isSmsEnabled(this)
        binding.switchSms.setOnCheckedChangeListener { _, checked ->
            Prefs.setSmsEnabled(this, checked)
            binding.layoutEmergencyContact.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) requestSmsPermission()
        }
        binding.layoutEmergencyContact.visibility =
            if (Prefs.isSmsEnabled(this)) View.VISIBLE else View.GONE

        // Start / Stop
        binding.btnStart.setOnClickListener {
            savePendingInputs()
            if (Prefs.isDetectionRunning(this)) {
                stopDetection()
            } else {
                if (!allPermissionsGranted()) {
                    toast("Please grant all required permissions first")
                } else {
                    if (!Prefs.isCalibrated(this)) {
                        startActivity(Intent(this, CalibrationActivity::class.java))
                    } else {
                        startDetection()
                    }
                }
            }
        }

        // Individual permission fix buttons
        binding.btnFixCamera.setOnClickListener { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
        binding.btnFixOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        binding.btnFixBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        binding.btnFixNotif.setOnClickListener {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.btnFixLocation.setOnClickListener {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        // Re-calibrate
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        // Edit profile (name, fleet manager, emergency contact)
        binding.btnEditProfile.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // Sign out
        binding.btnSignOut.setOnClickListener {
            stopService(Intent(this, DetectionService::class.java))
            FirebaseAuth.getInstance().signOut()
            Prefs.setSetupComplete(this, false)
            Prefs.setDetectionRunning(this, false)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun savePendingInputs() {
        val name = binding.etRiderName.text?.toString()?.trim() ?: ""
        if (name.isNotEmpty()) Prefs.setRiderName(this, name)

        val contact = binding.etEmergencyContact.text?.toString()?.trim() ?: ""
        if (contact.isNotEmpty()) Prefs.setEmergencyContact(this, contact)
    }

    // ------------------------------------------------------------------
    // Permission checklist
    // ------------------------------------------------------------------

    private fun refreshPermissionChecklist() {
        val hasCam     = hasPermission(Manifest.permission.CAMERA)
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasBattery = (getSystemService(PowerManager::class.java))
            .isIgnoringBatteryOptimizations(packageName)
        val hasNotif   = hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val hasLoc     = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        setPermRow(binding.rowCamera,   hasCam,     binding.btnFixCamera)
        setPermRow(binding.rowOverlay,  hasOverlay, binding.btnFixOverlay)
        setPermRow(binding.rowBattery,  hasBattery, binding.btnFixBattery)
        setPermRow(binding.rowNotif,    hasNotif,   binding.btnFixNotif)
        setPermRow(binding.rowLocation, hasLoc,     binding.btnFixLocation)

        val allOk = hasCam && hasOverlay && hasBattery && hasNotif && hasLoc
        binding.btnStart.isEnabled = true   // always enabled; validation on tap
        binding.tvPermStatus.text  = if (allOk) "✓ All permissions granted" else "Some permissions missing"
        binding.tvPermStatus.setTextColor(
            if (allOk) ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else       ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
    }

    private fun setPermRow(row: View, granted: Boolean, fixBtn: View) {
        row.alpha  = if (granted) 1f else 0.5f
        fixBtn.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun allPermissionsGranted(): Boolean {
        return hasPermission(Manifest.permission.CAMERA) &&
               Settings.canDrawOverlays(this)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermission() {
        smsPermLauncher.launch(Manifest.permission.SEND_SMS)
    }

    // ------------------------------------------------------------------
    // Service control
    // ------------------------------------------------------------------

    private fun startDetection() {
        val intent = Intent(this, DetectionService::class.java)
        startForegroundService(intent)
        binding.btnStart.text = "Stop Detection"
        toast("Detection started")
    }

    private fun stopDetection() {
        stopService(Intent(this, DetectionService::class.java))
        binding.btnStart.text = "Start Detection"
        toast("Detection stopped")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
