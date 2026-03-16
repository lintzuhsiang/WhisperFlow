package com.liveTranslation.service

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that:
 *   1. Receives a [MediaProjection] token from MainActivity.
 *   2. Captures internal device audio at 16 kHz / Mono / PCM-16.
 *   3. Accumulates ~2-3 seconds of audio into a "chunk".
 *   4. Passes each chunk through the ASR → Translation pipeline.
 *   5. Pushes the result to the [FloatingSubtitleView] overlay.
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

    // Language settings (injected via Intent extras)
    private var sourceLang = "auto"
    private var targetLang = "en"

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        NotificationHelper.createChannel(this)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this))

        // Initialise AI engines (model loading is done inside each constructor)
        asrEngine    = WhisperCppEngine(applicationContext)
        mtEngine     = LocalNllbEngine(applicationContext)
        floatingView = FloatingSubtitleView(this)
        floatingView.show()
    }

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

        // Retrieve the MediaProjection from the result data passed by MainActivity
        val projectionData: Intent = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run {
                Log.e(TAG, "No MediaProjection result data – stopping")
                stopSelf()
                return START_NOT_STICKY
            }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(RESULT_OK, projectionData).also {
            it.registerCallback(projectionCallback, null)
        }

        startAudioCapture()
        return START_REDELIVER_INTENT    // Re-deliver last intent if killed
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

    override fun onBind(intent: Intent?): IBinder? = null  // Not a bound service

    // ──────────────────────────────────────────────────────────────────────
    // Audio capture
    // ──────────────────────────────────────────────────────────────────────

    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        // ── AudioPlaybackCaptureConfiguration ────────────────────────────
        // Captures audio played by OTHER apps (requires USAGE_MEDIA or USAGE_GAME).
        // An app can only capture audio from processes that opt-in via
        // AudioAttributes.ALLOW_CAPTURE_BY_ALL (or when the capturing app has
        // CAPTURE_AUDIO_OUTPUT permission which requires being a system app).
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // ── AudioFormat ──────────────────────────────────────────────────
        // 16 kHz / Mono / PCM_16BIT  →  exact format expected by whisper.cpp
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
        // Use 4× the minimum to reduce the chance of buffer overrun
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
            runCaptureLoop()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Capture & AI pipeline loop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Reads audio from [audioRecord] in a continuous loop.
     *
     * The loop accumulates samples until [CHUNK_DURATION_SAMPLES] worth of
     * PCM data has been collected (≈ [CHUNK_DURATION_SECONDS] seconds), then
     * sends the chunk to the ASR → MT pipeline.
     *
     * This coroutine runs on [Dispatchers.Default] so it never touches the
     * Main thread.
     */
    private suspend fun runCaptureLoop() {
        // Short-array read buffer (one read call reads READ_SIZE_SAMPLES at a time)
        val readBuffer = ShortArray(READ_SIZE_SAMPLES)

        // Accumulation buffer that fills up to CHUNK_DURATION_SAMPLES shorts
        val chunkBuffer = ShortArray(CHUNK_DURATION_SAMPLES)
        var chunkOffset = 0

        Log.i(TAG, "Capture loop started (chunk=${CHUNK_DURATION_SECONDS}s = $CHUNK_DURATION_SAMPLES samples)")

        while (currentCoroutineContext().isActive) {
            val result = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break

            if (result < 0) {
                Log.w(TAG, "AudioRecord.read() error code: $result")
                continue
            }

            // Append freshly read samples to the accumulation buffer
            val copyCount = minOf(result, chunkBuffer.size - chunkOffset)
            System.arraycopy(readBuffer, 0, chunkBuffer, chunkOffset, copyCount)
            chunkOffset += copyCount

            // When the chunk is full, run the AI pipeline
            if (chunkOffset >= CHUNK_DURATION_SAMPLES) {
                val audioChunk = chunkBuffer.copyOf(chunkOffset)
                chunkOffset = 0
                processChunk(audioChunk)
            }
        }

        Log.i(TAG, "Capture loop ended")
    }

    /**
     * Runs the full ASR → Translation → UI update pipeline for one audio chunk.
     *
     * Everything here already runs on Dispatchers.Default (called from the
     * capture coroutine).  We only hop to Main for the UI update.
     */
    private suspend fun processChunk(audio: ShortArray) {
        // ── 1. Speech-to-Text ────────────────────────────────────────────
        val transcript = asrEngine.transcribe(audio, sourceLang)

        if (transcript.isBlank()) {
            Log.v(TAG, "No speech detected in chunk – skipping")
            return
        }

        Log.d(TAG, "Transcript [$sourceLang]: $transcript")

        // ── 2. Translation ───────────────────────────────────────────────
        val translation = if (sourceLang == targetLang || sourceLang == "en" && targetLang == "en") {
            transcript  // No-op: same language
        } else {
            mtEngine.translate(transcript, sourceLang, targetLang)
        }

        Log.d(TAG, "Translation [$targetLang]: $translation")

        // ── 3. Update the floating overlay (must be on Main thread) ──────
        withContext(Dispatchers.Main) {
            floatingView.updateSubtitle(
                originalText   = transcript,
                translatedText = translation
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MediaProjection callback
    // ──────────────────────────────────────────────────────────────────────

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System revoked our projection token – gracefully stop
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

        /** Process audio in 2.5-second chunks to balance latency vs. accuracy. */
        const val CHUNK_DURATION_SECONDS = 2.5f
        val CHUNK_DURATION_SAMPLES = (SAMPLE_RATE * CHUNK_DURATION_SECONDS).toInt() // 40 000

        /** Each read call pulls 0.1 s of audio (1 600 samples × 2 bytes = 3 200 bytes). */
        const val READ_SIZE_SAMPLES = SAMPLE_RATE / 10  // 1 600

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
