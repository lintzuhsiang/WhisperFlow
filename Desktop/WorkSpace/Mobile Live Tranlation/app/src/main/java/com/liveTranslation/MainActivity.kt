package com.liveTranslation

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
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
 *   3. Let the user pick source / target languages via spinners.
 *   4. Start / stop [AudioCaptureService] with the obtained projection token.
 */
class MainActivity : AppCompatActivity() {

    // ──────────────────────────────────────────────────────────────────────
    // Views
    // ──────────────────────────────────────────────────────────────────────

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var cbShowOriginal: android.widget.CheckBox
    private lateinit var rgEngine: android.widget.RadioGroup
    private lateinit var etApiKey: android.widget.EditText

    private val sharedPrefs by lazy { getSharedPreferences("LiveTranslationPrefs", MODE_PRIVATE) }

    // ──────────────────────────────────────────────────────────────────────
    // Language data
    // ──────────────────────────────────────────────────────────────────────

    /** Display names shown in the source-language spinner (includes Auto-detect). */
    private val sourceLanguageNames = arrayOf(
        "Auto-detect",
        "English",
        "Japanese",
        "Chinese (Simplified)",
        "Chinese (Traditional)",
        "Korean",
        "Spanish",
        "French",
        "German",
        "Arabic",
        "Portuguese",
        "Russian",
        "Hindi"
    )

    /** BCP-47 codes matching [sourceLanguageNames] by index. */
    private val sourceLanguageCodes = arrayOf(
        "auto", "en", "ja", "zh", "zh-tw", "ko",
        "es", "fr", "de", "ar", "pt", "ru", "hi"
    )

    /** Display names for the target-language spinner (no Auto-detect). */
    private val targetLanguageNames = arrayOf(
        "English",
        "Japanese",
        "Chinese (Simplified)",
        "Chinese (Traditional)",
        "Korean",
        "Spanish",
        "French",
        "German",
        "Arabic",
        "Portuguese",
        "Russian",
        "Hindi"
    )

    /** BCP-47 codes matching [targetLanguageNames] by index. */
    private val targetLanguageCodes = arrayOf(
        "en", "ja", "zh", "zh-tw", "ko",
        "es", "fr", "de", "ar", "pt", "ru", "hi"
    )

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

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                projectionResultData = result.data
                Log.i(TAG, "MediaProjection token granted")
                startCaptureService()
            } else {
                Log.w(TAG, "Screen capture permission denied (resultCode=${result.resultCode})")
                Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_LONG).show()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Log.i(TAG, "Overlay permission granted")
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

        btnToggle       = findViewById(R.id.btnToggle)
        tvStatus        = findViewById(R.id.tvStatus)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
        cbShowOriginal    = findViewById(R.id.cbShowOriginal)
        rgEngine          = findViewById(R.id.rgEngine)
        etApiKey          = findViewById(R.id.etApiKey)

        // Restore saved API key
        etApiKey.setText(sharedPrefs.getString("API_KEY", ""))

        // Toggle API key visibility based on engine choice
        rgEngine.setOnCheckedChangeListener { _, checkedId ->
            etApiKey.visibility = if (checkedId == R.id.rbGpt) android.view.View.VISIBLE else android.view.View.GONE
        }

        setupSpinners()

        btnToggle.setOnClickListener {
            if (isServiceRunning) stopCaptureService() else checkPermissionsAndStart()
        }

        updateUI()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Spinner setup
    // ──────────────────────────────────────────────────────────────────────

    private fun setupSpinners() {
        // Use a custom layout so the text is always dark (#212121) regardless of
        // system theme — the stock simple_spinner_item can render white-on-white
        // inside a white card background.
        val sourceAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            sourceLanguageNames
        ).also { it.setDropDownViewResource(R.layout.spinner_item) }

        val targetAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            targetLanguageNames
        ).also { it.setDropDownViewResource(R.layout.spinner_item) }

        spinnerSourceLang.adapter = sourceAdapter
        spinnerTargetLang.adapter = targetAdapter

        // Defaults: Auto-detect source, English target
        spinnerSourceLang.setSelection(0)  // Auto-detect
        spinnerTargetLang.setSelection(0)  // English
    }

    /** Read the currently selected source-language BCP-47 code from the spinner. */
    private fun selectedSourceLang(): String =
        sourceLanguageCodes[spinnerSourceLang.selectedItemPosition]

    /** Read the currently selected target-language BCP-47 code from the spinner. */
    private fun selectedTargetLang(): String =
        targetLanguageCodes[spinnerTargetLang.selectedItemPosition]

    // ──────────────────────────────────────────────────────────────────────
    // Permission flow
    // ──────────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else if (projectionResultData == null) {
            requestScreenCapture()
        } else {
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

        val srcLang = selectedSourceLang()
        val tgtLang = selectedTargetLang()
        val showOriginal = cbShowOriginal.isChecked
        val useGpt = rgEngine.checkedRadioButtonId == R.id.rbGpt
        val apiKey = etApiKey.text.toString().trim()

        if (useGpt) {
            sharedPrefs.edit().putString("API_KEY", apiKey).apply()
        }

        Log.i(TAG, "Starting capture: $srcLang -> $tgtLang, showOriginal=$showOriginal, useGpt=$useGpt")

        val serviceIntent = AudioCaptureService.buildStartIntent(
            context              = this,
            projectionResultData = data,
            sourceLang           = srcLang,
            targetLang           = tgtLang,
            showOriginal         = showOriginal,
            useGpt               = useGpt,
            apiKey               = apiKey
        )

        startForegroundService(serviceIntent)
        isServiceRunning = true
        updateUI()

        Log.i(TAG, "AudioCaptureService started")
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, AudioCaptureService::class.java)
            .setAction(AudioCaptureService.ACTION_STOP)
        startService(stopIntent)

        isServiceRunning = false
        updateUI()
        Log.i(TAG, "AudioCaptureService stop requested")
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (isServiceRunning) {
            btnToggle.text = "Stop Translation"
            btnToggle.setBackgroundColor(0xFFD32F2F.toInt())   // Red
            tvStatus.text  = "Capturing & translating..."
            // Disable spinners while service is running
            spinnerSourceLang.isEnabled = false
            spinnerTargetLang.isEnabled = false
            rgEngine.isEnabled = false
            etApiKey.isEnabled = false
            for (i in 0 until rgEngine.childCount) rgEngine.getChildAt(i).isEnabled = false
        } else {
            btnToggle.text = "Start Translation"
            btnToggle.setBackgroundColor(0xFF00796B.toInt())   // Teal
            tvStatus.text  = "Idle"
            spinnerSourceLang.isEnabled = true
            spinnerTargetLang.isEnabled = true
            rgEngine.isEnabled = true
            etApiKey.isEnabled = true
            for (i in 0 until rgEngine.childCount) rgEngine.getChildAt(i).isEnabled = true
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
