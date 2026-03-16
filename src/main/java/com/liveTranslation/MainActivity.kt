package com.liveTranslation

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.liveTranslation.service.AudioCaptureService

/**
 * Entry point of the app.
 *
 * Responsibilities:
 *   1. Request **SYSTEM_ALERT_WINDOW** (overlay) permission via Settings deep-link.
 *   2. Request the **MediaProjection** (screen-recording) token – displayed as the
 *      system "Start recording?" dialog.
 *   3. Start / stop [AudioCaptureService] with the obtained projection token.
 *
 * No microphone permission dialog is shown here because
 * [AudioPlaybackCaptureConfiguration] captures *internal* audio only and does
 * NOT require RECORD_AUDIO at runtime on API 29+.  If you want a debug mode
 * that captures the mic, add a runtime permission request for RECORD_AUDIO.
 */
class MainActivity : AppCompatActivity() {

    // ──────────────────────────────────────────────────────────────────────
    // Views
    // ──────────────────────────────────────────────────────────────────────

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    // ──────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────

    private var isServiceRunning = false

    // Last MediaProjection result data (survives re-permission requests)
    private var projectionResultData: Intent? = null

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // ──────────────────────────────────────────────────────────────────────
    // Activity Result Launchers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Launcher for the system "Allow screen recording?" dialog.
     *
     * On RESULT_OK we receive the one-time [Intent] that wraps the
     * [android.media.projection.MediaProjection] token.  We hold onto it and
     * pass it to [AudioCaptureService] when the user presses Start.
     */
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                projectionResultData = result.data
                Log.i(TAG, "MediaProjection token granted")
                startCaptureService()  // Immediately start after permission granted
            } else {
                Log.w(TAG, "Screen capture permission denied (resultCode=${result.resultCode})")
                Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Launcher for the overlay (SYSTEM_ALERT_WINDOW) settings screen.
     * After the user returns we re-check the permission.
     */
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Log.i(TAG, "Overlay permission granted")
                // Now ask for the screen-recording token
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Overlay permission required for subtitles", Toast.LENGTH_LONG).show()
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus  = findViewById(R.id.tvStatus)

        btnToggle.setOnClickListener {
            if (isServiceRunning) stopCaptureService() else checkPermissionsAndStart()
        }

        updateUI()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Permission flow
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Permission chain:
     *   SYSTEM_ALERT_WINDOW  →  MediaProjection dialog  →  start service
     */
    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            // Step 1: request overlay permission via Settings
            requestOverlayPermission()
        } else if (projectionResultData == null) {
            // Step 2: request MediaProjection token
            requestScreenCapture()
        } else {
            // All permissions already granted – go straight to service start
            startCaptureService()
        }
    }

    private fun requestOverlayPermission() {
        Log.i(TAG, "Requesting SYSTEM_ALERT_WINDOW")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
        Toast.makeText(this, "Please enable 'Appear on top' for this app", Toast.LENGTH_LONG).show()
    }

    private fun requestScreenCapture() {
        Log.i(TAG, "Requesting MediaProjection screen-capture intent")
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Service control
    // ──────────────────────────────────────────────────────────────────────

    private fun startCaptureService() {
        val data = projectionResultData ?: run {
            Log.e(TAG, "startCaptureService: projectionResultData is null")
            return
        }

        val serviceIntent = AudioCaptureService.buildStartIntent(
            context             = this,
            projectionResultData = data,
            sourceLang          = "ja",   // 🔧 TODO: wire to a language-picker UI
            targetLang          = "en"
        )

        startForegroundService(serviceIntent)
        isServiceRunning = true
        updateUI()

        Log.i(TAG, "AudioCaptureService started")
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, AudioCaptureService::class.java)
            .setAction(AudioCaptureService.ACTION_STOP)
        startService(stopIntent)   // The service calls stopSelf() upon receiving this

        isServiceRunning = false
        updateUI()
        Log.i(TAG, "AudioCaptureService stop requested")
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (isServiceRunning) {
            btnToggle.text = "⏹  Stop Translation"
            tvStatus.text  = "Status: Capturing & translating…"
        } else {
            btnToggle.text = "▶  Start Translation"
            tvStatus.text  = "Status: Idle"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
