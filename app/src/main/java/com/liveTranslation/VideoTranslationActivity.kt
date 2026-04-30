package com.liveTranslation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.PlayerView
import com.liveTranslation.ai.GptTranslationEngine
import com.liveTranslation.ai.LocalNllbEngine
import com.liveTranslation.ai.OpenAITranscriptionEngine
import com.liveTranslation.ai.SpeechToTextEngine
import com.liveTranslation.ai.TranslationEngine
import com.liveTranslation.ai.WhisperCppEngine
import com.liveTranslation.player.DualTrackPlayer
import com.liveTranslation.player.SubtitleStore
import com.liveTranslation.player.YouTubeStreamResolver
import kotlinx.coroutines.*

/**
 * Video Translation Activity — dual-track player.
 *
 * Plays a video file or HTTP URL with real-time ASR + translation subtitles.
 * An invisible audio decoder runs 3 seconds ahead of the visible playback,
 * so subtitles are pre-computed and ready when each scene is displayed.
 */
class VideoTranslationActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private lateinit var subtitleContainer: View
    private lateinit var tvOriginal: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var subtitleDivider: View
    private lateinit var etVideoUrl: EditText
    private lateinit var btnLoadUrl: Button
    private lateinit var btnPickFile: Button
    private lateinit var tvDecoderStatus: TextView
    private lateinit var spinnerSrcLang: Spinner
    private lateinit var spinnerTgtLang: Spinner
    private lateinit var cbShowOriginal: CheckBox
    private lateinit var topControlsOverlay: View
    private lateinit var emptyState: View

    // ── AI Engines ───────────────────────────────────────────────────────
    //
    // Both fields are typed by the abstract interface — concrete impl
    // (whisper.cpp vs OpenAI Whisper API, NLLB vs GPT) is chosen at init
    // time based on BuildConfig flags read from local.properties.
    //
    // Why cloud as default? Local whisper-medium can't run real-time on
    // Snapdragon 8 Elite. The dual-track player's lookahead absorbs the
    // ~1-2s API call latency so users perceive realtime subtitles.
    private var sttEngine: SpeechToTextEngine? = null
    private var translationEngine: TranslationEngine? = null
    private var dualTrackPlayer: DualTrackPlayer? = null
    private val initScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var resumePlaybackOnStart = false
    private var enginesLoading = false   // prevents duplicate initEngines() calls

    // ── Language data (same as MainActivity) ─────────────────────────────
    private val sourceLanguageNames = arrayOf(
        "Auto-detect", "English", "Japanese",
        "Chinese (Simplified)", "Chinese (Traditional)", "Korean",
        "Spanish", "French", "German", "Arabic", "Portuguese", "Russian", "Hindi"
    )
    private val sourceLanguageCodes = arrayOf(
        "auto", "en", "ja", "zh", "zh-tw", "ko",
        "es", "fr", "de", "ar", "pt", "ru", "hi"
    )
    private val targetLanguageNames = arrayOf(
        "English", "Japanese",
        "Chinese (Simplified)", "Chinese (Traditional)", "Korean",
        "Spanish", "French", "German", "Arabic", "Portuguese", "Russian", "Hindi"
    )
    private val targetLanguageCodes = arrayOf(
        "en", "ja", "zh", "zh-tw", "ko",
        "es", "fr", "de", "ar", "pt", "ru", "hi"
    )

    // ── Status update handler ────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            val player = dualTrackPlayer ?: return
            val pos = player.exoPlayer.currentPosition / 1000
            val dur = player.exoPlayer.duration.let { if (it > 0) it / 1000 else 0 }
            val storeSize = player.subtitleStore.size()

            // Language-lock badge: show 🔍 while still detecting, 🌐 once locked.
            // This gives users immediate feedback that auto-detect is running
            // and surfaces the detected language as soon as it commits.
            val locked = player.lockedSourceLang
            val langBadge = when {
                locked == null           -> "\uD83D\uDD0D detecting"
                locked == "auto"         -> "\uD83C\uDF10 auto (low conf)"
                else -> {
                    val confPct = (player.lockedSourceConfidence * 100).toInt()
                    "\uD83C\uDF10 ${locked.uppercase()} ${confPct}%"
                }
            }

            tvDecoderStatus.text = "$langBadge  \uD83D\uDCDD $storeSize  \u23F1 ${pos}s/${dur}s"
            handler.postDelayed(this, 1000)
        }
    }

    // ── File picker ──────────────────────────────────────────────────────
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Take persistable read permission so the URI survives process death
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (se: SecurityException) {
                    Log.w(TAG, "Provider does not support persistable URI permission", se)
                }
                loadVideo(uri)
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_translation)

        playerView         = findViewById(R.id.playerView)
        subtitleContainer  = findViewById(R.id.subtitleContainer)
        tvOriginal         = findViewById(R.id.tvOriginal)
        tvTranslated       = findViewById(R.id.tvTranslated)
        subtitleDivider    = findViewById(R.id.subtitleDivider)
        etVideoUrl         = findViewById(R.id.etVideoUrl)
        btnLoadUrl         = findViewById(R.id.btnLoadUrl)
        btnPickFile        = findViewById(R.id.btnPickFile)
        tvDecoderStatus    = findViewById(R.id.tvDecoderStatus)
        spinnerSrcLang     = findViewById(R.id.spinnerSrcLang)
        spinnerTgtLang     = findViewById(R.id.spinnerTgtLang)
        cbShowOriginal     = findViewById(R.id.cbShowOriginal)
        topControlsOverlay = findViewById(R.id.topControlsOverlay)
        emptyState         = findViewById(R.id.emptyState)

        setupSpinners()
        setupButtons()
        // Engines are loaded lazily when user triggers loadVideo() — not here.
        // This avoids ~3 GB of model memory sitting idle before the user
        // has even entered a URL.

        // Auto-hide/show top controls with player controls
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topControlsOverlay.visibility = visibility
            }
        )
    }

    override fun onStop() {
        super.onStop()
        resumePlaybackOnStart = dualTrackPlayer?.exoPlayer?.playWhenReady == true
        dualTrackPlayer?.pause()
        handler.removeCallbacks(statusUpdater)
    }

    override fun onStart() {
        super.onStart()
        if (resumePlaybackOnStart) {
            dualTrackPlayer?.play()
            handler.removeCallbacks(statusUpdater)
            handler.post(statusUpdater)
            resumePlaybackOnStart = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusUpdater)
        dualTrackPlayer?.release()
        dualTrackPlayer = null
        sttEngine?.release()
        translationEngine?.release()
        initScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────

    private fun setupSpinners() {
        // Use dark-themed spinner items for dark UI
        val srcAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, sourceLanguageNames)
            .also { it.setDropDownViewResource(R.layout.spinner_item_dark) }
        val tgtAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, targetLanguageNames)
            .also { it.setDropDownViewResource(R.layout.spinner_item_dark) }
        spinnerSrcLang.adapter = srcAdapter
        spinnerTgtLang.adapter = tgtAdapter
        spinnerSrcLang.setSelection(0)  // Auto-detect
        spinnerTgtLang.setSelection(0)  // English
    }

    private fun setupButtons() {
        btnLoadUrl.setOnClickListener {
            val url = etVideoUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a video URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Detect unsupported streaming URLs
            val errorMsg = validateVideoUrl(url)
            if (errorMsg != null) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            dismissKeyboard()
            resolveAndLoadUrl(Uri.parse(url))
        }

        btnPickFile.setOnClickListener {
            dismissKeyboard()
            filePickerLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun dismissKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: etVideoUrl.windowToken
        if (token != null) imm?.hideSoftInputFromWindow(token, 0)
        etVideoUrl.clearFocus()
    }

    /**
     * Lazily initialise Whisper + NLLB engines.
     * Called from [loadVideo] the first time a video is loaded, NOT from onCreate().
     * After engines are ready, [onReady] fires on the main thread.
     *
     * If engines are already loaded, [onReady] fires immediately.
     */
    private fun initEngines(onReady: (() -> Unit)? = null) {
        // Already loaded — fire callback immediately
        if (sttEngine != null && translationEngine != null) {
            onReady?.invoke()
            return
        }
        // Already loading in background — don't start a second load
        if (enginesLoading) {
            Log.w(TAG, "initEngines() already in progress, skipping duplicate call")
            return
        }
        enginesLoading = true
        tvDecoderStatus.text = "● LOADING AI…"
        initScope.launch {
            try {
                Log.i(TAG, "PERF | initEngines start " +
                    "(STT=${BuildConfig.STT_ENGINE_MODE}, MT=${BuildConfig.MT_ENGINE_MODE})")
                val t0 = android.os.SystemClock.elapsedRealtime()

                val stt: SpeechToTextEngine = when (BuildConfig.STT_ENGINE_MODE.uppercase()) {
                    "CLOUD" -> {
                        require(BuildConfig.OPENAI_API_KEY.isNotBlank()) {
                            "OPENAI_API_KEY missing — set it in local.properties or switch " +
                                "STT_ENGINE_MODE=LOCAL"
                        }
                        OpenAITranscriptionEngine(apiKey = BuildConfig.OPENAI_API_KEY)
                    }
                    else -> WhisperCppEngine(this@VideoTranslationActivity)
                }
                val t1 = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "PERF | STT engine loaded in ${t1 - t0}ms")

                val mt: TranslationEngine = when (BuildConfig.MT_ENGINE_MODE.uppercase()) {
                    "CLOUD" -> {
                        require(BuildConfig.OPENAI_API_KEY.isNotBlank()) {
                            "OPENAI_API_KEY missing — set it in local.properties or switch " +
                                "MT_ENGINE_MODE=LOCAL"
                        }
                        GptTranslationEngine(apiKey = BuildConfig.OPENAI_API_KEY)
                    }
                    else -> LocalNllbEngine(this@VideoTranslationActivity)
                }
                val t2 = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "PERF | MT engine loaded in ${t2 - t1}ms (total ${t2 - t0}ms)")

                withContext(Dispatchers.Main) {
                    sttEngine = stt
                    translationEngine = mt
                    enginesLoading = false
                    tvDecoderStatus.text = "● READY"
                    Log.i(TAG, "Engines initialized")
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init engines", e)
                withContext(Dispatchers.Main) {
                    enginesLoading = false
                    tvDecoderStatus.text = "● ERROR"
                    Toast.makeText(
                        this@VideoTranslationActivity,
                        "Failed to load AI models: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // URL validation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns an error message if the URL is unsupported, or null if it looks OK.
     *
     * Supported:
     *   - YouTube watch / shorts / live / share links → resolved via InnerTube API
     *   - Direct video URLs (.mp4, .mkv, .m3u8, .mpd, etc.) → played directly
     *
     * Blocked:
     *   - DRM-protected streaming services (Netflix, Disney+, etc.)
     */
    private fun validateVideoUrl(url: String): String? {
        val lower = url.lowercase()

        // DRM-protected services
        if (lower.contains("netflix.com") || lower.contains("disneyplus.com") ||
            lower.contains("hulu.com") || lower.contains("hbomax.com") ||
            lower.contains("primevideo.com")
        ) {
            return "This streaming service uses DRM protection.\n" +
                "Please use a YouTube link or direct video URL."
        }

        // Must start with http:// or https://
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }

        return null
    }

    private fun resolveAndLoadUrl(inputUri: Uri) {
        val isYouTube = YouTubeStreamResolver.isYouTubeUri(inputUri)
            tvDecoderStatus.text = if (isYouTube) "● RESOLVING…" else "● LOADING…"

        initScope.launch {
            val resolvedUri = withContext(Dispatchers.IO) {
                try {
                    // Always go through the resolver — it handles both YouTube
                    // and non-YouTube URIs (non-YouTube are returned as-is).
                    YouTubeStreamResolver.resolvePlayableUri(inputUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve URL: $inputUri", e)
                    null
                }
            }

            withContext(Dispatchers.Main) {
                if (resolvedUri == null) {
                    tvDecoderStatus.text = "● ERROR"
                    val msg = if (isYouTube) {
                        "Cannot resolve this YouTube video.\n" +
                            "The video may be private, age-restricted, or region-locked.\n" +
                            "Try another video or use a local file."
                    } else {
                        "Cannot play this URL.\n" +
                            "Make sure it points to a video file (.mp4, .mkv, .m3u8)."
                    }
                    Toast.makeText(
                        this@VideoTranslationActivity, msg, Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.i(TAG, "Resolved: ${inputUri} → ${resolvedUri.uri} " +
                        "(mime=${resolvedUri.mimeType}, headers=${resolvedUri.headers.size})")
                    loadVideo(resolvedUri.uri, resolvedUri.mimeType, resolvedUri.headers)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Video loading
    // ─────────────────────────────────────────────────────────────────────

    private fun loadVideo(
        uri: Uri,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val stt = sttEngine
        val mt  = translationEngine
        if (stt == null || mt == null) {
            // Engines not loaded yet — start loading, then auto-retry loadVideo
            initEngines {
                Log.i(TAG, "Engines ready — auto-loading video: $uri")
                loadVideo(uri, mimeType, headers)
            }
            return
        }

        // Release previous player if any
        dualTrackPlayer?.release()

        val srcLang = sourceLanguageCodes[spinnerSrcLang.selectedItemPosition]
        val tgtLang = targetLanguageCodes[spinnerTgtLang.selectedItemPosition]

        val player = DualTrackPlayer(
            context = this,
            sttEngine = stt,
            translationEngine = mt,
            sourceLang = srcLang,
            targetLang = tgtLang
        )

        // Bind ExoPlayer to the PlayerView
        playerView.player = player.exoPlayer

        // Subtitle listener
        player.setSubtitleListener { entry ->
            updateSubtitle(entry)
        }

        // Error listener — show user-friendly message instead of crashing
        player.onErrorListener = { error ->
            handlePlaybackError(error)
        }

        // Scheme A: show a one-shot toast when the priming-phase language
        // detector commits. Callback arrives on a background thread, so
        // marshal to main. `accepted=false` means we never got a confident
        // detection and fell back to per-window auto-mode; flag that visibly
        // so the user knows subtitles may be unstable and can tap the source
        // language spinner to fix it.
        player.onLanguageLocked = { lang, confidence, accepted ->
            runOnUiThread {
                val msg = if (accepted) {
                    val confPct = (confidence * 100).toInt()
                    "\uD83C\uDF10 Detected source language: ${lang.uppercase()} ($confPct%)"
                } else {
                    "\u26A0\uFE0F Could not confidently detect language. " +
                        "Consider selecting a source language manually."
                }
                Toast.makeText(this@VideoTranslationActivity, msg, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "UI notified of language lock: lang=$lang confidence=$confidence accepted=$accepted")
            }
        }

        player.load(uri, mimeType, headers)
        player.play()
        dualTrackPlayer = player

        // Hide empty state, show player
        emptyState.visibility = View.GONE

        // Start status updater
        handler.removeCallbacks(statusUpdater)
        handler.post(statusUpdater)

        Log.i(TAG, "Video loaded: $uri ($srcLang -> $tgtLang, headers=${headers.size})")
        tvDecoderStatus.text = "● DECODING…"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Subtitle display
    // ─────────────────────────────────────────────────────────────────────

    private fun updateSubtitle(entry: SubtitleStore.Entry?) {
        if (entry == null || entry.translated.isBlank()) {
            subtitleContainer.visibility = View.GONE
            return
        }

        subtitleContainer.visibility = View.VISIBLE
        tvTranslated.text = entry.translated

        val showOrig = cbShowOriginal.isChecked && entry.original.isNotBlank()
        tvOriginal.visibility = if (showOrig) View.VISIBLE else View.GONE
        subtitleDivider.visibility = if (showOrig) View.VISIBLE else View.GONE
        if (showOrig) tvOriginal.text = entry.original
    }

    // ─────────────────────────────────────────────────────────────────────
    // Error handling
    // ─────────────────────────────────────────────────────────────────────

    private fun handlePlaybackError(error: PlaybackException) {
        Log.e(TAG, "Playback error: ${error.errorCodeName}", error)

        handler.removeCallbacks(statusUpdater)

        // Build a user-friendly message based on the error type
        val cause = error.cause
        val message = when {
            cause is UnrecognizedInputFormatException ->
                "Cannot play this source — format not recognized.\n" +
                "Make sure the URL points directly to a video file (.mp4, .mkv, .m3u8)."

            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Network error — check your connection and try again."

            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Server returned an error. The URL may be invalid or expired."

            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "File not found. Check the URL or file path."

            else ->
                "Playback error: ${error.message ?: error.errorCodeName}"
        }

        tvDecoderStatus.text = "● ERROR"
        subtitleContainer.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "VideoTranslationAct"
    }
}
