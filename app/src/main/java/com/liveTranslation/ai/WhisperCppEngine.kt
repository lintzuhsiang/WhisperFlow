package com.liveTranslation.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Production implementation of [SpeechToTextEngine] backed by whisper.cpp
 * through the JNI bridge defined in `src/main/cpp/whisper_jni.cpp`.
 *
 * ─── File placement guide ──────────────────────────────────────────────────
 *
 *  C++ source  →  app/src/main/cpp/
 *                   ├── CMakeLists.txt         (build script)
 *                   ├── whisper_jni.cpp        (JNI bridge – this class's native peers)
 *                   ├── whisper.h              (whisper.cpp public header)
 *                   └── whisper.cpp            (whisper.cpp main implementation)
 *
 *  Model file  →  app/src/main/assets/
 *                   └── ggml-medium.bin        (or ggml-large-v3.bin, etc.)
 *                       Download from: https://huggingface.co/ggerganov/whisper.cpp
 *
 * ──────────────────────────────────────────────────────────────────────────
 *
 *  Usage:
 *   val engine = WhisperCppEngine(context)
 *   val text = engine.transcribe(pcmBuffer, "ja")
 *   engine.release()
 */
class WhisperCppEngine(
    private val context: Context,
    private val modelFilename: String = MODEL_FILENAME,
    private val fallbackFilename: String = "ggml-tiny.en.bin",
    /** CPU thread count for whisper_full(). Benchmark: 2 vs 4 vs 6.
     *  Check logcat for "PERF | whisper_transcribe" timing lines. */
    val nThreads: Int = DEFAULT_N_THREADS
) : SpeechToTextEngine {

    // Opaque handle to the whisper_context allocated in native code.
    // 0L means not yet initialised.
    private var nativeContextPtr: Long = 0L

    /** BCP-47 language code detected by Whisper during the last transcription. */
    @Volatile
    override var detectedLanguage: String = ""
        private set

    init {
        loadModel()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            val externalDir = context.getExternalFilesDir(null)
            
            // Try external storage first
            var modelFile = externalDir?.resolve(modelFilename)

            // Try bundled asset for the requested model
            if (modelFile == null || !modelFile.exists()) {
                modelFile = copyAssetToCacheIfExists(modelFilename)
            }

            // Fallback to a smaller built-in model if the requested one is missing
            if (modelFile == null || !modelFile.exists()) {
                Log.w(TAG, "$modelFilename not found. Falling back to $fallbackFilename.")
                modelFile = copyAssetToCacheIfExists(fallbackFilename)
            }

            if (modelFile == null || !modelFile.exists()) {
                Log.e(TAG, "Whisper model file not found: $modelFilename (fallback=$fallbackFilename)")
                return
            }

            nativeContextPtr = nativeInitContext(modelFile.absolutePath)
            if (nativeContextPtr == 0L) {
                Log.e(TAG, "whisper_init_from_file failed – check model path: \${modelFile.absolutePath}")
            } else {
                Log.i(TAG, "whisper.cpp context initialised (ptr=\$nativeContextPtr)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load whisper model", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // SpeechToTextEngine implementation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Transcribes [audioBuffer] (16 kHz, mono, PCM-16) using whisper.cpp.
     *
     * When [targetLang] is "en" and the source language is NOT English,
     * whisper.cpp's built-in translation mode is activated so it transcribes
     * AND translates to English in a single pass — much faster than running
     * a separate NLLB translation step afterwards.
     *
     * The function converts the Short samples to normalised floats [-1.0, 1.0]
     * before passing them to whisper, which is whisper.cpp's expected format.
     *
     * Callers should invoke this from [kotlinx.coroutines.Dispatchers.Default]
     * because it may take 500 ms – several seconds depending on hardware.
     */
    @Synchronized
    override fun transcribe(
        audioBuffer: ShortArray,
        sourceLang: String,
        targetLang: String,
        prompt: String
    ): String {
        if (nativeContextPtr == 0L) {
            Log.w(TAG, "transcribe() called but context is not initialised")
            return ""
        }

        val t0 = SystemClock.elapsedRealtime()

        // Convert PCM-16 → Float32 normalised [-1, 1] expected by whisper.cpp
        val floatBuffer = FloatArray(audioBuffer.size) { i ->
            audioBuffer[i].toFloat() / Short.MAX_VALUE
        }

        // We do NOT use Whisper's internal translation. We leave ALL MT to GPT/Nllb.
        val result = nativeTranscribe(
            contextPtr  = nativeContextPtr,
            audioData   = floatBuffer,
            language    = if (sourceLang == "auto") "" else sourceLang,
            translate   = false,
            prompt      = prompt,
            nThreads    = nThreads
        )

        // result[0] = transcript text, result[1] = detected language code,
        // result[2] = native inference time in ms (from C++ chrono)
        val elapsed = SystemClock.elapsedRealtime() - t0
        val nativeMs = result.getOrElse(2) { "" }
        Log.i(TAG, "PERF | whisper_transcribe: total=${elapsed}ms, native=${nativeMs}ms, " +
            "samples=${audioBuffer.size}, audio_sec=${"%.2f".format(audioBuffer.size / 16000f)}, " +
            "n_threads=$nThreads, model=$modelFilename")

        detectedLanguage = result.getOrElse(1) { "" }
        return result.getOrElse(0) { "" }
    }

    // LanguageDetectionResult lives in SpeechToTextEngine.kt (lifted so other
    // engines can return the same shape). Kept as a typealias inside this
    // class would shadow the import — just use the lifted type directly.

    /**
     * Run Whisper's built-in language auto-detector over [audioBuffer]
     * (16 kHz, mono, PCM-16). Uses at most the first 30 seconds of audio;
     * extra samples beyond that are ignored by the native side.
     *
     * Use this once during the priming phase of the hidden track player to
     * determine a source language, then lock it into subsequent [transcribe]
     * calls. This is DRAMATICALLY more reliable than letting Whisper
     * re-detect language on every 2-3s window.
     *
     * Runs synchronously; call from a background dispatcher (~50-200ms on base,
     * ~200-500ms on medium).
     */
    @Synchronized
    override fun detectLanguage(audioBuffer: ShortArray): LanguageDetectionResult {
        if (nativeContextPtr == 0L) {
            Log.w(TAG, "detectLanguage() called but context is not initialised")
            return LanguageDetectionResult("", 0f, "", 0f, 0L)
        }
        if (audioBuffer.isEmpty()) {
            Log.w(TAG, "detectLanguage() called with empty audio")
            return LanguageDetectionResult("", 0f, "", 0f, 0L)
        }

        val t0 = SystemClock.elapsedRealtime()

        // Convert PCM-16 → Float32 normalised [-1, 1]
        val floatBuffer = FloatArray(audioBuffer.size) { i ->
            audioBuffer[i].toFloat() / Short.MAX_VALUE
        }

        val raw = nativeDetectLanguage(
            contextPtr = nativeContextPtr,
            audioData  = floatBuffer,
            nThreads   = nThreads
        )

        val elapsed = SystemClock.elapsedRealtime() - t0

        val lang      = raw.getOrElse(0) { "" }
        val conf      = raw.getOrElse(1) { "0" }.toFloatOrNull() ?: 0f
        val second    = raw.getOrElse(2) { "" }
        val secConf   = raw.getOrElse(3) { "0" }.toFloatOrNull() ?: 0f
        val nativeMs  = raw.getOrElse(4) { "0" }.toLongOrNull() ?: 0L

        Log.i(TAG, "PERF | detect_language: total=${elapsed}ms, native=${nativeMs}ms, " +
            "samples=${audioBuffer.size}, audio_sec=${"%.2f".format(audioBuffer.size / 16000f)}, " +
            "top=$lang(${"%.3f".format(conf)}), 2nd=$second(${"%.3f".format(secConf)}), " +
            "model=$modelFilename")

        return LanguageDetectionResult(lang, conf, second, secConf, nativeMs)
    }

    /**
     * Free the native whisper_context. `@Synchronized` together with the
     * matching annotation on [transcribe] / [detectLanguage] guarantees we
     * never call `whisper_free` while another thread is mid-inference, which
     * would race on internal ggml buffers and SIGSEGV inside
     * `ggml_compute_forward_add_non_quantized`.
     */
    @Synchronized
    override fun release() {
        if (nativeContextPtr != 0L) {
            nativeFreeContext(nativeContextPtr)
            nativeContextPtr = 0L
            Log.i(TAG, "whisper.cpp context freed")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // JNI declarations
    //
    // Implemented in: app/src/main/cpp/whisper_jni.cpp
    //
    // Java_com_liveTranslation_ai_WhisperCppEngine_nativeInitContext
    // Java_com_liveTranslation_ai_WhisperCppEngine_nativeTranscribe
    // Java_com_liveTranslation_ai_WhisperCppEngine_nativeFreeContext
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Calls `whisper_init_from_file(modelPath)` and returns the resulting
     * `whisper_context*` cast to a Long (jlong).  Returns 0 on failure.
     */
    private external fun nativeInitContext(modelPath: String): Long

    /**
     * Runs full whisper inference (~whisper_full) on [audioData] (Float32,
     * 16 kHz, mono) and returns a 2-element String array:
     *   [0] = concatenated transcription segments
     *   [1] = detected language code (e.g. "en", "ja", "zh")
     *
     * @param contextPtr  Pointer returned by [nativeInitContext].
     * @param audioData   Float32 normalised PCM samples.
     * @param language    Language hint for whisper (e.g. "ja", "zh", "" for auto).
     * @param translate   If true, whisper attempts to translate to English internally.
     * @param prompt      Context prompt (previous transcription) fed into
     *                    `whisper_full_params.initial_prompt` so the decoder
     *                    can stitch words across sliding-window boundaries.
     */
    private external fun nativeTranscribe(
        contextPtr : Long,
        audioData  : FloatArray,
        language   : String,
        translate  : Boolean,
        prompt     : String,
        nThreads   : Int
    ): Array<String>

    /**
     * Runs Whisper's language auto-detector (`whisper_pcm_to_mel` +
     * `whisper_lang_auto_detect`) on [audioData] and returns a 5-element
     * String array:
     *   [0] topLang           BCP-47 code, e.g. "ja"
     *   [1] topConfidence     "0.9823"
     *   [2] secondLang        e.g. "zh"
     *   [3] secondConfidence  "0.0054"
     *   [4] elapsedMs         native wall-clock in ms
     *
     * @param contextPtr  Pointer returned by [nativeInitContext].
     * @param audioData   Float32 normalised PCM samples at 16 kHz, mono.
     * @param nThreads    CPU thread count for the mel + detection pass.
     */
    private external fun nativeDetectLanguage(
        contextPtr : Long,
        audioData  : FloatArray,
        nThreads   : Int
    ): Array<String>

    /**
     * Calls `whisper_free(contextPtr)`.
     */
    private external fun nativeFreeContext(contextPtr: Long)

    private fun copyAssetToCacheIfExists(filename: String): java.io.File? {
        return try {
            val cacheFile = java.io.File(context.cacheDir, filename)
            if (!cacheFile.exists()) {
                context.assets.open(filename).use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Copied Whisper model to cache: ${cacheFile.absolutePath}")
            }
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "WhisperCppEngine"

        /** Default CPU thread count for whisper_full().
         *  To A/B test: change WHISPER_N_THREADS in AudioCaptureService companion,
         *  rebuild, run, compare "PERF | whisper_transcribe" logcat lines. */
        const val DEFAULT_N_THREADS = 4

        /**
         * Place the GGML model binary at:
         *   app/src/main/assets/ggml-medium.bin
         *
         * Download options:
         *   • ggml-tiny.bin    ~75 MB   – fastest, good for testing
         *   • ggml-base.bin    ~142 MB
         *   • ggml-small.bin   ~466 MB
         *   • ggml-medium.bin  ~1.5 GB  – recommended balance
         *   • ggml-large-v3.bin ~3 GB   – most accurate, very slow on device
         *
         * All available at https://huggingface.co/ggerganov/whisper.cpp
         */
        // NOTE: switched from ggml-base-q8_0.bin → ggml-medium.bin so we use the
        // model that's already on the test device (~1.5 GB, multilingual, much
        // more accurate on non-English audio like Japanese). Watch for OOM on
        // first run; if it crashes, fall back to ggml-medium-q8_0.bin (~785 MB)
        // or ggml-small-q8_0.bin.
        private const val MODEL_FILENAME = "ggml-medium.bin"

        init {
            // Loads the shared library built by CMake:
            //   app/src/main/cpp/CMakeLists.txt → target "whisper_jni"
            System.loadLibrary("whisper_jni")
        }
    }
}
