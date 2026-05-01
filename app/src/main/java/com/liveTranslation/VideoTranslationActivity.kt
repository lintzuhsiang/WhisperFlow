package com.liveTranslation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import com.liveTranslation.ai.GptTranslationEngine
import com.liveTranslation.ai.LocalNllbEngine
import com.liveTranslation.ai.OpenAITranscriptionEngine
import com.liveTranslation.ai.SpeechToTextEngine
import com.liveTranslation.ai.TranslationEngine
import com.liveTranslation.ai.WhisperCppEngine
import com.liveTranslation.player.DualTrackPlayer
import com.liveTranslation.player.SubtitleStore
import com.liveTranslation.player.YouTubeStreamResolver
import com.liveTranslation.ui.WhisperFlowApp
import com.liveTranslation.ui.WhisperFlowAppState
import com.liveTranslation.ui.components.StatusTone
import com.liveTranslation.ui.screens.RecentSubtitle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Video Translation Activity — dual-track player, Compose UI.
 *
 * Plays a video file or HTTP URL with real-time ASR + translation subtitles.
 * An invisible audio decoder runs N seconds ahead of the visible playback,
 * so subtitles are pre-computed and ready by the time each scene plays.
 *
 * State model:
 *   - Activity owns engines and the DualTrackPlayer (lifecycle-tied).
 *   - Activity exposes a single [_uiState] StateFlow that the Compose tree
 *     subscribes to. The UI never reaches into engines directly.
 *   - All user actions surface as callbacks back into the Activity.
 */
class VideoTranslationActivity : ComponentActivity() {

    // ── AI engines (interface-typed; concrete impl chosen by BuildConfig) ──
    private var sttEngine: SpeechToTextEngine? = null
    private var translationEngine: TranslationEngine? = null
    private var dualTrackPlayer: DualTrackPlayer? = null
    private val initScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var enginesLoading = false
    private var resumePlaybackOnStart = false

    // ── UI state ──────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(WhisperFlowAppState())
    val uiState: StateFlow<WhisperFlowAppState> = _uiState

    private fun mutate(block: (WhisperFlowAppState) -> WhisperFlowAppState) {
        _uiState.value = block(_uiState.value)
    }

    // ── Language tables (codes ↔ display names) ──────────────────────────
    private val sourceLanguages = listOf(
        "auto"  to "Auto-detect",
        "en"    to "English",
        "ja"    to "Japanese",
        "zh"    to "Chinese (Simplified)",
        "zh-tw" to "Chinese (Traditional)",
        "ko"    to "Korean",
        "es"    to "Spanish",
        "fr"    to "French",
        "de"    to "German",
        "ar"    to "Arabic",
        "pt"    to "Portuguese",
        "ru"    to "Russian",
        "hi"    to "Hindi"
    )
    private val targetLanguages = sourceLanguages.drop(1)  // can't translate to "auto"

    // ── File picker ───────────────────────────────────────────────────────
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
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
        setContent { WhisperFlowAppHost(activity = this) }
    }

    override fun onStop() {
        super.onStop()
        resumePlaybackOnStart = dualTrackPlayer?.exoPlayer?.playWhenReady == true
        dualTrackPlayer?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (resumePlaybackOnStart) {
            dualTrackPlayer?.play()
            resumePlaybackOnStart = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dualTrackPlayer?.release()
        dualTrackPlayer = null
        sttEngine?.release()
        translationEngine?.release()
        initScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public callbacks (called from Compose)
    // ─────────────────────────────────────────────────────────────────────

    fun onUrlChange(url: String) = mutate { it.copy(url = url) }

    fun onShowOriginalChange(value: Boolean) = mutate { it.copy(showOriginal = value) }

    fun onSourceLangCycle() {
        val currentIdx = sourceLanguages.indexOfFirst { it.first == uiState.value.sourceLangCode }
        val nextIdx = (currentIdx + 1) % sourceLanguages.size
        val (code, name) = sourceLanguages[nextIdx]
        mutate { it.copy(sourceLangCode = code, sourceLangLabel = name) }
    }

    fun onTargetLangCycle() {
        val currentIdx = targetLanguages.indexOfFirst { it.first == uiState.value.targetLangCode }
        val nextIdx = (currentIdx + 1) % targetLanguages.size
        val (code, name) = targetLanguages[nextIdx]
        mutate { it.copy(targetLangCode = code, targetLangLabel = name) }
    }

    fun onLangChipClick() {
        // In the Player, clicking the chip cycles the TARGET language only.
        // Source is locked once detection commits.
        onTargetLangCycle()
        Toast.makeText(this, "Target → ${uiState.value.targetLangLabel}",
            Toast.LENGTH_SHORT).show()
    }

    fun onPlayPause() {
        val player = dualTrackPlayer ?: return
        if (player.exoPlayer.playWhenReady) player.pause() else player.play()
        mutate { it.copy(isPlaying = player.exoPlayer.playWhenReady) }
    }

    fun onSeek(positionMs: Long) {
        dualTrackPlayer?.exoPlayer?.seekTo(positionMs)
    }

    fun onFullscreenClick() {
        // Stage-3 placeholder — landscape lock is wired in stage 4.
        Toast.makeText(this, "Fullscreen — coming soon", Toast.LENGTH_SHORT).show()
    }

    fun onGoClick() {
        val url = uiState.value.url.trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Enter a video URL", Toast.LENGTH_SHORT).show()
            return
        }
        val errorMsg = validateVideoUrl(url)
        if (errorMsg != null) {
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            return
        }
        resolveAndLoadUrl(Uri.parse(url))
    }

    fun onFileClick() {
        filePickerLauncher.launch(arrayOf("video/*"))
    }

    fun currentPlayer() = dualTrackPlayer?.exoPlayer

    /**
     * Called from the Compose tick loop — pushes player timeline state into
     * UI state. Doing this here (rather than directly inside the composable)
     * keeps the reducer-style mutate() pattern intact.
     */
    fun pumpPlaybackState(pos: Long, dur: Long, isPlaying: Boolean) {
        mutate {
            it.copy(
                positionMs = pos,
                durationMs = dur,
                isPlaying = isPlaying
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Engine init (lazy — first time loadVideo is called)
    // ─────────────────────────────────────────────────────────────────────

    private fun initEngines(onReady: (() -> Unit)? = null) {
        if (sttEngine != null && translationEngine != null) {
            onReady?.invoke()
            return
        }
        if (enginesLoading) {
            Log.w(TAG, "initEngines() already in progress")
            return
        }
        enginesLoading = true
        mutate { it.copy(statusLabel = "LOADING AI", statusTone = StatusTone.Translating) }

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
                    mutate { it.copy(statusLabel = "READY", statusTone = StatusTone.Ready) }
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init engines", e)
                withContext(Dispatchers.Main) {
                    enginesLoading = false
                    mutate { it.copy(statusLabel = "ERROR", statusTone = StatusTone.Error) }
                    Toast.makeText(
                        this@VideoTranslationActivity,
                        "Failed to load AI: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // URL resolve + video load
    // ─────────────────────────────────────────────────────────────────────

    private fun validateVideoUrl(url: String): String? {
        val lower = url.lowercase()
        if (lower.contains("netflix.com") || lower.contains("disneyplus.com") ||
            lower.contains("hulu.com") || lower.contains("hbomax.com") ||
            lower.contains("primevideo.com")
        ) {
            return "This streaming service uses DRM protection.\n" +
                "Please use a YouTube link or direct video URL."
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        return null
    }

    private fun resolveAndLoadUrl(inputUri: Uri) {
        val isYouTube = YouTubeStreamResolver.isYouTubeUri(inputUri)
        mutate {
            it.copy(
                statusLabel = if (isYouTube) "RESOLVING" else "LOADING",
                statusTone = StatusTone.Translating
            )
        }
        initScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try {
                    YouTubeStreamResolver.resolvePlayableUri(inputUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve URL: $inputUri", e)
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (resolved == null) {
                    mutate { it.copy(statusLabel = "ERROR", statusTone = StatusTone.Error) }
                    val msg = if (isYouTube) {
                        "Cannot resolve this YouTube video.\n" +
                            "It may be private, age-restricted, or region-locked."
                    } else {
                        "Cannot play this URL.\n" +
                            "Make sure it points to a video file (.mp4, .mkv, .m3u8)."
                    }
                    Toast.makeText(this@VideoTranslationActivity, msg, Toast.LENGTH_LONG).show()
                } else {
                    Log.i(TAG, "Resolved: $inputUri → ${resolved.uri} " +
                        "(mime=${resolved.mimeType})")
                    loadVideo(resolved.uri, resolved.mimeType, resolved.headers)
                }
            }
        }
    }

    private fun loadVideo(
        uri: Uri,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val stt = sttEngine
        val mt = translationEngine
        if (stt == null || mt == null) {
            initEngines {
                Log.i(TAG, "Engines ready — auto-loading video: $uri")
                loadVideo(uri, mimeType, headers)
            }
            return
        }

        // Tear down any previous player
        dualTrackPlayer?.release()

        val srcLang = uiState.value.sourceLangCode
        val tgtLang = uiState.value.targetLangCode

        val player = DualTrackPlayer(
            context = this,
            sttEngine = stt,
            translationEngine = mt,
            sourceLang = srcLang,
            targetLang = tgtLang
        )

        player.setSubtitleListener { entry -> onSubtitleEntry(entry) }
        player.onErrorListener = { error -> handlePlaybackError(error) }
        player.onLanguageLocked = { lang, confidence, accepted ->
            runOnUiThread {
                val msg = if (accepted) {
                    val confPct = (confidence * 100).toInt()
                    "Detected: ${lang.uppercase()} ($confPct%)"
                } else {
                    "Couldn't detect language confidently — pick one manually if subtitles look wrong."
                }
                Toast.makeText(this@VideoTranslationActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        player.load(uri, mimeType, headers)
        player.play()
        dualTrackPlayer = player

        mutate {
            it.copy(
                isVideoLoaded = true,
                isPlaying = true,
                statusLabel = "TRANSLATING",
                statusTone = StatusTone.Translating
            )
        }

        Log.i(TAG, "Video loaded: $uri ($srcLang → $tgtLang)")
    }

    private fun onSubtitleEntry(entry: SubtitleStore.Entry?) {
        // Push current subtitle into UI state. Recent list shows last 3
        // distinct translated lines for the "RECENT" preview block.
        val current = uiState.value
        val newEntry = entry?.let {
            RecentSubtitle(
                timestamp = formatTime(it.startMs),
                translated = it.translated
            )
        }
        val updatedRecent = if (entry != null && entry.translated.isNotBlank() &&
            (current.recentEntries.lastOrNull()?.translated != entry.translated)
        ) {
            (current.recentEntries + RecentSubtitle(
                timestamp = formatTime(entry.startMs),
                translated = entry.translated
            )).takeLast(8)
        } else {
            current.recentEntries
        }
        mutate {
            it.copy(
                translatedText = entry?.translated,
                originalText = entry?.original,
                recentEntries = updatedRecent
            )
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
        val cause = error.cause
        val message = when {
            cause is UnrecognizedInputFormatException ->
                "Cannot play this source — format not recognized."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Network error — check your connection and try again."
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Server error. The URL may be invalid or expired."
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "File not found."
            else -> "Playback error: ${error.message ?: error.errorCodeName}"
        }
        mutate { it.copy(statusLabel = "ERROR", statusTone = StatusTone.Error) }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val mm = (s / 60) % 60
        val ss = s % 60
        val hh = s / 3600
        return if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss)
        else "%d:%02d".format(mm, ss)
    }

    companion object {
        private const val TAG = "VideoTranslationAct"
    }
}

/**
 * Compose host — subscribes to the Activity's [uiState] and pumps player
 * position / duration into UI state on a 250 ms tick.
 */
@Composable
private fun WhisperFlowAppHost(activity: VideoTranslationActivity) {
    val state by activity.uiState.collectAsState()
    val player = remember(state.isVideoLoaded) { activity.currentPlayer() }

    // Tick — update positionMs/durationMs/isPlaying every 250ms while a
    // video is loaded. Cheap; doesn't fight the player's own timeline.
    LaunchedEffect(state.isVideoLoaded) {
        if (!state.isVideoLoaded) return@LaunchedEffect
        while (true) {
            val p = activity.currentPlayer()
            if (p != null) {
                activity.pumpPlaybackState(
                    pos = p.currentPosition,
                    dur = p.duration.coerceAtLeast(0L),
                    isPlaying = p.isPlaying
                )
            }
            kotlinx.coroutines.delay(250)
        }
    }

    WhisperFlowApp(
        state = state,
        player = player,
        onUrlChange = activity::onUrlChange,
        onGoClick = activity::onGoClick,
        onFileClick = activity::onFileClick,
        onSourceLangClick = activity::onSourceLangCycle,
        onTargetLangClick = activity::onTargetLangCycle,
        onShowOriginalChange = activity::onShowOriginalChange,
        onPlayPause = activity::onPlayPause,
        onSeek = activity::onSeek,
        onLangChipClick = activity::onLangChipClick,
        onFullscreenClick = activity::onFullscreenClick
    )
}
