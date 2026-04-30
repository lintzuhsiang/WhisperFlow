package com.liveTranslation.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.liveTranslation.ai.SpeechToTextEngine
import com.liveTranslation.ai.TranslationEngine

/**
 * Dual-track player:
 * - visible ExoPlayer renders video/audio
 * - hidden ExoPlayer decodes audio ahead for ASR+translation
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DualTrackPlayer(
    private val context: Context,
    sttEngine: SpeechToTextEngine,
    translationEngine: TranslationEngine,
    sourceLang: String,
    targetLang: String,
    val advanceMs: Long = DEFAULT_ADVANCE_MS
) {
    val subtitleStore = SubtitleStore()

    /** Visible track — renders video + speaker audio. */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    /** Hidden track — audio-only pipeline feeding subtitleStore. */
    private val hiddenTrackPlayer = HiddenTrackPlayer(
        context = context,
        sttEngine = sttEngine,
        translationEngine = translationEngine,
        subtitleStore = subtitleStore,
        sourceLang = sourceLang,
        targetLang = targetLang
    )

    private val handler = Handler(Looper.getMainLooper())
    private var subtitleCallback: ((SubtitleStore.Entry?) -> Unit)? = null

    @Volatile var isLoaded = false; private set
    @Volatile var hasError = false; private set

    /** Callback for playback errors. Called on main thread. */
    var onErrorListener: ((PlaybackException) -> Unit)? = null

    /**
     * Fired once per video load when the hidden track's language detector
     * commits to a source language. `accepted=false` means detection never
     * became confident enough (even at 30s of audio) and we fell back to
     * per-window "auto" — UI should flag this so the user can override.
     *
     * Invoked on a background thread — dispatch to main before touching UI.
     */
    var onLanguageLocked: ((lang: String, confidence: Float, accepted: Boolean) -> Unit)? = null

    private var hiddenPausedByCeiling = false
    private var suppressVisibleSeekSync = false
    private var playRequested = false
    private var primedForPlayback = false
    private var hasEverPrimed = false   // true once the first priming completes
    private var primingStartMs = 0L

    @Volatile
    private var dynamicAdvanceMs: Long = advanceMs

    private val visiblePlayerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (suppressVisibleSeekSync) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                syncHiddenAfterUserSeek(newPosition.positionMs)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                stopSubtitleTicker()
                stopEvictionTicker()
                stopDriftChecker()
                handler.removeCallbacks(primeRunnable)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Visible player error: ${error.message}", error)
            handlePlaybackError(error)
        }
    }

    init {
        exoPlayer.addListener(visiblePlayerListener)

        // Forward hidden player errors through the same error path
        hiddenTrackPlayer.onErrorListener = { error ->
            Log.e(TAG, "Hidden player error forwarded: ${error.message}")
            handlePlaybackError(error)
        }

        // Forward language-detection events from the hidden track to UI.
        hiddenTrackPlayer.onLanguageLocked = { lang, confidence, accepted ->
            Log.i(TAG, "Language locked: '$lang' confidence=${"%.3f".format(confidence)} accepted=$accepted")
            onLanguageLocked?.invoke(lang, confidence, accepted)
        }
    }

    /**
     * Stop all tickers and notify error listener. Called on main thread
     * from either the visible or hidden player error callback.
     */
    private fun handlePlaybackError(error: PlaybackException) {
        hasError = true
        playRequested = false

        // Stop all background work
        handler.removeCallbacks(primeRunnable)
        stopSubtitleTicker()
        stopEvictionTicker()
        stopDriftChecker()

        // Pause hidden player to stop wasting resources
        hiddenTrackPlayer.pause()

        // Notify the Activity
        onErrorListener?.invoke(error)
    }

    fun load(uri: Uri, mimeType: String? = null, headers: Map<String, String> = emptyMap()) {
        subtitleStore.clear()
        dynamicAdvanceMs = advanceMs
        primedForPlayback = false
        hasError = false

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }
            .build()

        // If custom headers are provided (e.g. Referer for CDN auth), create
        // a MediaSource with a configured HttpDataSource instead of using
        // the default setMediaItem() path.
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
        exoPlayer.prepare()

        hiddenTrackPlayer.load(mediaItem, startPositionMs = 0, headers = headers)

        isLoaded = true
        Log.i(TAG, "Loaded: $uri, mimeType=$mimeType, headers=${headers.size}, advanceMs=$advanceMs")
    }

    fun play() {
        if (!isLoaded) return

        playRequested = true
        startPrimingThenPlayVisible()
        Log.i(TAG, "Play requested")
    }

    fun pause() {
        playRequested = false
        exoPlayer.playWhenReady = false
        hiddenTrackPlayer.pause()

        handler.removeCallbacks(primeRunnable)
        stopSubtitleTicker()
        stopEvictionTicker()
        stopDriftChecker()

        Log.i(TAG, "Pause")
    }

    /** Programmatic seek API. */
    fun seekTo(positionMs: Long) {
        doSeek(positionMs, seekVisible = true)
    }

    fun release() {
        handler.removeCallbacks(primeRunnable)
        stopSubtitleTicker()
        stopEvictionTicker()
        stopDriftChecker()

        exoPlayer.removeListener(visiblePlayerListener)
        hiddenTrackPlayer.release()
        exoPlayer.release()
        subtitleStore.clear()

        Log.i(TAG, "Released")
    }

    fun setSubtitleListener(callback: (SubtitleStore.Entry?) -> Unit) {
        subtitleCallback = callback
    }

    fun getCurrentSubtitle(): SubtitleStore.Entry? {
        // Use sticky lookup: when ASR/MT latency stretches past the natural
        // display window, the previous translation should remain on-screen
        // until the next one arrives instead of vanishing for several seconds.
        return subtitleStore.getNearestForDisplay(exoPlayer.currentPosition)
    }

    /**
     * Current locked source language, or null while still detecting.
     * Safe to call from any thread.
     */
    val lockedSourceLang: String? get() = hiddenTrackPlayer.lockedSourceLang

    /** Confidence (0..1) of the current language lock. 0 while detecting. */
    val lockedSourceConfidence: Float get() = hiddenTrackPlayer.lockedSourceConfidence

    private fun syncHiddenAfterUserSeek(positionMs: Long) {
        doSeek(positionMs, seekVisible = false)
    }

    private fun doSeek(positionMs: Long, seekVisible: Boolean) {
        refreshDynamicAdvance()

        subtitleStore.clear()

        if (seekVisible) {
            suppressVisibleSeekSync = true
            try {
                exoPlayer.seekTo(positionMs)
            } finally {
                suppressVisibleSeekSync = false
            }
        }

        hiddenTrackPlayer.seekTo(positionMs + dynamicAdvanceMs)
        primedForPlayback = false

        val shouldResume = playRequested || exoPlayer.playWhenReady
        if (shouldResume) {
            if (hasEverPrimed) {
                // Optimistic seek: already playing before, keep going immediately.
                // Hidden track will decode ahead in parallel; subtitles catch up
                // in ~3-6 seconds.  The drift checker handles edge cases where
                // the hidden player falls behind the visible player.
                exoPlayer.playWhenReady = true
                primedForPlayback = true
                hiddenTrackPlayer.play()
                startEvictionTicker()
                startDriftChecker()
                Log.i(TAG, "Seek (optimistic): visible=${positionMs}ms, " +
                    "hidden=${positionMs + dynamicAdvanceMs}ms — resuming immediately")
            } else {
                // First play ever: force priming so we don't start with zero subtitles.
                startPrimingThenPlayVisible()
                Log.i(TAG, "Seek (initial prime): visible=${positionMs}ms, " +
                    "hidden=${positionMs + dynamicAdvanceMs}ms")
            }
        }
    }

    private fun startPrimingThenPlayVisible() {
        hiddenTrackPlayer.play()
        exoPlayer.playWhenReady = false

        startEvictionTicker()
        startDriftChecker()

        primingStartMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(primeRunnable)
        handler.post(primeRunnable)
    }

    private val primeRunnable = object : Runnable {
        override fun run() {
            if (!playRequested) return

            refreshDynamicAdvance()
            val visiblePos = exoPlayer.currentPosition
            val targetReadyPos = visiblePos + dynamicAdvanceMs
            val readyPos = hiddenTrackPlayer.decodedUpToMs
            val waitedMs = SystemClock.elapsedRealtime() - primingStartMs

            // Use a small tolerance to avoid missing by a few ms due to
            // rounding between sample-based position and ms-based target.
            if (readyPos >= targetReadyPos - PRIME_TOLERANCE_MS || waitedMs >= MAX_PREROLL_WAIT_MS) {
                primedForPlayback = true
                hasEverPrimed = true
                exoPlayer.playWhenReady = true
                startSubtitleTicker()
                Log.i(
                    TAG,
                    "Primed: readyPos=${readyPos}ms, target=${targetReadyPos}ms, " +
                        "waited=${waitedMs}ms, dynamicAdvance=${dynamicAdvanceMs}ms"
                )
                return
            }

            handler.postDelayed(this, PREROLL_POLL_MS)
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val entry = getCurrentSubtitle()
            subtitleCallback?.invoke(entry)
            handler.postDelayed(this, SUBTITLE_POLL_MS)
        }
    }

    private fun startSubtitleTicker() {
        handler.removeCallbacks(tickerRunnable)
        handler.post(tickerRunnable)
    }

    private fun stopSubtitleTicker() {
        handler.removeCallbacks(tickerRunnable)
    }

    private val evictionRunnable = object : Runnable {
        override fun run() {
            val currentPos = exoPlayer.currentPosition
            subtitleStore.evictBefore(currentPos - HISTORY_KEEP_MS)
            handler.postDelayed(this, EVICTION_INTERVAL_MS)
        }
    }

    private fun startEvictionTicker() {
        handler.removeCallbacks(evictionRunnable)
        handler.postDelayed(evictionRunnable, EVICTION_INTERVAL_MS)
    }

    private fun stopEvictionTicker() {
        handler.removeCallbacks(evictionRunnable)
    }

    private val driftRunnable = object : Runnable {
        override fun run() {
            checkAndCorrectDrift()
            handler.postDelayed(this, DRIFT_CHECK_INTERVAL_MS)
        }
    }

    private fun checkAndCorrectDrift() {
        refreshDynamicAdvance()

        val visiblePos = exoPlayer.currentPosition
        val hiddenPlayerPos = hiddenTrackPlayer.exoPlayer.currentPosition
        val hiddenDecodedPos = hiddenTrackPlayer.decodedUpToMs

        val playbackLead = hiddenPlayerPos - visiblePos
        val decodedLead = hiddenDecodedPos - visiblePos
        val expectedLead = dynamicAdvanceMs

        when {
            // Hidden track is FAR behind the visible track. This typically means
            // a seek went wrong or the hidden ExoPlayer has been stalled for a
            // long time. Force a full resync (seekTo), which flushes the codec
            // but is unavoidable at this point.
            // Guard: only trigger AFTER initial priming is done. During startup
            // decodedUpToMs may still be 0 — that's normal, not a catastrophe.
            decodedLead < -DRIFT_RESYNC_THRESHOLD_MS && primedForPlayback -> {
                val targetPos = visiblePos + expectedLead
                hiddenTrackPlayer.seekTo(targetPos)
                hiddenPausedByCeiling = false
                primedForPlayback = false
                if (playRequested) {
                    startPrimingThenPlayVisible()
                }
                Log.w(
                    TAG,
                    "DRIFT catastrophic resync: hidden is behind visible! " +
                        "decodedLead=${decodedLead}ms. Forcing seek to ${targetPos}ms"
                )
            }

            // Hidden PCM decode has fallen behind the visible position, but not
            // by much. This can happen if the hidden ExoPlayer stalls (e.g.
            // network rebuffer). Gently pause visible and re-prime to let
            // hidden catch up without flushing codecs.
            //
            // NOTE: decodedUpToMs tracks PCM emission, which jumps in 3-second
            // increments. Between jumps decodedLead naturally dips toward 0 —
            // that is normal and must NOT trigger this branch. We only act
            // when decodedLead actually goes negative (decoded < visible).
            decodedLead < 0L && primedForPlayback -> {
                hiddenPausedByCeiling = false
                primedForPlayback = false
                startPrimingThenPlayVisible()
                Log.w(
                    TAG,
                    "DRIFT buffering: decodedLead=${decodedLead}ms, expected=${expectedLead}ms. " +
                        "Pausing visible player to let hidden track catch up without flushing codecs."
                )
            }

            // Hidden playback is too far ahead. Pause hidden track to cap resource usage.
            playbackLead > MAX_LEAD_MS && !hiddenPausedByCeiling -> {
                hiddenTrackPlayer.pause()
                hiddenPausedByCeiling = true
                Log.d(TAG, "DRIFT ceiling: pause hidden, playbackLead=${playbackLead}ms")
            }

            // Resume hidden after ceiling pause.
            hiddenPausedByCeiling && playbackLead < MAX_LEAD_MS - CEILING_RESUME_MARGIN_MS -> {
                if (playRequested) {
                    hiddenTrackPlayer.play()
                }
                hiddenPausedByCeiling = false
                Log.d(TAG, "DRIFT resume: playbackLead=${playbackLead}ms")
            }
        }

        Log.d(
            TAG,
            "DRIFT status: visible=${visiblePos}ms, hiddenPlay=${hiddenPlayerPos}ms, " +
                "hiddenDecoded=${hiddenDecodedPos}ms, playbackLead=${playbackLead}ms, " +
                "decodedLead=${decodedLead}ms, expected=${expectedLead}ms, " +
                "subtitles=${subtitleStore.size()}, primed=$primedForPlayback"
        )
    }

    private fun refreshDynamicAdvance() {
        dynamicAdvanceMs = hiddenTrackPlayer.recommendedLeadMs
            .coerceAtLeast(advanceMs)
            .coerceAtMost(MAX_LEAD_MS)
    }

    private fun startDriftChecker() {
        handler.removeCallbacks(driftRunnable)
        hiddenPausedByCeiling = false
        // Delay the first drift check to give the hidden player time to
        // decode its first PCM windows.  Subsequent checks run at the
        // normal 1-second interval.
        handler.postDelayed(driftRunnable, INITIAL_DRIFT_DELAY_MS)
    }

    private fun stopDriftChecker() {
        handler.removeCallbacks(driftRunnable)
    }

    companion object {
        private const val TAG = "DualTrackPlayer"

        const val DEFAULT_ADVANCE_MS = 2_000L

        private const val SUBTITLE_POLL_MS = 200L
        private const val PREROLL_POLL_MS = 100L
        private const val MAX_PREROLL_WAIT_MS = 6_000L

        private const val EVICTION_INTERVAL_MS = 5_000L
        private const val HISTORY_KEEP_MS = 10_000L

        private const val DRIFT_CHECK_INTERVAL_MS = 1_000L
        private const val DRIFT_RESYNC_THRESHOLD_MS = 2_000L
        private const val MAX_LEAD_MS = 10_000L
        private const val CEILING_RESUME_MARGIN_MS = 2_000L
        private const val PRIME_TOLERANCE_MS = 200L
        private const val INITIAL_DRIFT_DELAY_MS = 3_000L
    }
}
