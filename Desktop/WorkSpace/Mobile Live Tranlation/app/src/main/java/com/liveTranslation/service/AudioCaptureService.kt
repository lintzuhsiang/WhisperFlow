package com.liveTranslation.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.liveTranslation.ai.LocalNllbEngine
import com.liveTranslation.ai.WhisperCppEngine
import com.liveTranslation.ui.FloatingSubtitleView
import com.liveTranslation.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Foreground service that:
 *   1. Receives a [MediaProjection] token from MainActivity.
 *   2. Captures internal device audio at 16 kHz / Mono / PCM-16.
 *   3. Maintains a 3-second rolling buffer; every 1 second it snapshots the
 *      buffer and sends it through the ASR -> Translation pipeline.
 *   4. Pushes results to the [FloatingSubtitleView] overlay.
 *
 * Key optimisations:
 *   - **Sliding window**: 3 s context, 1 s step  ->  UI refreshes every second.
 *   - **VAD (RMS)**: silent windows are skipped to save CPU and prevent hallucination.
 *   - **Context prompting**: the previous transcription is fed as `initial_prompt`
 *     so words cut at window boundaries are stitched together.
 *   - **Pipelining**: processing runs on a separate coroutine; capture never blocks.
 *
 * Start the service with [buildStartIntent].
 */
class AudioCaptureService : Service() {

    // ──────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private lateinit var asrEngine: WhisperCppEngine
    private lateinit var mtEngine: LocalNllbEngine
    private lateinit var floatingView: FloatingSubtitleView

    // Service-scoped coroutine scope: cancelled in onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var captureJob: Job? = null

    data class InferencePayload(val audio: ShortArray, val isFinal: Boolean)

    // Channel to hold audio. Capacity=1 with DROP_OLDEST prevents an infinite backlog
    // while ensuring the CPU never sits idle if inference lags slightly.
    private val audioChannel = Channel<InferencePayload>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Context prompt: the last successful transcription, fed into the next window.
    @Volatile
    private var lastTranscript: String = ""

    // Language settings (injected via Intent extras)
    private var sourceLang = "auto"
    private var targetLang = "en"

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        // NOTE: startForeground() for foregroundServiceType=mediaProjection MUST be called
        // AFTER MediaProjectionManager.getMediaProjection() on API 34+ — we do it in
        // onStartCommand() once we have the projection token.  Here we just create the
        // notification channel so it exists before the foreground call.
        NotificationHelper.createChannel(this)

        asrEngine    = WhisperCppEngine(applicationContext)
        mtEngine     = LocalNllbEngine(applicationContext)
        floatingView = FloatingSubtitleView(this)
        floatingView.show()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        sourceLang = intent?.getStringExtra(EXTRA_SOURCE_LANG) ?: "auto"
        targetLang = intent?.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
        Log.i(TAG, "Languages: $sourceLang -> $targetLang")

        val projectionData: Intent = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run {
                Log.e(TAG, "No MediaProjection result data – stopping")
                stopSelf()
                return START_NOT_STICKY
            }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // On Android 14+ (API 34) it is strictly required to run as a foreground service
        // of type mediaProjection BEFORE calling getMediaProjection().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID, 
                NotificationHelper.buildNotification(this), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this))
        }

        mediaProjection = projectionManager.getMediaProjection(RESULT_OK, projectionData).also {
            it.registerCallback(projectionCallback, null)
        }

        startAudioCapture()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy – cleaning up")
        captureJob?.cancel()
        audioRecord?.apply { stop(); release() }
        mediaProjection?.stop()
        asrEngine.release()
        mtEngine.release()
        floatingView.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────
    // Audio capture
    // ──────────────────────────────────────────────────────────────────────

    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize * 4

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        Log.i(TAG, "AudioRecord started (bufferSize=$bufferSize bytes)")

        captureJob = serviceScope.launch {
            // Start the background inference pipeline consumer
            launch {
                for (payload in audioChannel) {
                    processWindow(payload.audio, lastTranscript, payload.isFinal)
                }
            }
            runCaptureLoop()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utterance-based VAD Capture Loop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * VAD-based recording loop.
     * Continuously records audio and groups speech into single "utterances".
     * Triggers AI processing only when the user finishes a sentence (pauses speaking)
     * or when the utterance hits a maximum length limit.
     */
    private suspend fun runCaptureLoop() {
        val readBuffer = ShortArray(READ_SIZE_SAMPLES)

        // State machine variables
        var isSpeaking = false
        var silenceFramesCount = 0
        val silenceFramesThreshold = (SILENCE_TIMEOUT_MS / READ_WINDOW_MS).toInt()

        // Accumulator for the current utterance
        val utteranceBuffer = mutableListOf<ShortArray>()
        var utteranceSamples = 0

        // Timer for streaming partial updates
        var lastPartialDurationMs = 0L

        // Keep a small rollback buffer to prepend to utterances (captures the breath/onset)
        val preRollFrames = 3
        val preRollBuffer = kotlin.collections.ArrayDeque<ShortArray>(preRollFrames)

        Log.i(TAG, "Capture loop started – Utterance based")

        while (currentCoroutineContext().isActive) {
            val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
            if (readCount < 0) {
                Log.w(TAG, "AudioRecord.read() error code: $readCount")
                continue
            }

            // We only process full read buffers for simplicity in VAD calculation.
            // If it's a partial read, we copy it exactly.
            val currentFrame = readBuffer.copyOf(readCount)
            val rms = computeRms(currentFrame)
            val isActiveFrame = rms >= SILENCE_RMS_THRESHOLD

            if (!isSpeaking) {
                if (isActiveFrame) {
                    // Transition to SPEAKING
                    isSpeaking = true
                    silenceFramesCount = 0
                    lastPartialDurationMs = 0L
                    Log.v(TAG, "VAD: Speech started")

                    // Add pre-roll buffer so we don't cut off the start of words
                    for (frame in preRollBuffer) {
                        utteranceBuffer.add(frame)
                        utteranceSamples += frame.size
                    }
                    preRollBuffer.clear()

                    utteranceBuffer.add(currentFrame)
                    utteranceSamples += currentFrame.size
                } else {
                    // Update pre-roll buffer while idle
                    if (preRollBuffer.size >= preRollFrames) {
                        preRollBuffer.removeFirst()
                    }
                    preRollBuffer.addLast(currentFrame)
                }
            } else {
                // Currently SPEAKING
                utteranceBuffer.add(currentFrame)
                utteranceSamples += currentFrame.size

                if (!isActiveFrame) {
                    silenceFramesCount++
                } else {
                    silenceFramesCount = 0 // reset on active speech
                }

                // Check for utterance end conditions
                val utteranceDurationMs = (utteranceSamples * 1000L) / SAMPLE_RATE

                // --- Streaming Partial Updates ---
                // If enough time has passed, send a partial update so the UI feels "streaming"
                if (utteranceDurationMs - lastPartialDurationMs >= PARTIAL_UPDATE_INTERVAL_MS) {
                    lastPartialDurationMs = utteranceDurationMs

                    // Only send partial if it contains at least some minimum speech duration
                    if (utteranceDurationMs >= MIN_SPEECH_DURATION_MS) {
                        val partialUtterance = ShortArray(utteranceSamples)
                        var offset = 0
                        for (frame in utteranceBuffer) {
                            System.arraycopy(frame, 0, partialUtterance, offset, frame.size)
                            offset += frame.size
                        }
                        audioChannel.trySend(InferencePayload(partialUtterance, isFinal = false))
                    }
                }
                val maxDurationReached = utteranceDurationMs >= MAX_SPEECH_DURATION_MS
                val silenceTimeoutReached = silenceFramesCount >= silenceFramesThreshold

                if (silenceTimeoutReached || maxDurationReached) {
                    // Transition to SILENCE
                    isSpeaking = false

                    // If it was just a micro-pop (e.g. keyboard click), ignore it
                    if (utteranceDurationMs >= MIN_SPEECH_DURATION_MS) {
                        Log.i(TAG, "VAD: Utterance finished (length=${utteranceDurationMs}ms, reason=${if (maxDurationReached) "MAX_DURATION" else "SILENCE_TIMEOUT"})")

                        // Flatten the queued utterance chunks into one array
                        val finalUtterance = ShortArray(utteranceSamples)
                        var offset = 0
                        for (frame in utteranceBuffer) {
                            System.arraycopy(frame, 0, finalUtterance, offset, frame.size)
                            offset += frame.size
                        }

                        // Send the complete utterance to the background pipeline
                        audioChannel.trySend(InferencePayload(finalUtterance, isFinal = true))
                    } else {
                        Log.v(TAG, "VAD: Utterance too short (${utteranceDurationMs}ms), discarding")
                    }

                    // Reset state
                    utteranceBuffer.clear()
                    utteranceSamples = 0
                    silenceFramesCount = 0
                }
            }
        }

        Log.i(TAG, "Capture loop ended")
    }

    // ──────────────────────────────────────────────────────────────────────
    // VAD helper
    // ──────────────────────────────────────────────────────────────────────

    /** Compute Root-Mean-Square amplitude of a PCM-16 buffer. */
    private fun computeRms(audio: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in audio) {
            val s = sample.toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / audio.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ASR → Translation pipeline
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Runs the full ASR -> Translation -> UI update pipeline for one
     * sliding-window snapshot.
     *
     * @param audio   3 s PCM-16 window.
     * @param prompt  Previous transcription used as whisper's `initial_prompt`.
     * @param isFinal True if this is a finished sentence, false if partial "streaming" update
     */
    private suspend fun processWindow(audio: ShortArray, prompt: String, isFinal: Boolean) {
        // ── 1. Speech-to-Text (+ optional built-in EN translation) ────────
        val whisperUsedTranslation = targetLang == "en" && sourceLang != "en"
        val transcript = asrEngine.transcribe(audio, sourceLang, targetLang, prompt)

        // Whisper returns "[BLANK_AUDIO]" (or similar bracketed tokens) when it
        // detects no meaningful speech.  Filter these out so they never reach the
        // subtitle overlay.
        if (transcript.isBlank() || transcript.trim().let {
                it.startsWith("[") && it.endsWith("]")
            }) {
            Log.v(TAG, "No speech in window (whisper='$transcript') – skipping")
            return
        }

        // Save for next window's context prompt (trim to last ~200 chars to
        // avoid feeding an ever-growing string into the decoder)
        // ONLY save if this is Final, otherwise partial text pollutes context of the same utterance
        if (isFinal) {
            lastTranscript = transcript.takeLast(MAX_PROMPT_CHARS)
        }

        Log.d(TAG, "Transcript [$sourceLang] (final=$isFinal): $transcript")

        // ── 2. Translation ─────────────────────────────────────────────────
        val translation = when {
            sourceLang == targetLang -> transcript
            whisperUsedTranslation  -> transcript
            else -> mtEngine.translate(transcript, sourceLang, targetLang)
        }

        Log.d(TAG, "Translation [$targetLang] (final=$isFinal): $translation")

        // ── 3. Update the floating overlay (must be on Main thread) ────────
        withContext(Dispatchers.Main) {
            val displayTranscript = if (isFinal) transcript else "$transcript..."
            val displayTranslation = if (isFinal) translation else "$translation..."
            floatingView.updateSubtitle(
                originalText   = displayTranscript,
                translatedText = displayTranslation
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MediaProjection callback
    // ──────────────────────────────────────────────────────────────────────

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopSelf()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "AudioCaptureService"

        const val ACTION_STOP = "com.liveTranslation.ACTION_STOP"

        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val EXTRA_TARGET_LANG = "target_lang"

        // ── Audio parameters ──────────────────────────────────────────
        /** Whisper.cpp requires exactly 16 000 Hz mono PCM-16. */
        const val SAMPLE_RATE = 16_000

        // ── VAD & Utterance state machine parameters ──────────────────
        /** Size of each read block from AudioRecord (in ms) */
        const val READ_WINDOW_MS = 100L
        val READ_SIZE_SAMPLES = ((SAMPLE_RATE * READ_WINDOW_MS) / 1000).toInt()  // 1 600

        /** RMS threshold for silence detection. Typical noise floor is 30-80, speech > 200. */
        const val SILENCE_RMS_THRESHOLD = 100.0

        /** How long of silence constitutes the end of a sentence (in ms) */
        const val SILENCE_TIMEOUT_MS = 600L

        /** Min length of speech to be considered valid (ignore short pops, in ms) */
        const val MIN_SPEECH_DURATION_MS = 400L

        /** How often to update the UI while speaking (in ms) */
        const val PARTIAL_UPDATE_INTERVAL_MS = 800L

        /** Max length of an utterance before we forcibly cut it (in ms) */
        const val MAX_SPEECH_DURATION_MS = 15_000L

        // ── Context prompting ─────────────────────────────────────────
        /** Max characters from the previous transcription to keep as prompt. */
        const val MAX_PROMPT_CHARS = 200

        private const val RESULT_OK = android.app.Activity.RESULT_OK

        /** Build the Intent needed to start this service from MainActivity. */
        fun buildStartIntent(
            context: android.content.Context,
            projectionResultData: Intent,
            sourceLang: String = "auto",
            targetLang: String = "en"
        ) = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_DATA, projectionResultData)
            putExtra(EXTRA_SOURCE_LANG, sourceLang)
            putExtra(EXTRA_TARGET_LANG, targetLang)
        }
    }
}
