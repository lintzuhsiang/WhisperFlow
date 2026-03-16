package com.liveTranslation.ai

import android.content.Context
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
class WhisperCppEngine(private val context: Context) : SpeechToTextEngine {

    // Opaque handle to the whisper_context allocated in native code.
    // 0L means not yet initialised.
    private var nativeContextPtr: Long = 0L

    init {
        loadModel()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            // Copy the asset to the app's internal storage so the C++ layer
            // can open it with a regular file path (assets are not directly
            // accessible as file-system paths on Android).
            val modelFile = context.filesDir.resolve(MODEL_FILENAME)
            if (!modelFile.exists()) {
                context.assets.open(MODEL_FILENAME).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Model copied to ${modelFile.absolutePath}")
            }

            nativeContextPtr = nativeInitContext(modelFile.absolutePath)
            if (nativeContextPtr == 0L) {
                Log.e(TAG, "whisper_init_from_file failed – check model path")
            } else {
                Log.i(TAG, "whisper.cpp context initialised (ptr=$nativeContextPtr)")
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
     * The function converts the Short samples to normalised floats [-1.0, 1.0]
     * before passing them to whisper, which is whisper.cpp's expected format.
     *
     * Callers should invoke this from [kotlinx.coroutines.Dispatchers.Default]
     * because it may take 500 ms – several seconds depending on hardware.
     */
    override fun transcribe(audioBuffer: ShortArray, sourceLang: String): String {
        if (nativeContextPtr == 0L) {
            Log.w(TAG, "transcribe() called but context is not initialised")
            return ""
        }

        // Convert PCM-16 → Float32 normalised [-1, 1] expected by whisper.cpp
        val floatBuffer = FloatArray(audioBuffer.size) { i ->
            audioBuffer[i].toFloat() / Short.MAX_VALUE
        }

        return nativeTranscribe(
            contextPtr  = nativeContextPtr,
            audioData   = floatBuffer,
            language    = if (sourceLang == "auto") "" else sourceLang, // "" triggers auto-detect
            translate   = false   // set true for Whisper's built-in EN translation (EN only)
        )
    }

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
     * 16 kHz, mono) and returns the concatenated transcription segments.
     *
     * @param contextPtr  Pointer returned by [nativeInitContext].
     * @param audioData   Float32 normalised PCM samples.
     * @param language    Language hint for whisper (e.g. "ja", "zh", "" for auto).
     * @param translate   If true, whisper attempts to translate to English internally.
     */
    private external fun nativeTranscribe(
        contextPtr : Long,
        audioData  : FloatArray,
        language   : String,
        translate  : Boolean
    ): String

    /**
     * Calls `whisper_free(contextPtr)`.
     */
    private external fun nativeFreeContext(contextPtr: Long)

    // ──────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "WhisperCppEngine"

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
        private const val MODEL_FILENAME = "ggml-medium.bin"

        init {
            // Loads the shared library built by CMake:
            //   app/src/main/cpp/CMakeLists.txt → target "whisper_jni"
            System.loadLibrary("whisper_jni")
        }
    }
}
