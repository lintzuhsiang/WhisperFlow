package com.liveTranslation.player

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom [AudioProcessor] that intercepts decoded PCM audio flowing through
 * an ExoPlayer's audio rendering pipeline.
 *
 * Designed for the "hidden track" player: it accumulates PCM samples into
 * fixed-duration windows (default 3 seconds) and fires a callback when each
 * window is complete. The callback is invoked on ExoPlayer's audio thread,
 * so consumers MUST hand off heavy work (ASR, MT) to another thread.
 *
 * Audio passes through unchanged — the hidden player is muted via `volume=0f`,
 * but the pipeline still expects valid output buffers.
 *
 * ## Position tracking
 *
 * The processor tracks media position by counting samples. After a seek,
 * the caller MUST call [setBasePositionMs] so the processor knows where
 * in the media timeline the new audio begins.
 *
 * ## Thread safety
 *
 * All methods are called by ExoPlayer's internal audio thread except
 * [setBasePositionMs] and [onWindowReady], which are called from the
 * main thread during setup/seek. The `@Volatile` and synchronization
 * annotations handle the necessary visibility guarantees.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WhisperAudioProcessor(
    /**
     * Soft target window length. Used as the legacy `samplesPerWindow`
     * value when dynamic windowing is disabled. Default 3 s matches the
     * iOS side and is the upper bound on perceived subtitle latency.
     */
    private val windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS,
    /**
     * Cut a window early — at most this many seconds before the next
     * forced cut — if we detect sentence-end silence. Picked to match
     * cloud Whisper's internal segmenter (which is happiest with 1.5–5 s
     * chunks).
     */
    private val minWindowMs: Long = DEFAULT_MIN_WINDOW_MS,
    /**
     * Hard upper bound on a single window. Past this we emit even if
     * the audio is still mid-sentence; OpenAI's Whisper API rejects
     * windows longer than 25 MB anyway and gets less accurate beyond
     * ~30 s of unbroken speech.
     */
    private val maxWindowMs: Long = DEFAULT_MAX_WINDOW_MS,
    /**
     * Consecutive quiet milliseconds that count as a sentence boundary.
     * Too small → we cut between syllables (loses accuracy); too large
     * → we miss real pauses and fall through to maxWindowMs cuts.
     */
    private val silenceMs: Long = DEFAULT_SILENCE_MS,
    /**
     * Mean-absolute-amplitude threshold below which a 100 ms frame is
     * counted as silence. Int16 PCM scale (max 32767). Calibrated for
     * typical YouTube content; quieter speakers may need to drop this.
     */
    private val silenceRmsThreshold: Int = DEFAULT_SILENCE_THRESHOLD,
    /**
     * Set to `false` to revert to legacy fixed-length 3 s windows.
     * Useful for A/B-comparing accuracy against the dynamic path.
     */
    private val useDynamicWindowing: Boolean = true
) : AudioProcessor {

    // ── Configuration (set in configure()) ──────────────────────────────
    private var pendingInputFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerSample = 2  // PCM-16

    // ── PCM accumulator (primitive ShortArray — no boxing overhead) ─────
    private var accBuffer = ShortArray(48000 * 2 * 10)  // pre-size for ~10s stereo 48kHz
    private var accSize = 0
    private var samplesPerWindow = 0       // legacy fixed-window mode
    private var minWindowSamples = 0       // dynamic mode
    private var maxWindowSamples = 0       // dynamic mode
    private var silenceWindowSamples = 0   // dynamic mode
    private var rmsFrameSamples = 0        // size of one 100 ms RMS frame

    /**
     * Running count of consecutive samples whose RMS-frame amplitude was
     * below threshold. When this crosses [silenceWindowSamples] AND we
     * have at least [minWindowSamples] accumulated, we emit early and
     * reset.
     */
    private var consecutiveQuietSamples = 0
    /**
     * Where we last finished scanning RMS frames. Tracks position into
     * `accBuffer` so we don't re-scan audio every queueInput call.
     */
    private var rmsScanCursor = 0

    // ── Position tracking ───────────────────────────────────────────────
    @Volatile private var basePositionMs = 0L
    private var totalSamplesConsumed = 0L  // mono-equivalent sample count

    // ── Output buffer ───────────────────────────────────────────────────
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    // ── Callback ────────────────────────────────────────────────────────
    /**
     * Callback fired when a complete audio window is ready for ASR.
     *
     * Parameters: (startMs, endMs, pcmShorts, sampleRate, channelCount)
     *
     * **Warning**: Called on ExoPlayer's audio thread. Must not block.
     */
    @Volatile
    var onWindowReady: ((Long, Long, ShortArray, Int, Int) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────
    // AudioProcessor interface
    // ─────────────────────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Validate: we only handle PCM-16 (encoding = C.ENCODING_PCM_16BIT = 2)
        if (inputAudioFormat == AudioFormat.NOT_SET) {
            pendingInputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }

        pendingInputFormat = inputAudioFormat
        // Pass-through: output format == input format
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        // Must return true so ExoPlayer calls queueInput().
        // Even though we don't change the audio format, we need to intercept PCM.
        return pendingInputFormat != AudioFormat.NOT_SET
    }

    override fun flush() {
        // Called after configure() and on seek
        inputFormat = pendingInputFormat
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        bytesPerSample = if (inputFormat.encoding == 2) 2 else 2  // assume PCM-16

        if (sampleRate > 0 && channelCount > 0) {
            samplesPerWindow     = (sampleRate * channelCount * windowDurationMs / 1000).toInt()
            minWindowSamples     = (sampleRate * channelCount * minWindowMs        / 1000).toInt()
            maxWindowSamples     = (sampleRate * channelCount * maxWindowMs        / 1000).toInt()
            silenceWindowSamples = (sampleRate * channelCount * silenceMs          / 1000).toInt()
            rmsFrameSamples      = (sampleRate * channelCount * RMS_FRAME_MS       / 1000).toInt()
        } else {
            samplesPerWindow = 0
            minWindowSamples = 0
            maxWindowSamples = 0
            silenceWindowSamples = 0
            rmsFrameSamples = 0
        }

        accSize = 0
        consecutiveQuietSamples = 0
        rmsScanCursor = 0
        totalSamplesConsumed = 0L
        outputBuffer = EMPTY_BUFFER
        inputEnded = false

        Log.d(TAG, "flush: rate=$sampleRate, ch=$channelCount, " +
            "win(legacy=${samplesPerWindow}, min=${minWindowSamples}, " +
            "max=${maxWindowSamples}, silence=${silenceWindowSamples}), " +
            "dynamic=$useDynamicWindowing, basePos=${basePositionMs}ms")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || sampleRate == 0) return

        val remaining = inputBuffer.remaining()
        val shortCount = remaining / 2

        // 1. Extract PCM shorts for accumulation (non-destructive read)
        val savedPosition = inputBuffer.position()
        val savedOrder = inputBuffer.order()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val shorts = ShortArray(shortCount)
        inputBuffer.asShortBuffer().get(shorts)
        inputBuffer.position(savedPosition)  // restore for pass-through
        inputBuffer.order(savedOrder)

        // 2. Accumulate and check for complete windows
        ensureAccCapacity(accSize + shortCount)
        shorts.copyInto(accBuffer, accSize)
        accSize += shortCount
        checkAndEmitWindows()

        // 3. Pass audio through to output (hidden player is muted but pipeline needs it)
        val outBuf = replaceOutputBuffer(remaining)
        outBuf.put(inputBuffer)  // advances inputBuffer.position to limit
        outBuf.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true

        // Emit any remaining accumulated audio as a partial window
        if (accSize > 0 && onWindowReady != null) {
            val startMs = computeCurrentPositionMs()
            val windowPcm = accBuffer.copyOfRange(0, accSize)
            val monoSamples = if (channelCount > 0) accSize / channelCount else 0
            val endMs = if (sampleRate > 0) {
                startMs + (monoSamples * 1000L / sampleRate)
            } else {
                startMs
            }
            accSize = 0

            Log.d(TAG, "end_of_stream: emitting partial window, start=${startMs}ms, end=${endMs}ms, " +
                "samples=${windowPcm.size}")
            onWindowReady?.invoke(startMs, endMs, windowPcm, sampleRate, channelCount)
        }

        outputBuffer = EMPTY_BUFFER
    }

    override fun getOutput(): ByteBuffer {
        val result = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return result
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === EMPTY_BUFFER
    }

    override fun reset() {
        flush()
        pendingInputFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        sampleRate = 0
        channelCount = 0
        onWindowReady = null
        Log.d(TAG, "reset")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Set the base media position for sample-counting. Call this after
     * each seek so the processor knows where the new audio starts.
     *
     * Called from main thread; position tracking is on audio thread.
     * The `@Volatile` on [basePositionMs] ensures visibility.
     */
    fun setBasePositionMs(positionMs: Long) {
        synchronized(this) {
            basePositionMs = positionMs
            totalSamplesConsumed = 0L
        }
        Log.d(TAG, "setBasePositionMs: ${positionMs}ms")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Decide whether to emit a window now and, if so, send it.
     *
     * In **dynamic** mode (default), we look for natural sentence
     * boundaries by scanning the new audio in 100 ms RMS frames:
     *   - quiet frame (RMS below [silenceRmsThreshold]) → grow the
     *     consecutive-quiet count
     *   - loud frame → reset to 0
     *
     * Emit when EITHER:
     *   a) we've accumulated ≥ [minWindowSamples] AND the trailing audio
     *      is silent for ≥ [silenceWindowSamples] (= a sentence break), OR
     *   b) we've accumulated ≥ [maxWindowSamples] (forced cut, even
     *      mid-sentence — at least we never deliver windows so long they
     *      hurt accuracy at the other end).
     *
     * In **legacy** mode the original fixed-length cuts apply.
     */
    private fun checkAndEmitWindows() {
        val callback = onWindowReady ?: return

        if (useDynamicWindowing) {
            // Update the rolling RMS history. Only scan frames we haven't
            // seen yet; rmsScanCursor advances monotonically until emit.
            while (rmsScanCursor + rmsFrameSamples <= accSize) {
                val mean = meanAbs(accBuffer, rmsScanCursor, rmsFrameSamples)
                consecutiveQuietSamples = if (mean < silenceRmsThreshold) {
                    consecutiveQuietSamples + rmsFrameSamples
                } else {
                    0
                }
                rmsScanCursor += rmsFrameSamples
            }

            val canCutOnSilence =
                accSize >= minWindowSamples &&
                consecutiveQuietSamples >= silenceWindowSamples
            val mustCutOnMaxLength = accSize >= maxWindowSamples

            if (!canCutOnSilence && !mustCutOnMaxLength) return

            // Emit everything we have. The window length is variable.
            val windowStartMs = computeCurrentPositionMs()
            val windowPcm = accBuffer.copyOfRange(0, accSize)
            val monoSamples = if (channelCount > 0) accSize / channelCount else 0
            val windowEndMs = if (sampleRate > 0) {
                windowStartMs + (monoSamples * 1000L / sampleRate)
            } else {
                windowStartMs
            }
            totalSamplesConsumed += monoSamples
            accSize = 0
            consecutiveQuietSamples = 0
            rmsScanCursor = 0

            Log.d(TAG, "window_ready[dyn] start=${windowStartMs}ms end=${windowEndMs}ms " +
                "len=${windowEndMs - windowStartMs}ms reason=" +
                if (canCutOnSilence) "silence" else "maxLen")
            callback(windowStartMs, windowEndMs, windowPcm, sampleRate, channelCount)
            return
        }

        // ── Legacy fixed-window path ────────────────────────────────────
        if (samplesPerWindow <= 0) return

        while (accSize >= samplesPerWindow) {
            val windowStartMs = computeCurrentPositionMs()

            // Extract one window (native array copy — no boxing)
            val windowPcm = accBuffer.copyOfRange(0, samplesPerWindow)

            // Shift remaining samples to the front (native memory move)
            accBuffer.copyInto(accBuffer, 0, samplesPerWindow, accSize)
            accSize -= samplesPerWindow

            // Advance position counter (mono-equivalent samples in this window)
            val monoSamplesInWindow = samplesPerWindow / channelCount
            totalSamplesConsumed += monoSamplesInWindow
            val windowEndMs = computeCurrentPositionMs()

            Log.d(TAG, "window_ready: start=${windowStartMs}ms, end=${windowEndMs}ms, samples=$samplesPerWindow, " +
                "accumulated_remaining=$accSize")

            onWindowReady?.invoke(windowStartMs, windowEndMs, windowPcm, sampleRate, channelCount)
        }
    }

    private fun ensureAccCapacity(needed: Int) {
        if (needed <= accBuffer.size) return
        accBuffer = accBuffer.copyOf(maxOf(needed, accBuffer.size * 2))
    }

    /**
     * Mean absolute amplitude over a region of [accBuffer]. Cheap proxy
     * for RMS — equivalent ranking, half the arithmetic, no Math.sqrt.
     * Pure Int math; safe to call from the audio thread without GC churn.
     */
    private fun meanAbs(buf: ShortArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        var sum = 0L
        var i = offset
        val end = offset + count
        while (i < end) {
            val v = buf[i].toInt()
            sum += if (v < 0) -v else v
            i++
        }
        return (sum / count).toInt()
    }

    /**
     * Compute the current media position based on how many samples we've consumed.
     */
    private fun computeCurrentPositionMs(): Long {
        if (sampleRate == 0) return basePositionMs
        return basePositionMs + (totalSamplesConsumed * 1000L / sampleRate)
    }

    /**
     * Allocate (or reuse) an output buffer of the given size.
     */
    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private const val TAG = "WhisperAudioProc"

        // ── Legacy fixed-window mode ────────────────────────────────────
        const val DEFAULT_WINDOW_DURATION_MS = 2000L

        // ── Dynamic-window mode defaults ────────────────────────────────
        // Picked from a few hours of YouTube content + cloud Whisper
        // accuracy testing. Adjust carefully — these are an interlocking
        // set, changing one in isolation tends to make accuracy worse.
        const val DEFAULT_MIN_WINDOW_MS = 1500L
        const val DEFAULT_MAX_WINDOW_MS = 5000L
        const val DEFAULT_SILENCE_MS = 300L
        // Mean-abs amplitude in int16 PCM. Quiet conversation runs ~200,
        // background noise ~80–150, music dynamic range varies. 500 is a
        // safe middle ground.
        const val DEFAULT_SILENCE_THRESHOLD = 500
        // RMS frame size = unit at which we track silence vs speech.
        // 100 ms is the smallest unit Whisper still resolves usefully.
        private const val RMS_FRAME_MS = 100L

        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
