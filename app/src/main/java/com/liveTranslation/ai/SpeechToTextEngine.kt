package com.liveTranslation.ai

/**
 * Result of an engine's language-detection pass. Returned by
 * [SpeechToTextEngine.detectLanguage]. Implementations that don't support
 * detection (e.g. cloud APIs that auto-detect on every call) should return
 * null instead of an instance with `confidence == 0`.
 */
data class LanguageDetectionResult(
    val lang: String,
    val confidence: Float,
    val secondLang: String,
    val secondConfidence: Float,
    val nativeElapsedMs: Long
) {
    /** Margin between top-1 and top-2 probabilities. Higher = more decisive. */
    val margin: Float get() = (confidence - secondConfidence).coerceAtLeast(0f)

    /** True when the detector returned a usable language code. */
    val isValid: Boolean get() = lang.isNotBlank() && confidence > 0f
}

/**
 * Abstraction over any on-device or cloud Speech-to-Text engine.
 *
 * Implementations are expected to run on a background coroutine dispatcher
 * (e.g. Dispatchers.Default) and MUST NOT block the Main thread.
 */
interface SpeechToTextEngine {

    /**
     * Transcribe a raw PCM audio buffer.
     *
     * @param audioBuffer Raw 16-bit PCM samples sampled at **16 kHz, Mono**.
     *                    This is the exact format required by whisper.cpp.
     * @param sourceLang  BCP-47 language code of the spoken audio, e.g. "ja", "zh",
     *                    "en".  Pass "auto" to let the engine detect automatically.
     * @param targetLang  BCP-47 target language code.  When set to "en", engines
     *                    that support built-in translation (e.g. whisper.cpp) will
     *                    transcribe AND translate in a single pass, skipping the
     *                    separate MT step.  Pass "" to disable built-in translation.
     * @param prompt      Context prompt from a previous transcription.  Passing the
     *                    last result as the prompt for the next window helps whisper
     *                    stitch words that were cut at the sliding-window boundary.
     * @return The transcribed (and possibly translated) text, or an empty string
     *         if no speech was detected.
     */
    fun transcribe(
        audioBuffer: ShortArray,
        sourceLang: String = "auto",
        targetLang: String = "",
        prompt: String = ""
    ): String

    /**
     * BCP-47 language code detected during the most recent [transcribe] call.
     * Returns empty string if detection is unsupported or no transcription has run.
     */
    val detectedLanguage: String get() = ""

    /**
     * Run a one-shot language-detection pass over [audioBuffer]. Used by the
     * dual-track player's priming phase to lock a stable source language
     * before normal ASR begins.
     *
     * Default returns `null`, meaning "this engine does not need a priming
     * phase" — its [transcribe] call already auto-detects per window with
     * acceptable accuracy (e.g. cloud Whisper). The caller (HiddenTrackPlayer)
     * treats null as a signal to skip priming entirely and feed windows
     * straight through.
     *
     * Implementations that DO need priming (e.g. on-device whisper.cpp,
     * which is unreliable on short windows) should return a populated
     * [LanguageDetectionResult].
     */
    fun detectLanguage(audioBuffer: ShortArray): LanguageDetectionResult? = null

    /**
     * Release any native resources held by the engine (model buffers, JNI contexts, etc.).
     * Call this when the service is being destroyed.
     */
    fun release()
}
