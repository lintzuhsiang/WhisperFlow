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
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.liveTranslation.ai.LocalNllbEngine
import com.liveTranslation.ai.WhisperCppEngine
import com.liveTranslation.ui.FloatingSubtitleView
import com.liveTranslation.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private lateinit var mtEngine: com.liveTranslation.ai.TranslationEngine
    private lateinit var floatingView: FloatingSubtitleView

    // Service-scoped coroutine scope: cancelled in onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    data class InferencePayload(
        val audio: ShortArray,
        val isFinal: Boolean,
        val enqueueTimeMs: Long
    )

    // Channel to hold audio. Capacity=1 with DROP_OLDEST prevents an infinite backlog
    // while ensuring the CPU never sits idle if inference lags slightly.
    private val audioChannel = Channel<InferencePayload>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Signal channel for translation work. We coalesce bursts into one signal
    // and batch pending text to avoid MT-induced ASR stalls.
    private val translationSignal = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val translationLock = Any()
    private var pendingTranslationText: String = ""
    private var pendingTranslationSourceLang: String = "en"
    private var pendingTranslationEnqueueMs: Long = 0L

    // Context prompt: the last successful transcription, fed into the next window.
    @Volatile
    private var lastTranscript: String = ""

    // Streaming text state used to build stable, low-latency subtitles.
    @Volatile
    private var lastChunkTranscript: String = ""
    @Volatile
    private var streamedOriginalText: String = ""
    @Volatile
    private var streamedTranslatedText: String = ""

    // Language settings (injected via Intent extras)
    private var sourceLang = "auto"
    private var targetLang = "en"
    private var showOriginal = false

    // Diagnostic timestamps (elapsedRealtime) for debugging projection lifecycle
    private var serviceStartTimeMs = 0L
    @Volatile private var projectionStopTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null
    @Volatile private var isDestroying = false
    @Volatile private var projectionStopHandled = false

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        NotificationHelper.createChannel(this)

        // Heavy resources (ASR engine, floating view) are lazy-initialised in
        // onStartCommand() after startForeground() is called. We do NOT call
        // startForeground() here — it MUST happen in onStartCommand() right
        // before getMediaProjection(), matching the proven working pattern.
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "DIAG | onStartCommand t0=$t0")
        if (Build.VERSION.SDK_INT >= 33) {
            val notifPerm = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            Log.i(TAG, "DIAG | POST_NOTIFICATIONS granted=${notifPerm == android.content.pm.PackageManager.PERMISSION_GRANTED}")
        }
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                broadcastCaptureState(CAPTURE_STATE_STOPPED)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // If a previous projection revoke scheduled a delayed stop, cancel it
        // when a fresh start intent arrives (user restarted quickly from PiP/UI).
        pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStopRunnable = null
        isDestroying = false
        projectionStopHandled = false
        projectionStopTimeMs = 0L

        // ── Lazy-init floating view ──────────────────────────────────────────
        if (!::floatingView.isInitialized) {
            floatingView = FloatingSubtitleView(this)
            floatingView.show()
        }

        sourceLang = intent?.getStringExtra(EXTRA_SOURCE_LANG) ?: "auto"
        targetLang = intent?.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
        showOriginal = intent?.getBooleanExtra(EXTRA_SHOW_ORIGINAL, false) ?: false
        floatingView.setShowOriginal(showOriginal)

        val useGpt = intent?.getBooleanExtra(EXTRA_USE_GPT, true) ?: true
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""

        Log.i(TAG, "Languages: $sourceLang -> $targetLang, Engine: ${if (useGpt) "GPT" else "Local NLLB"}")

        if (useGpt && apiKey.isBlank()) {
            Log.w(TAG, "GPT engine selected but API key is empty")
            floatingView.updateSubtitle("Configuration required", "Enter OpenAI API key or switch to Local NLLB.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Reset streaming state for a fresh session.
        lastTranscript = ""
        lastChunkTranscript = ""
        streamedOriginalText = ""
        streamedTranslatedText = ""
        synchronized(translationLock) {
            pendingTranslationText = ""
            pendingTranslationSourceLang = "en"
            pendingTranslationEnqueueMs = 0L
        }
        latestOriginalText = ""
        latestTranslatedText = ""

        val projectionData: Intent = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run {
                Log.e(TAG, "No MediaProjection result data – stopping")
                stopSelf()
                return START_NOT_STICKY
            }
        Log.i(TAG, "DIAG | resultData hash=${projectionData.hashCode()}, extrasKeys=${projectionData.extras?.keySet()}")
        val sstValue = projectionData.getIntExtra("screen_share_type", -1)
        Log.i(TAG, "DIAG | screen_share_type=$sstValue")

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // ════════════════════════════════════════════════════════════════════════
        // CRITICAL: This is the exact lifecycle pattern from the working main
        // branch.  All four steps run synchronously on the Main thread (which
        // has a Looper), with NO Handler.postDelayed, NO double startForeground,
        // and NO ACTION_PRIME pre-start.
        //
        // Order matters:
        //   1. startForeground(MEDIA_PROJECTION) — makes us a FGS of the right type
        //   2. getMediaProjection()              — consumes the one-shot token
        //   3. buildAndStartAudioRecord()        — needs Main-thread Looper
        //   4. launch { load models ... }        — background, non-blocking
        // ════════════════════════════════════════════════════════════════════════

        // ── Step 1: Promote to foreground service ─────────────────────────────
        // Android 14+ requires the service to be a foreground service of type
        // MEDIA_PROJECTION when getMediaProjection() is called.  Calling
        // startForeground() here — on the SAME synchronous call chain as
        // getMediaProjection() — is the proven pattern that works.
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        Log.i(TAG, "startForeground(MEDIA_PROJECTION) done")
        Log.i(TAG, "DIAG | after startForeground dt=${SystemClock.elapsedRealtime() - t0}ms")

        // ── Step 2: Acquire the MediaProjection ───────────────────────────────
        val projAcquireTimeMs = SystemClock.elapsedRealtime()
        try {
            mediaProjection = projectionManager.getMediaProjection(RESULT_OK, projectionData).also {
                // Use an explicit Main-thread Handler instead of null.
                // Passing null relies on the calling thread's implicit Looper;
                // on Android 16 (API 36) / Samsung, this caused the onStop()
                // callback to fire immediately (0 ms) after getMediaProjection().
                it.registerCallback(projectionCallback, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            Log.i(TAG, "DIAG | MediaProjection obtained in ${SystemClock.elapsedRealtime() - projAcquireTimeMs}ms")
            Log.i(TAG, "DIAG | after getMediaProjection dt=${SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MediaProjection", e)
            floatingView.updateSubtitle("Error", "Could not obtain screen capture permission.")
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Step 3: Build + start AudioRecord (Main thread — Looper present) ──
        // onStartCommand() always runs on the Main thread, which has a Looper.
        // AudioRecord.Builder.build() with AudioPlaybackCaptureConfiguration
        // registers an AudioPolicyClient binder callback that REQUIRES a Looper.
        val arBuildTimeMs = SystemClock.elapsedRealtime()
        if (!buildAndStartAudioRecord()) {
            Log.e(TAG, "AudioRecord setup failed — stopping service")
            floatingView.updateSubtitle("Error", "Could not start audio capture.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "DIAG | AudioRecord built+started in ${SystemClock.elapsedRealtime() - arBuildTimeMs}ms")

        // Mark service as actively running so MainActivity can sync its button
        // state in onResume() even if the service stops unexpectedly.
        isRunning = true
        broadcastCaptureState(CAPTURE_STATE_LISTENING)
        serviceStartTimeMs = SystemClock.elapsedRealtime()

        // ── Diagnostic: log device + notification state for debugging ────────
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        Log.i(TAG, "DIAG | device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                "API=${Build.VERSION.SDK_INT}, " +
                "notificationsEnabled=${nm.areNotificationsEnabled()}, " +
                "channelImportance=${ch?.importance ?: -1}")

        // ── Step 4: Load AI models in the background ──────────────────────────
        // Audio capture is already running; the channel buffers audio via
        // DROP_OLDEST so nothing is lost during model loading.
        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    floatingView.updateSubtitle("Loading AI engines...", "This may take a few moments.")
                }

                // Offload heavy JNI/ONNX initialisation to IO thread
                withContext(Dispatchers.IO) {
                    if (!::asrEngine.isInitialized) {
                        val asrModel = selectAsrModelFilename()
                        asrEngine = WhisperCppEngine(
                            context = applicationContext,
                            modelFilename = asrModel,
                            fallbackFilename = FALLBACK_ASR_MODEL_FILENAME,
                            nThreads = WHISPER_N_THREADS
                        )
                        Log.i(TAG, "ASR model selected: $asrModel, n_threads=$WHISPER_N_THREADS")
                    }
                    if (::mtEngine.isInitialized) {
                        mtEngine.release()
                    }
                    mtEngine = if (useGpt) {
                        com.liveTranslation.ai.GptTranslationEngine(apiKey = apiKey, modelName = "gpt-4o-mini")
                    } else {
                        LocalNllbEngine(applicationContext).also { local ->
                            if (!local.isReady()) {
                                throw IllegalStateException("Local NLLB model/tokenizer is not ready.")
                            }
                        }
                    }
                }

                // Models ready — start the inference consumer
                withContext(Dispatchers.Main) {
                    floatingView.updateSubtitle("Ready", "Waiting for speech...")
                }
                startInferenceConsumer()
                startTranslationConsumer()

            } catch (e: Exception) {
                Log.e(TAG, "Engine initialisation failed", e)
                withContext(Dispatchers.Main) {
                    floatingView.updateSubtitle("Error", "Engine failed: ${e.message}")
                }
                kotlinx.coroutines.delay(3000)
                stopSelf()
            }
        }

        // Do NOT use START_REDELIVER_INTENT — the MediaProjection token is
        // one-shot and cannot be re-used.  If the process dies, redelivering
        // the old intent would fail with an expired token and cause a crash
        // loop ("startForeground() not allowed due to bg restriction").
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val uptimeSec = if (serviceStartTimeMs > 0)
            "%.1fs".format((SystemClock.elapsedRealtime() - serviceStartTimeMs) / 1000.0) else "N/A"
        Log.i(TAG, "DIAG | onDestroy — uptime=$uptimeSec, projectionWasRevoked=${projectionStopTimeMs > 0}")
        Log.i(TAG, "onDestroy – cleaning up")
        isDestroying = true
        pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStopRunnable = null
        isRunning = false          // Let MainActivity know we stopped (crash, stopSelf, or user stop)
        broadcastCaptureState(CAPTURE_STATE_STOPPED)
        captureJob?.cancel()
        audioRecord?.apply { stop(); release() }
        mediaProjection?.stop()
        // Guard with isInitialized: heavy resources may not have been initialised
        // if the service was stopped before model loading completed.
        if (::asrEngine.isInitialized)    asrEngine.release()
        if (::mtEngine.isInitialized)     mtEngine.release()
        if (::floatingView.isInitialized) floatingView.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastCaptureState(state: String) {
        latestCaptureState = state
        sendBroadcast(
            Intent(ACTION_CAPTURE_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_CAPTURE_STATE, state)
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Audio capture — split into two functions to enforce correct ordering:
    //   1. buildAndStartAudioRecord()  → called on Main thread, BEFORE model loading
    //   2. startInferenceConsumer()    → called after models are loaded
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Creates and starts the [AudioRecord] with [AudioPlaybackCaptureConfiguration].
     *
     * **MUST be called on the Main thread.**  Although the official Android docs do
     * NOT list a Looper as a prerequisite for `AudioRecord.Builder.build()`, empirical
     * testing shows that `AudioPlaybackCaptureConfiguration` internally registers an
     * `AudioPolicyClient` binder callback that requires a Looper on the calling thread.
     * On `Dispatchers.Default` threads (which have no Looper) this throws
     * `UnsupportedOperationException("could not register audio policy")`.
     * Both OpenAI and Gemini reviews acknowledge this discrepancy; the current approach
     * (calling from `onStartCommand()` on the Main thread) is the safe, proven pattern.
     *
     * Also launches [runCaptureLoop] so audio is captured (and buffered in [audioChannel])
     * even while model loading is in progress.
     *
     * @return `true` on success, `false` if the [AudioRecord] could not be initialised.
     */
    private fun buildAndStartAudioRecord(): Boolean {
        val projection = mediaProjection ?: run {
            Log.e(TAG, "buildAndStartAudioRecord: mediaProjection is null")
            return false
        }

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlaybackCaptureConfiguration.build() failed", e)
            return false
        }

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
        Log.i(TAG, "DIAG | minBufferSize=$minBufferSize, bufferSize=$bufferSize")

        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: UnsupportedOperationException) {
            // AudioPolicyManager rejected the registration — Looper was missing or the
            // MediaProjection token was invalid.
            Log.e(TAG, "AudioRecord.build() threw UnsupportedOperationException — " +
                    "audio policy could not be registered. Ensure this runs on Main thread " +
                    "with a valid, fresh MediaProjection token.", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.build() failed", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is STATE_UNINITIALIZED after build()")
            record.release()
            return false
        }
        Log.i(
            TAG,
            "DIAG | AudioRecord state=${record.state}, " +
                "sessionId=${record.audioSessionId}, " +
                "bufferSizeInFrames=${record.bufferSizeInFrames}"
        )

        // ── Sample-rate verification ──────────────────────────────────
        // Playback capture can run at 48 kHz on some devices if the platform
        // doesn't resample.  Whisper.cpp expects exactly 16 kHz — if we get
        // a different rate the transcriptions will be garbled (e.g. 3× speed
        // at 48 kHz).  Log a prominent warning so we can diagnose on affected
        // devices.  (Both OpenAI and Gemini flagged this scenario.)
        if (record.sampleRate != SAMPLE_RATE) {
            Log.w(TAG, "⚠ AudioRecord sampleRate=${record.sampleRate} — expected $SAMPLE_RATE. " +
                    "Transcriptions may be garbled on this device!")
        } else {
            Log.i(TAG, "AudioRecord sampleRate=${record.sampleRate} (matches expected $SAMPLE_RATE)")
        }

        audioRecord = record
        record.startRecording()
        Log.i(TAG, "AudioRecord started (bufferSize=$bufferSize bytes, recordingState=${record.recordingState})")

        // Launch the capture loop immediately — audio is buffered in audioChannel
        // via DROP_OLDEST while models are loading.
        captureJob = serviceScope.launch(Dispatchers.IO) { runCaptureLoop() }
        return true
    }

    /**
     * Starts the inference consumer coroutine.
     *
     * Called AFTER [asrEngine] and [mtEngine] are fully initialised so that
     * [processWindow] never accesses an uninitialised engine.
     */
    private fun startInferenceConsumer() {
        serviceScope.launch {
            for (payload in audioChannel) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val queueDelayMs = now - payload.enqueueTimeMs
                    if (ENABLE_TIMING_LOGS) {
                        Log.i(TAG, "TIMING | asr_queue_delay_ms=$queueDelayMs")
                    }
                    processWindow(payload.audio, lastTranscript, payload.isFinal, queueDelayMs)
                } catch (e: Exception) {
                    Log.e(TAG, "processWindow error (continuing pipeline)", e)
                    withContext(Dispatchers.Main) {
                        if (::floatingView.isInitialized) {
                            floatingView.updateSubtitle("⚠ Error", e.message ?: "Unknown error")
                        }
                    }
                }
            }
        }
    }

    /**
     * Dedicated MT consumer.
     *
     * ASR pushes transcript deltas into a pending buffer and signals this worker.
     * The worker batches pending text and translates it off the ASR critical path,
     * preventing network/ONNX MT latency from blocking the next speech chunk.
     */
    private fun startTranslationConsumer() {
        serviceScope.launch {
            for (ignored in translationSignal) {
                while (true) {
                    val batch = synchronized(translationLock) {
                        if (pendingTranslationText.isBlank()) {
                            null
                        } else {
                            val text = pendingTranslationText
                            val src = pendingTranslationSourceLang
                            pendingTranslationText = ""
                            val enq = pendingTranslationEnqueueMs
                            pendingTranslationEnqueueMs = 0L
                            Triple(text, src, enq)
                        }
                    } ?: break
                    val (batchText, batchSourceLang, batchEnqueueMs) = batch

                    if (ENABLE_TIMING_LOGS && batchEnqueueMs > 0L) {
                        Log.i(TAG, "TIMING | mt_queue_delay_ms=${SystemClock.elapsedRealtime() - batchEnqueueMs}")
                    }

                    val shouldTranslate = sourceLang == "auto" ||
                        !sourceLang.equals(targetLang, ignoreCase = true)

                    val mtStart = SystemClock.elapsedRealtime()
                    val translatedBatch = try {
                        if (!shouldTranslate) {
                            batchText
                        } else {
                            mtEngine.translate(batchText, batchSourceLang, targetLang).trim()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "MT batch translation failed", e)
                        withContext(Dispatchers.Main) {
                            floatingView.updateSubtitle(
                                originalText = streamedOriginalText,
                                translatedText = "Translation failed - check API key/network/model."
                            )
                        }
                        continue
                    }
                    if (ENABLE_TIMING_LOGS) {
                        Log.i(TAG, "TIMING | mt_ms=${SystemClock.elapsedRealtime() - mtStart}, chars=${batchText.length}")
                    }

                    if (shouldTranslate && translatedBatch.isBlank()) {
                        Log.w(TAG, "MT returned blank output for non-empty batch")
                        continue
                    }

                    streamedTranslatedText = appendStreamingText(
                        streamedTranslatedText,
                        if (translatedBatch.isBlank()) batchText else translatedBatch
                    )

                    withContext(Dispatchers.Main) {
                        floatingView.updateSubtitle(
                            originalText = streamedOriginalText,
                            translatedText = streamedTranslatedText
                        )
                    }
                    broadcastSubtitle(streamedOriginalText, streamedTranslatedText)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Streaming Capture Loop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Time-based streaming capture loop (replaces the old utterance-based VAD).
     *
     * Instead of waiting for a silence boundary, audio is dispatched every
     * [CHUNK_DISPATCH_MS] regardless of whether the speaker has paused.  This
     * gives YouTube-style "text appears while speaking" behaviour with latency
     * roughly equal to CHUNK_DISPATCH_MS + inference time.
     *
     * A rolling context window ([CONTEXT_WINDOW_MS]) is kept so Whisper always
     * sees a few seconds of history — this dramatically improves continuity and
     * reduces hallucination compared to processing isolated sub-second snippets.
     *
     * A silence gate ([SILENCE_RMS_THRESHOLD]) still prevents pure-silence chunks
     * from being fed to Whisper, which avoids the "[BLANK_AUDIO]" hallucination
     * tax when no one is speaking.
     */
    private suspend fun runCaptureLoop() {
        val readBuffer = ShortArray(READ_SIZE_SAMPLES)

        // Rolling ring-buffer: keeps the last CONTEXT_WINDOW_MS of audio frames.
        val maxContextSamples = (SAMPLE_RATE * CONTEXT_WINDOW_MS / 1000).toInt()
        val contextChunks = ArrayDeque<ShortArray>()
        var contextSamples = 0

        var lastDispatchMs = SystemClock.elapsedRealtime()

        Log.i(TAG, "Capture loop started — streaming (chunk=${CHUNK_DISPATCH_MS}ms, context=${CONTEXT_WINDOW_MS}ms)")

        while (currentCoroutineContext().isActive) {
            val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
            if (readCount < 0) {
                // All negative values are errors:
                //   ERROR_DEAD_OBJECT (-6) — MediaProjection revoked
                //   ERROR_INVALID_OPERATION (-3) — not in RECORDING state
                //   ERROR_BAD_VALUE (-2) — AudioRecord in invalid state
                //   ERROR (-1) — generic failure
                val now = SystemClock.elapsedRealtime()
                val projDelta = if (projectionStopTimeMs > 0)
                    "${now - projectionStopTimeMs}ms after projection stop"
                else "projection still alive"
                Log.e(TAG, "DIAG | AudioRecord.read() error=$readCount, " +
                        "recordingState=${audioRecord?.recordingState}, " +
                        "projectionAlive=${mediaProjection != null}, $projDelta")
                Log.e(TAG, "AudioRecord.read() error $readCount — ending capture loop")
                break
            }

            // Append new frame to the rolling context buffer.
            val frame = readBuffer.copyOf(readCount)
            contextChunks.addLast(frame)
            contextSamples += frame.size

            // Trim the oldest frames to stay within the context window.
            while (contextSamples > maxContextSamples && contextChunks.isNotEmpty()) {
                contextSamples -= contextChunks.removeFirst().size
            }

            // Every CHUNK_DISPATCH_MS, snapshot the context buffer and send to ASR.
            val now = SystemClock.elapsedRealtime()
            if (now - lastDispatchMs >= CHUNK_DISPATCH_MS && contextSamples > 0) {
                lastDispatchMs = now

                // Flatten context chunks into a single array.
                val audio = ShortArray(contextSamples)
                var off = 0
                for (chunk in contextChunks) {
                    System.arraycopy(chunk, 0, audio, off, chunk.size)
                    off += chunk.size
                }

                // Silence gate: skip windows with no speech energy to prevent
                // Whisper from hallucinating filler text on quiet audio.
                val rms = computeRms(audio)
                if (ENABLE_TIMING_LOGS) {
                    Log.i(TAG, "TIMING | dispatch_ms=$CHUNK_DISPATCH_MS, ctx_samples=$contextSamples, rms=$rms")
                }
                if (rms >= SILENCE_RMS_THRESHOLD) {
                    audioChannel.trySend(
                        InferencePayload(
                            audio = audio,
                            isFinal = false,
                            enqueueTimeMs = SystemClock.elapsedRealtime()
                        )
                    )
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
     * Runs the full ASR → Translation → UI update pipeline for one streaming chunk.
     *
     * All blocking work (JNI transcription, network/ONNX translation) runs on
     * [Dispatchers.IO] so the Main thread is never blocked.
     *
     * @param audio  PCM-16 context window (up to [CONTEXT_WINDOW_MS] of audio).
     * @param prompt Previous transcription used as Whisper's `initial_prompt` for continuity.
     * @param isFinal Unused in streaming mode; kept for API compatibility.
     */
    private suspend fun processWindow(
        audio: ShortArray,
        prompt: String,
        isFinal: Boolean,
        queueDelayMs: Long
    ) {
        withContext(Dispatchers.IO) {
            val pipelineStart = SystemClock.elapsedRealtime()

            // ── 1. Speech-to-Text ────────────────────────────────────────────
            val asrStart = SystemClock.elapsedRealtime()
            val audioForAsr = if (audio.size > MAX_ASR_SAMPLES) {
                audio.copyOfRange(audio.size - MAX_ASR_SAMPLES, audio.size)
            } else {
                audio
            }
            val transcript = asrEngine.transcribe(audioForAsr, sourceLang, targetLang, prompt).trim()
            val asrMs = SystemClock.elapsedRealtime() - asrStart
            if (ENABLE_TIMING_LOGS) {
                Log.i(TAG, "TIMING | asr_ms=$asrMs, queue_ms=$queueDelayMs, " +
                    "audio_samples=${audioForAsr.size}, audio_sec=${"%.2f".format(audioForAsr.size / 16000f)}")
            }

            // Whisper sometimes emits noise/event markers instead of real speech, e.g.:
            //   "(spoken in foreign language)", "[SPEAKING FOREIGN LANGUAGE]", "(music)", "[BLANK_AUDIO]"
            // Drop them before they reach the UI or MT.
            val trimmedTranscript = transcript.trim()
            if (trimmedTranscript.isBlank() ||
                WHISPER_NOISE_MARKER_REGEX.containsMatchIn(trimmedTranscript)
            ) {
                Log.v(TAG, "No speech in window (whisper='$transcript') – skipping")
                return@withContext
            }

            // Keep Whisper prompt continuity for the next chunk.
            lastTranscript = transcript.takeLast(MAX_PROMPT_CHARS)

            // Extract only the new suffix compared to the previous chunk so we don't
            // re-translate the same context repeatedly.
            val novelTranscript = extractNovelSuffix(lastChunkTranscript, transcript)
            lastChunkTranscript = transcript
            if (novelTranscript.isBlank()) {
                Log.v(TAG, "Chunk produced no novel transcript suffix — skipping MT")
                return@withContext
            }

            // ── 2. Resolve actual source language for MT ──────────────────────
            val mtSourceLang = if (sourceLang == "auto") {
                resolveAutoSourceLangForMt(
                    detectedLang = asrEngine.detectedLanguage,
                    transcript = novelTranscript
                )
            } else {
                sourceLang
            }
            Log.d(TAG, "Novel transcript [src=$mtSourceLang]: $novelTranscript")

            // ── 3. Append original stream immediately (ASR fast path) ─────────
            streamedOriginalText = appendStreamingText(streamedOriginalText, novelTranscript)

            // Queue MT work asynchronously so MT latency doesn't block ASR.
            val enqueueNow = SystemClock.elapsedRealtime()
            synchronized(translationLock) {
                val wasEmpty = pendingTranslationText.isBlank()
                pendingTranslationText = appendStreamingText(pendingTranslationText, novelTranscript)
                pendingTranslationSourceLang = mtSourceLang
                if (wasEmpty) {
                    pendingTranslationEnqueueMs = enqueueNow
                }
            }
            translationSignal.trySend(Unit)

            // ── 4. Broadcast/update UI with latest available translation ───────
            broadcastSubtitle(streamedOriginalText, streamedTranslatedText)

            // ── 5. Update the floating overlay (must be on Main thread) ────────
            withContext(Dispatchers.Main) {
                floatingView.updateSubtitle(
                    originalText   = streamedOriginalText,
                    translatedText = streamedTranslatedText
                )
            }

            if (ENABLE_TIMING_LOGS) {
                val pipelineMs = SystemClock.elapsedRealtime() - pipelineStart
                Log.i(TAG, "TIMING | pipeline_total=${pipelineMs}ms " +
                    "(queue=$queueDelayMs + asr=$asrMs + extract+queue) " +
                    "e2e_from_capture=${pipelineMs + queueDelayMs}ms")
            }
        }
    }

    /**
     * Returns only the new suffix from [current], removing the largest
     * suffix/prefix overlap with [previous]. Handles both spaced and CJK text.
     */
    private fun extractNovelSuffix(previous: String, current: String): String {
        if (previous.isBlank()) return current.trim()
        if (current == previous) return ""

        val prev = previous.trim()
        val curr = current.trim()
        if (curr.startsWith(prev)) {
            return curr.removePrefix(prev).trim()
        }

        val maxOverlap = minOf(prev.length, curr.length, MAX_OVERLAP_SCAN_CHARS)
        for (k in maxOverlap downTo 1) {
            if (prev.takeLast(k) == curr.take(k)) {
                return curr.drop(k).trim()
            }
        }
        return curr
    }

    private fun appendStreamingText(base: String, addition: String): String {
        val clean = addition.trim()
        if (clean.isBlank()) return base
        if (base.isBlank()) return clean.takeLast(MAX_STREAM_TEXT_CHARS)

        val spacer = if (needsSeparator(base.last(), clean.first())) " " else ""
        return (base + spacer + clean).takeLast(MAX_STREAM_TEXT_CHARS)
    }

    private fun needsSeparator(left: Char, right: Char): Boolean {
        return left.isLetterOrDigit() && right.isLetterOrDigit()
    }

    private fun resolveAutoSourceLangForMt(detectedLang: String, transcript: String): String {
        detectScriptLanguageHint(transcript)?.let { return it }

        val normalized = detectedLang.trim().lowercase()
        if (normalized in LOW_CONFIDENCE_AUTO_DETECTIONS) {
            return "auto"
        }
        return normalized.ifBlank { "auto" }
    }

    private fun detectScriptLanguageHint(text: String): String? {
        var hasKana = false
        var hasHangul = false
        var hasCjk = false
        var hasArabic = false
        var hasCyrillic = false
        var hasDevanagari = false

        for (ch in text) {
            when {
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> hasKana = true
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> hasHangul = true
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> hasCjk = true
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' -> hasArabic = true
                ch in '\u0400'..'\u04FF'                              -> hasCyrillic = true
                ch in '\u0900'..'\u097F'                              -> hasDevanagari = true
            }
        }

        return when {
            hasKana       -> "ja"
            hasHangul     -> "ko"
            hasCjk        -> "zh"
            hasArabic     -> "ar"
            hasCyrillic   -> "ru"
            hasDevanagari -> "hi"
            else          -> null
        }
    }

    /** Broadcasts the latest subtitle text so [MainActivity]'s PiP caption view stays in sync. */
    private fun broadcastSubtitle(original: String, translated: String) {
        latestOriginalText = original
        latestTranslatedText = translated
        sendBroadcast(
            Intent(ACTION_SUBTITLE_UPDATED)
                .setPackage(packageName)
                .putExtra(EXTRA_SUBTITLE_ORIGINAL, original)
                .putExtra(EXTRA_SUBTITLE_TRANSLATED, translated)
        )
    }

    private fun selectAsrModelFilename(): String {
        val wantMultilingual = sourceLang == "auto" || !sourceLang.equals("en", ignoreCase = true)
        if (ENABLE_TIMING_LOGS) {
            Log.i(TAG, "ASR assets available: tiny=${isAsrModelAvailable(FAST_ASR_MODEL_FILENAME)}, " +
                "tiny.en=${isAsrModelAvailable(FAST_ASR_EN_MODEL_FILENAME)}, " +
                "medium=${isAsrModelAvailable(QUALITY_ASR_MODEL_FILENAME)}")
        }
        return when {
            wantMultilingual && isAsrModelAvailable(FAST_ASR_MODEL_FILENAME) ->
                FAST_ASR_MODEL_FILENAME
            !wantMultilingual && isAsrModelAvailable(FAST_ASR_EN_MODEL_FILENAME) ->
                FAST_ASR_EN_MODEL_FILENAME
            isAsrModelAvailable(FAST_ASR_MODEL_FILENAME) ->
                FAST_ASR_MODEL_FILENAME
            isAsrModelAvailable(FAST_ASR_EN_MODEL_FILENAME) ->
                FAST_ASR_EN_MODEL_FILENAME
            else -> {
                Log.w(TAG, "Fast ASR model missing; falling back to $QUALITY_ASR_MODEL_FILENAME (high latency). " +
                    "Add ggml-tiny.bin to assets or external storage for real-time.")
                QUALITY_ASR_MODEL_FILENAME
            }
        }
    }

    private fun isAsrModelAvailable(filename: String): Boolean {
        val external = getExternalFilesDir(null)?.resolve(filename)
        if (external?.exists() == true) return true
        return try {
            val assetsList = assets.list("")
            assetsList?.contains(filename) == true
        } catch (e: Exception) {
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MediaProjection callback
    // ──────────────────────────────────────────────────────────────────────

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (isDestroying || projectionStopHandled) {
                Log.i(TAG, "MediaProjection.onStop ignored (isDestroying=$isDestroying, handled=$projectionStopHandled)")
                return
            }
            projectionStopHandled = true

            projectionStopTimeMs = SystemClock.elapsedRealtime()
            val uptimeSec = if (serviceStartTimeMs > 0)
                "%.1fs".format((projectionStopTimeMs - serviceStartTimeMs) / 1000.0) else "N/A"
            Log.w(TAG, "DIAG | MediaProjection.onStop() — uptime=$uptimeSec since service start, thread=${Thread.currentThread().name}")
            Log.w(TAG, "MediaProjection stopped by system — grace period ${PROJECTION_GRACE_PERIOD_MS / 1000}s before shutdown")
            isRunning = false
            broadcastCaptureState(CAPTURE_STATE_DISCONNECTED)

            // ── Immediately stop AudioRecord to prevent native crash ────────
            // When the projection dies, AudioFlinger invalidates the record
            // track. If the capture loop calls read() on the dead track, the
            // internal restoreRecord_l retries fail and the shared buffer gets
            // into an inconsistent state, triggering a fatal
            // "releaseBuffer: mUnreleased out of range" abort in native code.
            // Cancel the capture job first, then stop+release AudioRecord
            // synchronously so no further reads can occur.
            captureJob?.cancel()
            try {
                audioRecord?.apply { stop(); release() }
                audioRecord = null
                Log.i(TAG, "AudioRecord stopped+released in onStop (crash prevention)")
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord cleanup in onStop failed", e)
            }

            // Show a message on the floating view so the user knows what happened.
            mainHandler.post {
                if (::floatingView.isInitialized) {
                    floatingView.updateSubtitle(
                        "Disconnected",
                        "Disconnected - tap to restart."
                    )
                }
            }

            // Don't kill the service immediately — give the user time to notice
            // and reopen the app.
            pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingStopRunnable = Runnable {
                Log.i(TAG, "Grace period expired — stopping service")
                stopSelf()
            }
            mainHandler.postDelayed(pendingStopRunnable!!, PROJECTION_GRACE_PERIOD_MS)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "AudioCaptureService"

        /**
         * True while the service is actively capturing and translating audio.
         * Set to true after AudioRecord starts successfully; cleared in onDestroy().
         * MainActivity reads this in onResume() to keep its button in sync even
         * when the service stopped unexpectedly (projection revoked, crash, etc.).
         */
        @Volatile var isRunning: Boolean = false
        @Volatile var latestCaptureState: String = CAPTURE_STATE_IDLE

        // Latest subtitle text — read by MainActivity to pre-populate the PiP view.
        @Volatile var latestOriginalText: String = ""
        @Volatile var latestTranslatedText: String = ""

        const val ACTION_STOP = "com.liveTranslation.ACTION_STOP"
        const val ACTION_CAPTURE_STATE_CHANGED = "com.liveTranslation.ACTION_CAPTURE_STATE_CHANGED"
        const val EXTRA_CAPTURE_STATE = "capture_state"

        /** Broadcast sent every time a new subtitle is ready; received by [MainActivity] for PiP. */
        const val ACTION_SUBTITLE_UPDATED    = "com.liveTranslation.ACTION_SUBTITLE_UPDATED"
        const val EXTRA_SUBTITLE_ORIGINAL    = "subtitle_original"
        const val EXTRA_SUBTITLE_TRANSLATED  = "subtitle_translated"

        const val CAPTURE_STATE_IDLE         = "idle"
        const val CAPTURE_STATE_LISTENING    = "listening"
        const val CAPTURE_STATE_DISCONNECTED = "disconnected"
        const val CAPTURE_STATE_STOPPED      = "stopped"

        const val EXTRA_RESULT_DATA    = "result_data"
        const val EXTRA_SOURCE_LANG    = "source_lang"
        const val EXTRA_TARGET_LANG    = "target_lang"
        const val EXTRA_SHOW_ORIGINAL  = "show_original"
        const val EXTRA_USE_GPT        = "use_gpt"
        const val EXTRA_API_KEY        = "api_key"

        // ── Audio parameters ──────────────────────────────────────────
        /** Whisper.cpp requires exactly 16 000 Hz mono PCM-16. */
        const val SAMPLE_RATE = 16_000

        /** Size of each AudioRecord read block in wall-clock time. */
        const val READ_WINDOW_MS = 100L
        val READ_SIZE_SAMPLES = ((SAMPLE_RATE * READ_WINDOW_MS) / 1000).toInt()  // 1 600

        // ── Streaming chunk parameters ────────────────────────────────
        /**
         * How often to dispatch a context snapshot to the ASR pipeline.
         * Lower = lower first-word latency; higher = better word accuracy.
         * 900 ms lowers first-word latency while still giving Whisper enough
         * audio to decode reliably on most devices.
         */
        const val CHUNK_DISPATCH_MS = 450L

        /**
         * Length of the rolling audio context kept for each Whisper call.
         * Longer context improves accuracy at word boundaries without adding
         * extra end-to-end latency (the chunk timer drives dispatch frequency).
         */
        const val CONTEXT_WINDOW_MS = 1_600L

        /** RMS energy gate — windows below this threshold are skipped so Whisper
         *  doesn't hallucinate filler text during silence. */
        const val SILENCE_RMS_THRESHOLD = 100.0

        // ── Context prompting ─────────────────────────────────────────
        /** Max characters from the previous transcript to keep as Whisper's initial_prompt. */
        const val MAX_PROMPT_CHARS = 200
        /** Max chars to scan when removing chunk overlaps. */
        const val MAX_OVERLAP_SCAN_CHARS = 220
        /** Max subtitle length kept in the streaming overlay buffer. */
        const val MAX_STREAM_TEXT_CHARS = 280
        /** Max audio fed into ASR per chunk (ms). */
        const val MAX_ASR_AUDIO_MS = 1_200L
        val MAX_ASR_SAMPLES = ((SAMPLE_RATE * MAX_ASR_AUDIO_MS) / 1000).toInt()
        /** ASR model filenames for fast/quality modes. */
        // ── Whisper n_threads tuning ─────────────────────────────────
        /** CPU thread count for whisper.cpp inference.
         *  A/B test: change to 2, 4, or 6, rebuild, and compare the
         *  "PERF | whisper_transcribe" and "PERF | whisper_full" logcat lines.
         *
         *  Snapdragon 8 Elite (S25 Ultra): 2P@4.32GHz + 6P@3.53GHz
         *    - n_threads=2: lowest power, ~120ms on ggml-tiny (1.2s audio)
         *    - n_threads=4: balanced, ~80ms (recommended)
         *    - n_threads=6: fastest, ~65ms but higher thermal risk on long sessions
         */
        const val WHISPER_N_THREADS = 4

        const val FAST_ASR_MODEL_FILENAME = "ggml-tiny.bin"
        const val FAST_ASR_EN_MODEL_FILENAME = "ggml-tiny.en.bin"
        const val QUALITY_ASR_MODEL_FILENAME = "ggml-medium.bin"
        const val FALLBACK_ASR_MODEL_FILENAME = "ggml-tiny.en.bin"
        /** Enable verbose timing logs for latency diagnosis. */
        const val ENABLE_TIMING_LOGS = true
        private val LOW_CONFIDENCE_AUTO_DETECTIONS = setOf("af", "cy", "so", "la")

        /**
         * Matches Whisper's parenthetical/bracketed noise or event markers that
         * indicate non-speech audio content — e.g.:
         *   "(spoken in foreign language)", "[SPEAKING FOREIGN LANGUAGE]",
         *   "(music)", "(applause)", "(laughter)", "[BLANK_AUDIO]"
         */
        private val WHISPER_NOISE_MARKER_REGEX = Regex(
            """(?:\(\s*(?:spoken|speaking)\s+in\s+[^)]+\s*\)|\[\s*(?:spoken|speaking)\s+in\s+[^\]]+\s*\]|\(\s*(?:music|applause|laughter|silence|background[^)]*|crowd[^)]*|noise[^)]*|inaudible[^)]*|foreign language|unintelligible)\s*\)|\[\s*(?:music|applause|laughter|silence|background[^\]]*|crowd[^\]]*|noise[^\]]*|inaudible[^\]]*|foreign language|unintelligible)\s*\]|\[BLANK_AUDIO\]|\b(?:spoken|speaking)\s+in\s+foreign language\b)""",
            RegexOption.IGNORE_CASE
        )

        // ── Projection lifecycle ──────────────────────────────────────
        /** How long to keep the service alive after MediaProjection is revoked (ms). */
        const val PROJECTION_GRACE_PERIOD_MS = 30_000L

        private const val RESULT_OK = android.app.Activity.RESULT_OK

        /** Build the Intent needed to start this service from MainActivity. */
        fun buildStartIntent(
            context: android.content.Context,
            projectionResultData: Intent,
            sourceLang: String = "auto",
            targetLang: String = "en",
            showOriginal: Boolean = false,
            useGpt: Boolean = true,
            apiKey: String = ""
        ) = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_DATA, projectionResultData)
            putExtra(EXTRA_SOURCE_LANG, sourceLang)
            putExtra(EXTRA_TARGET_LANG, targetLang)
            putExtra(EXTRA_SHOW_ORIGINAL, showOriginal)
            putExtra(EXTRA_USE_GPT, useGpt)
            putExtra(EXTRA_API_KEY, apiKey)
        }
    }
}
