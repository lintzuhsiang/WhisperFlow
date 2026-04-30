package com.liveTranslation.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.liveTranslation.ai.TranslationEngine
import com.liveTranslation.ai.SpeechToTextEngine
import com.liveTranslation.ai.LanguageDetectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * Hidden audio-only ExoPlayer that runs ahead of the visible player.
 *
 * PCM windows are intercepted by [WhisperAudioProcessor], then sent through
 * a two-stage pipeline:
 *
 * 1) ASR stage (resample + Whisper)
 * 2) MT stage  (translation + subtitle store)
 *
 * Decoupling ASR and MT prevents MT latency spikes from blocking new ASR work.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HiddenTrackPlayer(
    private val context: Context,
    private val sttEngine: SpeechToTextEngine,
    private val translationEngine: TranslationEngine,
    private val subtitleStore: SubtitleStore,
    private val sourceLang: String,
    private val targetLang: String
) {
    // Audio processor (intercepts PCM in ExoPlayer pipeline)
    val processor = WhisperAudioProcessor()

    // ExoPlayer (audio-only, muted)
    val exoPlayer: ExoPlayer

    // Coroutine infrastructure
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val windowChannel = Channel<PcmWindow>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val translationChannel = Channel<TranscriptWindow>(
        capacity = TRANSLATION_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var asrJob: Job? = null
    private var translationJob: Job? = null

    // Seek invalidation
    @Volatile var generationId: Int = 0; private set

    // Status
    @Volatile var decodedUpToMs: Long = 0L; private set
    @Volatile var isLoaded: Boolean = false; private set

    /** Callback for hidden player errors. Called on main thread by ExoPlayer. */
    var onErrorListener: ((PlaybackException) -> Unit)? = null

    /**
     * Dynamic lead recommendation derived from actual ASR+MT latency.
     * The visible player can use this as the expected hidden-track lead.
     */
    @Volatile var recommendedLeadMs: Long = INITIAL_RECOMMENDED_LEAD_MS; private set

    // ── Language lock (Scheme A: priming-phase whisper_lang_auto_detect) ─
    //
    // Design rationale
    // ────────────────
    // Whisper's per-window language detection is unreliable on short (2-3s)
    // windows — it routinely misclassifies English as Malay, Japanese as
    // Russian, music as Afrikaans, etc. This causes cascading failures:
    //   1. wrong Whisper decode (wrong token distribution)
    //   2. wrong NLLB source-language tag → translation silently no-ops
    //   3. user sees source-language text instead of target-language text
    //
    // Fix: during the first 15-30s of playback, BUFFER PCM windows instead
    // of running ASR. Once enough audio is accumulated, run ONE pass of
    // `whisper_lang_auto_detect` on the concatenated buffer (which has a
    // proper 30s receptive field). Lock the result and pass it as the
    // `language` argument to all subsequent whisper_full() calls.
    //
    // Flushing: once locked, the buffered windows are drained back through
    // the ASR pipeline so the user eventually sees subtitles for the
    // priming period too (with a ~15-30s initial delay that the visible
    // player has absorbed via playback-ahead).

    /**
     * Locked source language for all subsequent ASR calls. `null` while still
     * buffering for detection. Set exactly once (per load), either from the
     * explicit sourceLang parameter or from the detection pass.
     * Exposed read-only so UI can display the detected language.
     */
    @Volatile var lockedSourceLang: String? = null; private set

    /** Confidence of the last language-detection run. 0f until detection runs. */
    @Volatile var lockedSourceConfidence: Float = 0f; private set

    /**
     * Fired once, on the ASR coroutine, when language detection completes
     * and `lockedSourceLang` is assigned. `accepted=false` means confidence
     * was too low even at 30s — we fell back to "auto" (per-window).
     *
     * Callback may be invoked on a background thread; handlers that touch UI
     * must dispatch to main.
     */
    var onLanguageLocked: ((lang: String, confidence: Float, accepted: Boolean) -> Unit)? = null

    // ── Priming buffer state (ASR coroutine ONLY — no sync needed) ──────
    private val primingBuffer = ArrayList<PrimedWindow>(8)
    private var primingBufferMs: Long = 0L

    private data class PcmWindow(
        val startMs: Long,
        val endMs: Long,
        val pcm: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val generationId: Int,
        val enqueueTimeMs: Long
    )

    /** Already-resampled 16kHz mono PCM window held during the priming phase. */
    private data class PrimedWindow(
        val startMs: Long,
        val endMs: Long,
        val pcm16k: ShortArray,
        val generationId: Int,
        val enqueueTimeMs: Long
    )

    private data class TranscriptWindow(
        val startMs: Long,
        val endMs: Long,
        val transcript: String,
        val resolvedSourceLang: String,
        val generationId: Int,
        val enqueueTimeMs: Long
    )

    init {
        // If user explicitly picked a source language, there is nothing to
        // detect — skip the priming-buffer phase entirely and let ASR run
        // normally from window #1. We also treat the explicit selection as
        // the "lock" so downstream code can uniformly read `lockedSourceLang`.
        if (!sourceLang.equals("auto", ignoreCase = true)) {
            lockedSourceLang = sourceLang
            lockedSourceConfidence = 1f  // user-provided ≈ ground truth
            Log.i(TAG, "Source language explicit: '$sourceLang' — skipping priming detection")
        } else {
            Log.i(TAG, "Source language auto — will run detection after " +
                "${LANG_DETECT_MIN_MS}ms of audio buffered (max ${LANG_DETECT_MAX_MS}ms)")
        }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        // Limit hidden player's network buffer to prevent over-buffering (especially HLS).
        // Default ExoPlayer buffers up to 50s; we only need ~10s ahead.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs =                     */ 2_000,  // must be >= bufferForPlaybackAfterRebufferMs
                /* maxBufferMs =                     */ 10_000,
                /* bufferForPlaybackMs =             */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000
            )
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            volume = 0f

            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false
            )

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Hidden player error: ${error.message}", error)
                    onErrorListener?.invoke(error)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateName = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                    Log.d(TAG, "Hidden player state: $stateName")
                }
            })
        }

        processor.onWindowReady = { startMs, endMs, pcm, sampleRate, channels ->
            val gen = generationId

            // Update decodedUpToMs immediately when PCM is emitted.
            // This represents the true ExoPlayer decode position — audio has
            // been decoded up to endMs regardless of whether Whisper/NLLB have
            // processed it yet.  The DualTrackPlayer drift checker uses this
            // value to decide if the hidden track is keeping up; updating it
            // here (instead of after ASR) prevents false "behind" detections
            // that would stall or seek the visible player.
            decodedUpToMs = max(decodedUpToMs, endMs)

            val result = windowChannel.trySend(
                PcmWindow(
                    startMs = startMs,
                    endMs = endMs,
                    pcm = pcm,
                    sampleRate = sampleRate,
                    channels = channels,
                    generationId = gen,
                    enqueueTimeMs = SystemClock.elapsedRealtime()
                )
            )
            if (result.isFailure) {
                Log.w(TAG, "ASR queue full, dropping window ${startMs}..${endMs}ms (gen=$gen)")
            }
        }

        startProcessingLoops()
        Log.i(TAG, "Initialised")
    }

    fun load(mediaItem: MediaItem, startPositionMs: Long, headers: Map<String, String> = emptyMap()) {
        generationId++
        decodedUpToMs = startPositionMs
        recommendedLeadMs = INITIAL_RECOMMENDED_LEAD_MS
        isLoaded = true

        // Reset language-detection state for the new media. If the user
        // picked an explicit source lang, the init-time lock still holds;
        // only auto-mode needs a fresh detection pass per video.
        // primingBuffer itself is owned by the ASR coroutine — it will
        // detect the generationId change and clear itself naturally
        // (see processAsrWindow's stale-gen guard).
        if (sourceLang.equals("auto", ignoreCase = true)) {
            lockedSourceLang = null
            lockedSourceConfidence = 0f
            Log.i(TAG, "Language lock reset for new media load (auto-detect mode)")
        }

        processor.setBasePositionMs(startPositionMs)

        // If custom headers (e.g. Referer) are provided, create a MediaSource
        // with those headers so the CDN doesn't reject the request.
        if (headers.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItem)
            exoPlayer.setMediaSource(mediaSource)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
        exoPlayer.seekTo(startPositionMs)
        exoPlayer.prepare()

        Log.i(
            TAG,
            "load: uri=${mediaItem.localConfiguration?.uri}, mime=${mediaItem.localConfiguration?.mimeType}, " +
                "startPos=${startPositionMs}ms, headers=${headers.size}, gen=$generationId"
        )
    }

    fun play() {
        exoPlayer.playWhenReady = true
        Log.d(TAG, "play")
    }

    fun pause() {
        exoPlayer.playWhenReady = false
        Log.d(TAG, "pause")
    }

    fun seekTo(positionMs: Long) {
        generationId++
        decodedUpToMs = positionMs
        processor.setBasePositionMs(positionMs)
        exoPlayer.seekTo(positionMs)
        Log.i(TAG, "seekTo: ${positionMs}ms, gen=$generationId")
    }

    fun release() {
        Log.i(TAG, "Releasing...")
        // CRITICAL: a running ASR coroutine may be inside whisper.cpp's JNI
        // call when release() fires. `scope.cancel()` returns immediately
        // and does NOT interrupt native code, so the worker thread keeps
        // running and could touch whisper_context after a sibling
        // Activity/Engine has freed it → SIGSEGV in
        // ggml_compute_forward_add_non_quantized.
        //
        // Wait (briefly) for the ASR job to finish whatever JNI call is in
        // flight before tearing down ExoPlayer + the processor callback.
        val joinJobs = listOfNotNull(asrJob, translationJob)
        if (joinJobs.isNotEmpty()) {
            runBlocking {
                withTimeoutOrNull(WHISPER_RELEASE_JOIN_TIMEOUT_MS) {
                    joinJobs.forEach { it.cancelAndJoin() }
                }
            }
        }
        scope.cancel()
        windowChannel.close()
        translationChannel.close()
        exoPlayer.release()
        processor.onWindowReady = null
        Log.i(TAG, "Released")
    }

    private fun startProcessingLoops() {
        asrJob = scope.launch {
            for (window in windowChannel) {
                processAsrWindow(window)
            }
        }

        translationJob = scope.launch {
            for (window in translationChannel) {
                processTranslationWindow(window)
            }
        }
    }

    private suspend fun processAsrWindow(window: PcmWindow) {
        val gen = window.generationId

        val mono16k = AudioResampler.resampleToWhisper(
            window.pcm,
            window.sampleRate,
            window.channels
        )
        if (mono16k.isEmpty()) return

        if (gen != generationId) {
            Log.d(TAG, "STALE | resample done but gen=$gen, current=$generationId, discarding")
            return
        }

        // ── Priming gate ─────────────────────────────────────────────
        // While `lockedSourceLang` is null we are still in the language-
        // detection priming phase: buffer windows instead of running ASR,
        // and once we've accumulated enough audio, run one detection pass.
        // After lock, flush all buffered windows through the ASR pipeline
        // so the user eventually gets subtitles for the priming period too.
        if (lockedSourceLang == null) {
            bufferForPriming(window, mono16k, gen)
            return
        }
        // ─────────────────────────────────────────────────────────────

        runAsrAndEnqueueMt(
            startMs       = window.startMs,
            endMs         = window.endMs,
            pcm16k        = mono16k,
            generationId  = gen,
            enqueueTimeMs = window.enqueueTimeMs,
            srcLangForAsr = lockedSourceLang!!
        )
    }

    /**
     * Append a window to the priming buffer and, once we cross the minimum
     * audio threshold, attempt a language-detection pass. If detection is
     * still low-confidence at [LANG_DETECT_MAX_MS], we lock to "auto" as a
     * safety fallback (preserving the pre-Scheme-A per-window behaviour).
     *
     * Called from and only from the ASR coroutine — no synchronisation
     * needed on primingBuffer / primingBufferMs.
     */
    private suspend fun bufferForPriming(window: PcmWindow, mono16k: ShortArray, gen: Int) {
        // If a prior buffered window is from a stale generation (e.g. user
        // seeked before detection completed), drop the whole buffer and
        // start fresh from the current generation.
        if (primingBuffer.isNotEmpty() && primingBuffer.first().generationId != gen) {
            Log.i(TAG, "Priming buffer reset: gen changed " +
                "${primingBuffer.first().generationId} → $gen (discarding ${primingBuffer.size} windows)")
            primingBuffer.clear()
            primingBufferMs = 0L
        }

        primingBuffer.add(
            PrimedWindow(
                startMs        = window.startMs,
                endMs          = window.endMs,
                pcm16k         = mono16k,
                generationId   = gen,
                enqueueTimeMs  = window.enqueueTimeMs
            )
        )
        primingBufferMs += (window.endMs - window.startMs).coerceAtLeast(0L)

        Log.d(TAG, "Priming buffer: ${primingBufferMs}ms across ${primingBuffer.size} windows " +
            "(need ≥${LANG_DETECT_MIN_MS}ms)")

        if (primingBufferMs >= LANG_DETECT_MIN_MS) {
            tryLockLanguage()
        }
    }

    /**
     * Run `whisper_lang_auto_detect` over the concatenated priming buffer.
     * Commits the lock + flushes buffered windows if the result is
     * confident enough; otherwise waits for more audio (up to
     * [LANG_DETECT_MAX_MS]), at which point we commit to "auto" as a
     * last-resort fallback.
     *
     * Runs on the ASR coroutine. Within a single HiddenTrackPlayer instance
     * this is the only caller into whisper.cpp, so within the instance there
     * is no concurrent transcribe(). However whisper_context is NOT
     * thread-safe across instances (e.g. release() racing with a freshly
     * spawned activity), which is why [WhisperCppEngine] carries
     * `@Synchronized` on its public entry points as the final guard.
     */
    private suspend fun tryLockLanguage() {
        if (primingBuffer.isEmpty()) return

        val snapshotGen = primingBuffer.first().generationId
        if (snapshotGen != generationId) {
            Log.d(TAG, "tryLockLanguage: buffer is stale (gen=$snapshotGen vs $generationId), skipping")
            return
        }

        // Concatenate buffered 16kHz mono PCM for a single detection call.
        // Detection reads at most first 30s of samples so upper-bound the copy.
        val maxSamples = (AudioResampler.TARGET_RATE * (LANG_DETECT_MAX_MS / 1000L)).toInt()
        var totalSamples = 0
        for (w in primingBuffer) totalSamples += w.pcm16k.size
        val copySamples = totalSamples.coerceAtMost(maxSamples)

        val concat = ShortArray(copySamples)
        var pos = 0
        for (w in primingBuffer) {
            val space = copySamples - pos
            if (space <= 0) break
            val take = w.pcm16k.size.coerceAtMost(space)
            w.pcm16k.copyInto(concat, pos, 0, take)
            pos += take
        }

        val bufferedMs = primingBufferMs
        val detection: LanguageDetectionResult = sttEngine.detectLanguage(concat)
            ?: run {
                // Engine doesn't support priming detection (e.g. cloud STT).
                // Lock to the user-supplied source language (or "auto"). The
                // engine's transcribe() will handle per-window detection.
                val finalLang = if (sourceLang.isBlank()) "auto" else sourceLang
                lockedSourceLang = finalLang
                lockedSourceConfidence = 0f
                Log.i(TAG, "Engine doesn't need priming — locking sourceLang='$finalLang' immediately")
                onLanguageLocked?.invoke(finalLang, 0f, true)

                // Flush buffered windows through the normal pipeline so users
                // see subtitles for the priming period too.
                val flushBatch = ArrayList(primingBuffer)
                primingBuffer.clear()
                primingBufferMs = 0L
                for (w in flushBatch) {
                    if (w.generationId != generationId) continue
                    runAsrAndEnqueueMt(
                        startMs       = w.startMs,
                        endMs         = w.endMs,
                        pcm16k        = w.pcm16k,
                        generationId  = w.generationId,
                        enqueueTimeMs = w.enqueueTimeMs,
                        srcLangForAsr = finalLang
                    )
                }
                return
            }

        // Re-check gen after the detection call (it can take 50-500ms).
        if (generationId != snapshotGen) {
            Log.d(TAG, "tryLockLanguage: gen changed during detection, discarding result")
            return
        }

        val decisive = detection.isValid &&
            detection.confidence >= LANG_DETECT_MIN_CONFIDENCE &&
            detection.lang !in LOW_CONFIDENCE_AUTO_DETECTIONS &&
            detection.margin >= LANG_DETECT_MIN_MARGIN

        Log.i(TAG, "lang_detect result: top=${detection.lang}(${"%.3f".format(detection.confidence)}), " +
            "2nd=${detection.secondLang}(${"%.3f".format(detection.secondConfidence)}), " +
            "margin=${"%.3f".format(detection.margin)}, buffered=${bufferedMs}ms, decisive=$decisive")

        // Still below max audio → keep accumulating and retry on next window.
        if (!decisive && bufferedMs < LANG_DETECT_MAX_MS) {
            Log.w(TAG, "Language detection not yet decisive (conf=${"%.3f".format(detection.confidence)}, " +
                "lang='${detection.lang}'); continuing to accumulate " +
                "(${bufferedMs}/${LANG_DETECT_MAX_MS}ms)")
            return
        }

        // Commit the lock. If still not decisive at max audio, fall back to
        // "auto" so per-window detection takes over (old behaviour). This is
        // a SAFETY NET, not the happy path.
        val finalLang  = if (decisive) detection.lang else "auto"
        val finalConf  = detection.confidence
        lockedSourceLang       = finalLang
        lockedSourceConfidence = finalConf

        Log.i(TAG, "Language LOCKED: lang='$finalLang' confidence=${"%.3f".format(finalConf)} " +
            "audio=${bufferedMs}ms decisive=$decisive (2nd='${detection.secondLang}' " +
            "${"%.3f".format(detection.secondConfidence)})")

        onLanguageLocked?.invoke(finalLang, finalConf, decisive)

        // ── Flush buffered windows through the normal ASR→MT pipeline ──
        // Snapshot + clear FIRST so that any re-entrant calls see an empty
        // buffer and don't double-process. New windows arriving during the
        // flush go straight to the locked path via processAsrWindow.
        val flushBatch = ArrayList(primingBuffer)
        primingBuffer.clear()
        primingBufferMs = 0L

        val asrSrcLang = if (decisive) finalLang else "auto"
        Log.i(TAG, "Flushing ${flushBatch.size} buffered windows with srcLang='$asrSrcLang'")

        for (w in flushBatch) {
            if (w.generationId != generationId) {
                Log.d(TAG, "Flush skip (stale gen ${w.generationId}): ${w.startMs}..${w.endMs}ms")
                continue
            }
            runAsrAndEnqueueMt(
                startMs       = w.startMs,
                endMs         = w.endMs,
                pcm16k        = w.pcm16k,
                generationId  = w.generationId,
                enqueueTimeMs = w.enqueueTimeMs,
                srcLangForAsr = asrSrcLang
            )
        }
    }

    /**
     * Run whisper.cpp ASR on the already-resampled 16 kHz mono PCM [pcm16k],
     * filter out empty/noise transcripts, and enqueue the result to the
     * translation stage. Factored out so both the normal path and the
     * priming-flush path share identical downstream logic.
     *
     * Passing an explicit [srcLangForAsr] lets the caller force Whisper to
     * skip its per-window auto-detection once we've locked a language.
     */
    private suspend fun runAsrAndEnqueueMt(
        startMs: Long,
        endMs: Long,
        pcm16k: ShortArray,
        generationId: Int,
        enqueueTimeMs: Long,
        srcLangForAsr: String
    ) {
        val transcript = sttEngine.transcribe(pcm16k, srcLangForAsr, targetLang, "").trim()

        if (generationId != this.generationId) {
            Log.d(TAG, "STALE | ASR done but gen=$generationId, current=${this.generationId}, discarding")
            return
        }

        if (transcript.isBlank()) {
            Log.d(TAG, "Empty transcript at ${startMs}ms, skipping (decodedUpToMs=$decodedUpToMs)")
            return
        }

        // Whisper sometimes emits noise/event markers instead of real speech, e.g.:
        //   "(spoken in foreign language)", "(music)", "(applause)", "(laughter)"
        // These look like transcription but carry no translatable content.
        // Drop them before sending to NLLB to avoid spurious "translation failed" logs.
        if (WHISPER_NOISE_MARKER_REGEX.containsMatchIn(transcript)) {
            Log.d(TAG, "Noise marker at ${startMs}ms, skipping: '$transcript'")
            return
        }

        val resolvedSourceLang = resolveSourceLangForWindow(transcript)
        val result = translationChannel.trySend(
            TranscriptWindow(
                startMs = startMs,
                endMs = endMs,
                transcript = transcript,
                resolvedSourceLang = resolvedSourceLang,
                generationId = generationId,
                enqueueTimeMs = enqueueTimeMs
            )
        )
        if (result.isFailure) {
            Log.w(TAG, "MT queue full, dropping transcript ${startMs}..${endMs}ms (gen=$generationId)")
        }
    }

    private suspend fun processTranslationWindow(window: TranscriptWindow) {
        val gen = window.generationId

        if (gen != generationId) {
            Log.d(TAG, "STALE | before MT gen=$gen, current=$generationId, discarding")
            return
        }

        val shouldBypassTranslation =
            !sourceLang.equals("auto", ignoreCase = true) &&
                normalizedEquals(window.resolvedSourceLang, targetLang)

        val translated = if (shouldBypassTranslation) {
            window.transcript
        } else {
            // MT source-language strategy:
            //   - User selected a specific language (not auto): trust the spinner value
            //     and pass it directly to NLLB so the correct language tag is used.
            //   - User selected auto-detect: use the per-window resolved source
            //     language from transcript script analysis, and fall back to "auto"
            //     for low-confidence Whisper detections.
            val mtSourceLang = if (sourceLang.equals("auto", ignoreCase = true)) {
                window.resolvedSourceLang
            } else {
                sourceLang   // e.g. "ja", "zh", "ko" — maps cleanly to NLLB tags
            }
            Log.d(TAG, "MT: input='${window.transcript.take(40)}…' " +
                "src=${sourceLang}->${mtSourceLang} tgt=${targetLang} " +
                "window=${window.startMs}..${window.endMs}ms")
            translationEngine.translate(window.transcript, mtSourceLang, targetLang).trim()
        }


        if (gen != generationId) {
            Log.d(TAG, "STALE | MT done but gen=$gen, current=$generationId, discarding")
            return
        }

        if (translated.isBlank()) {
            Log.d(TAG, "Blank translation at ${window.startMs}ms, skipping")
            return
        }

        val rawDurationMs = (window.endMs - window.startMs).coerceAtLeast(MIN_WINDOW_DURATION_MS)
        val displayStartMs = window.startMs + rawDurationMs / 2
        val displayEndMs = window.endMs + max(SUBTITLE_TRAIL_HOLD_MS, rawDurationMs / 3)

        subtitleStore.put(displayStartMs, displayEndMs, window.transcript, translated)
        // NOTE: decodedUpToMs is now updated at PCM emission time.
        // No need to update it here after translation completes.

        val elapsed = SystemClock.elapsedRealtime() - window.enqueueTimeMs
        updateRecommendedLead(elapsed, rawDurationMs)

        Log.i(
            TAG,
            "PERF | hidden_track: window=${window.startMs}..${window.endMs}ms, " +
                "display=${displayStartMs}..${displayEndMs}ms, " +
                "e2e=${elapsed}ms, lead_rec=${recommendedLeadMs}ms, gen=$gen"
        )
    }

    private fun updateRecommendedLead(e2eLatencyMs: Long, windowDurationMs: Long) {
        val target = (
            e2eLatencyMs + LATENCY_SAFETY_BUFFER_MS + (windowDurationMs / 2)
            ).coerceIn(MIN_DYNAMIC_LEAD_MS, MAX_DYNAMIC_LEAD_MS)

        // EMA smoothing so lead target changes gradually instead of oscillating.
        recommendedLeadMs = ((recommendedLeadMs * 4) + target) / 5
    }

    private fun resolveSourceLangForWindow(transcript: String): String {
        // User's explicit choice always wins.
        if (!sourceLang.equals("auto", ignoreCase = true)) {
            return sourceLang
        }

        // Scheme A: if the priming-phase detector committed to a concrete
        // language (not the "auto" fallback), trust it — it saw 15-30s of
        // audio on a 30s receptive field, which is vastly more reliable
        // than per-window detection on 2s slivers.
        lockedSourceLang?.takeIf { it.isNotBlank() && it != "auto" }?.let { return it }

        // Fallback chain (only reached when priming detection failed or is
        // still pending): script analysis → per-window Whisper guess →
        // "auto" as last resort.
        detectScriptLanguageHint(transcript)?.let { return it }

        val detected = sttEngine.detectedLanguage.trim().lowercase()
        if (detected in LOW_CONFIDENCE_AUTO_DETECTIONS) {
            return "auto"
        }
        return detected.ifBlank { "auto" }
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

    private fun normalizedEquals(left: String, right: String): Boolean {
        return left.trim().lowercase() == right.trim().lowercase()
    }

    companion object {
        private const val TAG = "HiddenTrackPlayer"
        private const val CHANNEL_CAPACITY = 16
        private const val TRANSLATION_CHANNEL_CAPACITY = 24

        // Cap on how long release() waits for an in-flight whisper JNI call
        // to return before continuing. medium-q on S24U takes ~10s/window;
        // 12s buys us one whole call without hanging UI for absurdly long.
        private const val WHISPER_RELEASE_JOIN_TIMEOUT_MS = 12_000L

        private const val INITIAL_RECOMMENDED_LEAD_MS = 2_000L
        private const val MIN_DYNAMIC_LEAD_MS = 2_000L
        // Whisper-medium on S24 Ultra runs ~5–10s per 2s window (5× slower than
        // realtime). With a 10s lead cap the EMA is permanently saturated and
        // every translation arrives AFTER the visible player has passed the
        // segment, so the subtitle store ends up empty. 20s gives the EMA
        // headroom to cover end-to-end ASR+MT latency.
        private const val MAX_DYNAMIC_LEAD_MS = 20_000L
        private const val LATENCY_SAFETY_BUFFER_MS = 1_200L

        private const val MIN_WINDOW_DURATION_MS = 600L
        // Minimum lingering of one subtitle past its raw end. Combined with the
        // sticky lookup in SubtitleStore.getNearestForDisplay this guarantees
        // at least ~2.5s of on-screen visibility per segment, smoothing the
        // gaps when consecutive ASR windows take different durations.
        private const val SUBTITLE_TRAIL_HOLD_MS = 2_500L

        // ── Language-detection (Scheme A) tuning ────────────────────────
        //
        // MIN_MS:   wait for this much audio before the FIRST detection pass.
        //           15s is enough for Whisper to read a stable language token
        //           on its 30s-mel receptive field for the vast majority of
        //           speech; less than this and we regress to per-window noise.
        // MAX_MS:   hard ceiling. If detection still isn't confident at 30s,
        //           we commit to "auto" (per-window fallback) so subtitles
        //           don't stall forever on truly ambiguous content.
        // MIN_CONFIDENCE:
        //           threshold on top-1 language probability. Below this we
        //           keep buffering. 0.5 is conservative — for clean speech
        //           the top-1 is usually >0.9.
        // MIN_MARGIN:
        //           required gap between top-1 and top-2 probability. Guards
        //           against near-ties where the model isn't really decisive
        //           (e.g. ja vs zh on mixed content).
        internal const val LANG_DETECT_MIN_MS = 15_000L
        internal const val LANG_DETECT_MAX_MS = 30_000L
        private const val LANG_DETECT_MIN_CONFIDENCE = 0.5f
        private const val LANG_DETECT_MIN_MARGIN = 0.15f

        // Languages that Whisper base/small models frequently hallucinate
        // from non-speech or short windows. If the detector picks one of
        // these, we treat it as "not decisive" and keep accumulating audio.
        // Expanded beyond the original {af, cy, so, la} set to cover the
        // hallucinations observed in real logs (ms, mt, am, etc.).
        private val LOW_CONFIDENCE_AUTO_DETECTIONS = setOf(
            // Originals
            "af",  // Afrikaans
            "cy",  // Welsh
            "so",  // Somali
            "la",  // Latin
            // Added from observed hallucinations / known base-model weaknesses
            "ms",  // Malay      (log: English audio → "ms")
            "mt",  // Maltese
            "am",  // Amharic
            "is",  // Icelandic
            "eu",  // Basque
            "yi",  // Yiddish
            "lo",  // Lao
            "ln",  // Lingala
            "mi",  // Maori
            "haw", // Hawaiian
            "sn",  // Shona
            "tl",  // Tagalog (sometimes legit, but very often a hallucination)
            "br",  // Breton
            "oc",  // Occitan
            "jw",  // Javanese
            "su",  // Sundanese
            "lb",  // Luxembourgish
            "mg",  // Malagasy
            "nn",  // Norwegian Nynorsk
            "sa",  // Sanskrit
            "ht",  // Haitian Creole
            "gu",  // Gujarati
            "bo",  // Tibetan
            "tk",  // Turkmen
            "tt",  // Tatar
            "ba"   // Bashkir
        )

        /**
         * Matches Whisper's parenthetical noise / event markers that indicate
         * non-speech audio content — e.g.:
         *   "(spoken in foreign language)", "(music)", "(applause)", "(laughter)",
         *   "(background music)", "(silence)", "[BLANK_AUDIO]", etc.
         *
         * These are emitted instead of real transcription segments and should be
         * dropped before being sent to NLLB translation.
         */
        private val WHISPER_NOISE_MARKER_REGEX = Regex(
            """(?:\(\s*(?:spoken|speaking)\s+in\s+[^)]+\s*\)|\[\s*(?:spoken|speaking)\s+in\s+[^\]]+\s*\]|\(\s*(?:music|applause|laughter|silence|background[^)]*|crowd[^)]*|noise[^)]*|inaudible[^)]*|foreign language|unintelligible)\s*\)|\[\s*(?:music|applause|laughter|silence|background[^\]]*|crowd[^\]]*|noise[^\]]*|inaudible[^\]]*|foreign language|unintelligible)\s*\]|\[BLANK_AUDIO\]|\b(?:spoken|speaking)\s+in\s+foreign language\b)""",
            RegexOption.IGNORE_CASE
        )
    }
}
