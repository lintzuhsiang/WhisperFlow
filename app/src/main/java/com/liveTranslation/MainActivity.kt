package com.liveTranslation

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var tvHint: TextView
    private lateinit var headerSection: View
    private lateinit var settingsCard: View
    private lateinit var pipStatusContainer: View
    private lateinit var tvPipStatus: TextView
    private lateinit var tvPipHint: TextView
    private lateinit var tvPipOriginal: TextView
    private lateinit var tvPipTranslated: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var cbShowOriginal: android.widget.CheckBox
    private lateinit var rgEngine: android.widget.RadioGroup
    private lateinit var tilApiKey: TextInputLayout
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

    private enum class CaptureUiState { IDLE, LISTENING, DISCONNECTED }

    private var isServiceRunning = false
    private var captureUiState = CaptureUiState.IDLE

    // Last MediaProjection result data (survives re-permission requests)
    private var projectionResultData: Intent? = null

    // Set when user is sent to system notification settings; checked in onResume
    private var awaitingNotificationSettings = false

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var captureStateReceiverRegistered = false

    private val captureStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioCaptureService.ACTION_CAPTURE_STATE_CHANGED) return
            val state = intent.getStringExtra(AudioCaptureService.EXTRA_CAPTURE_STATE)
            captureUiState = mapCaptureUiState(state)
            isServiceRunning = AudioCaptureService.isRunning
            updateUI()
            updatePipParams()
        }
    }

    // Receives live subtitle updates from AudioCaptureService to populate the PiP caption view.
    private var subtitleReceiverRegistered = false

    private val subtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioCaptureService.ACTION_SUBTITLE_UPDATED) return
            val original   = intent.getStringExtra(AudioCaptureService.EXTRA_SUBTITLE_ORIGINAL) ?: ""
            val translated = intent.getStringExtra(AudioCaptureService.EXTRA_SUBTITLE_TRANSLATED) ?: ""
            updatePipCaption(original, translated)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Activity Result Launchers
    // ──────────────────────────────────────────────────────────────────────

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Audio permission granted")
                checkPermissionsAndStart()
            } else {
                Toast.makeText(this, "Microphone permission required for internal audio capture", Toast.LENGTH_LONG).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data!!

                // Samsung One UI 7 / Android 16 injects a "screen_share_type" extra even
                // when createConfigForDefaultDisplay() was used.  Known values:
                //   0 = entire screen (full display) — what we need
                //   1 = single app / App only       — projection dies when user leaves our app
                //  -1 = extra absent (non-Samsung or older Android) — assume entire screen
                val shareType = data.getIntExtra("screen_share_type", -1)
                Log.i(TAG, "DIAG | screen_share_type=$shareType (0=entire, 1=single-app, -1=absent)")

                if (shareType == 1) {
                    // User selected "App only" mode.  The MediaProjection will be revoked
                    // the instant they switch to another app, making live translation useless.
                    // Reject this token and guide the user to retry with "Entire screen".
                    Log.w(TAG, "Single-app mode detected — rejecting token, prompting user to retry")
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Please select 'Entire screen'")
                        .setMessage(
                            "You selected 'App only' mode.\n\n" +
                            "Translation will stop the moment you switch to another app.\n\n" +
                            "Tap OK and then select 'Entire screen' on the next permission dialog."
                        )
                        .setPositiveButton("OK") { _, _ -> requestScreenCapture() }
                        .setCancelable(false)
                        .show()
                    return@registerForActivityResult
                }

                projectionResultData = data
                Log.i(TAG, "MediaProjection token granted (shareType=$shareType)")
                startCaptureService()
            } else {
                Log.w(TAG, "Screen capture permission denied (resultCode=${result.resultCode})")
                Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted")
                checkPermissionsAndStart()
            } else {
                // POST_NOTIFICATIONS is critical: without it the FGS notification is
                // invisible and Android 14+ will revoke the MediaProjection the moment
                // the user switches apps.  Block the start flow until granted.
                Log.w(TAG, "POST_NOTIFICATIONS denied — cannot safely start capture")
                showNotificationPermissionDialog()
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

        btnToggle          = findViewById(R.id.btnToggle)
        tvStatus           = findViewById(R.id.tvStatus)
        tvHint             = findViewById(R.id.tvHint)
        headerSection      = findViewById(R.id.headerSection)
        settingsCard       = findViewById(R.id.settingsCard)
        pipStatusContainer = findViewById(R.id.pipStatusContainer)
        tvPipStatus        = findViewById(R.id.tvPipStatus)
        tvPipHint          = findViewById(R.id.tvPipHint)
        tvPipOriginal      = findViewById(R.id.tvPipOriginal)
        tvPipTranslated    = findViewById(R.id.tvPipTranslated)
        spinnerSourceLang  = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
        cbShowOriginal    = findViewById(R.id.cbShowOriginal)
        rgEngine          = findViewById(R.id.rgEngine)
        tilApiKey         = findViewById(R.id.tilApiKey)
        etApiKey          = findViewById(R.id.etApiKey)

        // Restore saved API key
        etApiKey.setText(sharedPrefs.getString("API_KEY", ""))

        // Show/hide the API key field (entire TextInputLayout) based on engine choice
        rgEngine.setOnCheckedChangeListener { _, checkedId ->
            tilApiKey.visibility = if (checkedId == R.id.rbGpt) android.view.View.VISIBLE else android.view.View.GONE
        }

        setupSpinners()

        btnToggle.setOnClickListener {
            if (isServiceRunning) stopCaptureService() else checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnOpenVideo).setOnClickListener {
            startActivity(Intent(this, VideoTranslationActivity::class.java))
        }

        registerCaptureStateReceiver()
        registerSubtitleReceiver()
        syncServiceStateFromCompanion()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        syncServiceStateFromCompanion()
        updateUI()
        updatePipParams()

        // If the service is NOT running, discard the stored projection token.
        // MediaProjection tokens are one-use: they cannot be re-used after the
        // service that owned them has stopped. Clearing the token here ensures
        // the next Start press triggers a fresh consent dialog instead of
        // silently failing with an expired token.
        if (!isServiceRunning) {
            projectionResultData = null
        }

        // If user returned from system notification settings after being prompted
        // by showNotificationPermissionDialog(), auto-retry the start flow.
        if (awaitingNotificationSettings) {
            awaitingNotificationSettings = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                checkPermissionsAndStart()
            } else {
                Toast.makeText(this, "Notification permission is still disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        unregisterCaptureStateReceiver()
        unregisterSubtitleReceiver()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isServiceRunning) {
            enterTranslationPip(reason = "userLeaveHint")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        updatePipLayout(isInPictureInPictureMode)
        updatePipParams()
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
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // API 33+: POST_NOTIFICATIONS is required for the foreground-service
            // notification to appear in the status bar.  Without it the FGS still
            // runs but the user sees no persistent icon — confusing and potentially
            // causing the system to kill the service sooner.
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (!Settings.canDrawOverlays(this)) {
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
        launchScreenCaptureIntent()
    }

    private fun launchScreenCaptureIntent() {
        // NOTE: No service pre-start (ACTION_PRIME) needed — the consent dialog
        // is launched from the Activity (not a service), so no foreground service
        // is required at this point. The service will be started AFTER the user
        // grants permission, and it will call startForeground() synchronously
        // before getMediaProjection() in its onStartCommand().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent(config))
        } else {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    /**
     * Shows a blocking dialog when POST_NOTIFICATIONS is denied.
     * Without the notification, Android 14+ treats the FGS as invisible and may
     * revoke the MediaProjection the moment the app leaves the foreground.
     */
    private fun showNotificationPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Notification Permission Required")
            .setMessage(
                "This app needs notification permission to keep the translation " +
                "service running in the background.\n\n" +
                "Without it, Android will stop the translation as soon as you " +
                "switch to another app."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                awaitingNotificationSettings = true
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
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
        captureUiState = CaptureUiState.LISTENING
        updateUI()
        updatePipParams()

        Log.i(TAG, "AudioCaptureService started")
        window.decorView.post { enterTranslationPip(reason = "autoAfterStart") }
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, AudioCaptureService::class.java)
            .setAction(AudioCaptureService.ACTION_STOP)
        startService(stopIntent)

        isServiceRunning = false
        captureUiState = CaptureUiState.IDLE
        updateUI()
        updatePipParams()
        Log.i(TAG, "AudioCaptureService stop requested")
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (isServiceRunning) {
            btnToggle.text = "Stop Translation"
            btnToggle.setBackgroundColor(0xFFD32F2F.toInt())   // Red
            tvStatus.text  = currentStatusText()
            // Disable controls while service is running
            spinnerSourceLang.isEnabled = false
            spinnerTargetLang.isEnabled = false
            rgEngine.isEnabled = false
            tilApiKey.isEnabled = false   // disables the eye icon + inner EditText together
            for (i in 0 until rgEngine.childCount) rgEngine.getChildAt(i).isEnabled = false
        } else {
            btnToggle.text = "Start Translation"
            btnToggle.setBackgroundColor(0xFF00796B.toInt())   // Teal
            tvStatus.text  = currentStatusText()
            spinnerSourceLang.isEnabled = true
            spinnerTargetLang.isEnabled = true
            rgEngine.isEnabled = true
            tilApiKey.isEnabled = true
            for (i in 0 until rgEngine.childCount) rgEngine.getChildAt(i).isEnabled = true
        }

        tvPipStatus.text = currentPipStatusText()
        tvPipHint.text = currentPipHintText()
        updatePipLayout(isInPictureInPictureMode)
    }

    private fun updatePipLayout(inPip: Boolean) {
        val regularUiVisible = !inPip
        headerSection.visibility      = if (regularUiVisible) View.VISIBLE else View.GONE
        settingsCard.visibility        = if (regularUiVisible) View.VISIBLE else View.GONE
        btnToggle.visibility           = if (regularUiVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnOpenVideo).visibility = if (regularUiVisible) View.VISIBLE else View.GONE
        tvHint.visibility              = if (regularUiVisible) View.VISIBLE else View.GONE
        pipStatusContainer.visibility  = if (inPip) View.VISIBLE else View.GONE

        if (inPip) {
            // Restore the latest known subtitle into the PiP view immediately so the
            // user sees content as soon as they enter PiP (instead of a blank screen).
            val orig = AudioCaptureService.latestOriginalText
            val trans = AudioCaptureService.latestTranslatedText
            if (trans.isNotBlank()) {
                updatePipCaption(orig, trans)
            } else {
                // No captions yet — show the status text
                tvPipTranslated.text = ""
                tvPipOriginal.visibility = View.GONE
                tvPipStatus.visibility = View.VISIBLE
                tvPipStatus.text = currentPipStatusText()
            }
        }
    }

    private fun currentStatusText(): String {
        return when {
            isServiceRunning -> getString(R.string.pip_status_listening)
            captureUiState == CaptureUiState.DISCONNECTED -> getString(R.string.pip_status_disconnected)
            else -> getString(R.string.pip_status_idle)
        }
    }

    private fun currentPipStatusText(): String {
        return when {
            isServiceRunning -> getString(R.string.pip_status_listening)
            captureUiState == CaptureUiState.DISCONNECTED -> getString(R.string.pip_status_disconnected)
            else -> getString(R.string.pip_status_idle)
        }
    }

    private fun currentPipHintText(): String {
        return getString(R.string.pip_hint_tap_to_return)
    }

    private fun mapCaptureUiState(raw: String?): CaptureUiState {
        return when (raw) {
            AudioCaptureService.CAPTURE_STATE_LISTENING -> CaptureUiState.LISTENING
            AudioCaptureService.CAPTURE_STATE_DISCONNECTED -> CaptureUiState.DISCONNECTED
            else -> CaptureUiState.IDLE
        }
    }

    private fun syncServiceStateFromCompanion() {
        isServiceRunning = AudioCaptureService.isRunning
        captureUiState = mapCaptureUiState(AudioCaptureService.latestCaptureState)
    }

    private fun registerCaptureStateReceiver() {
        if (captureStateReceiverRegistered) return
        val filter = IntentFilter(AudioCaptureService.ACTION_CAPTURE_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            captureStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        captureStateReceiverRegistered = true
    }

    private fun unregisterCaptureStateReceiver() {
        if (!captureStateReceiverRegistered) return
        unregisterReceiver(captureStateReceiver)
        captureStateReceiverRegistered = false
    }

    private fun registerSubtitleReceiver() {
        if (subtitleReceiverRegistered) return
        val filter = IntentFilter(AudioCaptureService.ACTION_SUBTITLE_UPDATED)
        ContextCompat.registerReceiver(
            this,
            subtitleReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        subtitleReceiverRegistered = true
    }

    private fun unregisterSubtitleReceiver() {
        if (!subtitleReceiverRegistered) return
        unregisterReceiver(subtitleReceiver)
        subtitleReceiverRegistered = false
    }

    /**
     * Updates the PiP caption view with the latest subtitle text.
     * tvPipOriginal is only shown when the user has "Show original" checked.
     * tvPipStatus is hidden once we have real caption content so it doesn't crowd the view.
     */
    private fun updatePipCaption(original: String, translated: String) {
        tvPipTranslated.text = translated
        val showOrig = cbShowOriginal.isChecked && original.isNotBlank()
        tvPipOriginal.visibility = if (showOrig) View.VISIBLE else View.GONE
        if (showOrig) tvPipOriginal.text = original
        // Hide the status dot once real captions start arriving
        tvPipStatus.visibility = if (translated.isNotBlank()) View.GONE else View.VISIBLE
    }

    private fun enterTranslationPip(reason: String) {
        if (!isPipSupported() || !isServiceRunning || isInPictureInPictureMode) return
        try {
            val params = buildPipParams()
            setPictureInPictureParams(params)
            val entered = enterPictureInPictureMode(params)
            Log.i(TAG, "enterPictureInPictureMode(reason=$reason, entered=$entered)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enter PiP (reason=$reason)", t)
        }
    }

    private fun updatePipParams() {
        if (!isPipSupported()) return
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update PiP params", t)
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val stopIntent = Intent(this, AudioCaptureService::class.java)
            .setAction(AudioCaptureService.ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            PIP_STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_stop),
            getString(R.string.pip_action_stop),
            getString(R.string.pip_action_stop),
            stopPendingIntent
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(stopAction))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setSubtitle(currentPipStatusText())
                }
            }
            .build()
    }

    private fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PIP_STOP_REQUEST_CODE = 3001
    }
}
