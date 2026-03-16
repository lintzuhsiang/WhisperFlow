package com.liveTranslation.ai

/**
 * Abstraction over any on-device Speech-to-Text engine.
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
     * @return The transcribed text, or an empty string if no speech was detected.
     */
    fun transcribe(audioBuffer: ShortArray, sourceLang: String = "auto"): String

    /**
     * Release any native resources held by the engine (model buffers, JNI contexts, etc.).
     * Call this when the service is being destroyed.
     */
    fun release()
}
