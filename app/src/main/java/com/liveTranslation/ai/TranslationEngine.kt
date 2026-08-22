package com.liveTranslation.ai

/**
 * Abstraction over any on-device Machine Translation engine.
 *
 * Implementations run on Dispatchers.Default and MUST be thread-safe if
 * multiple coroutines might call [translate] concurrently.
 */
interface TranslationEngine {

    /**
     * Translate [text] from [sourceLang] into [targetLang].
     *
     * @param text       The text produced by [SpeechToTextEngine.transcribe].
     * @param sourceLang BCP-47 source language code  (e.g. "ja", "zh", "fr").
     * @param targetLang BCP-47 target language code  (e.g. "en", "es").
     * @return           Translated text, or the original [text] if translation failed.
     */
    fun translate(text: String, sourceLang: String, targetLang: String): String

    /**
     * Release ONNX session and any other native / heap resources.
     */
    fun release()
}
