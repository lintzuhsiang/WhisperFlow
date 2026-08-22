package com.liveTranslation.ai

import android.util.Log

/**
 * Kotlin wrapper around the SentencePiece C++ library, loaded via JNI.
 *
 * The native shared library `libsentencepiece_jni.so` is built by
 * CMakeLists.txt alongside whisper_jni.  It exposes the bare minimum
 * of the SentencePiece API needed for NLLB tokenization:
 *
 *   - [load]           – load a `.model` file
 *   - [encodeAsIds]    – text → token IDs
 *   - [decodeIds]      – token IDs → text
 *   - [pieceToId]      – piece string → single ID  (language tags, special tokens)
 *   - [idToPiece]      – single ID → piece string
 *   - [vocabSize]      – total vocabulary size
 *   - [release]        – free native memory
 *
 * Usage mirrors [com.liveTranslation.ai.WhisperCppEngine]'s JNI pattern:
 * a `Long` pointer is passed across the boundary to identify the native
 * `SentencePieceProcessor*`.
 */
class SentencePieceJni {

    /** Opaque pointer to the native SentencePieceProcessor. 0 = not loaded. */
    private var nativePtr: Long = 0L

    /** `true` after a successful [load] call. */
    val isLoaded: Boolean get() = nativePtr != 0L

    /**
     * Load a SentencePiece `.model` file from [modelPath] (absolute path
     * on the device filesystem).
     *
     * @return `true` on success, `false` on failure (check logcat for details).
     */
    fun load(modelPath: String): Boolean {
        if (nativePtr != 0L) {
            Log.w(TAG, "load() called but model already loaded – releasing old model first")
            release()
        }
        nativePtr = nativeLoad(modelPath)
        return nativePtr != 0L
    }

    /** Encode [text] into an array of SentencePiece token IDs. */
    fun encodeAsIds(text: String): IntArray {
        check(nativePtr != 0L) { "SentencePiece model not loaded" }
        return nativeEncodeAsIds(nativePtr, text)
    }

    /** Decode an array of token [ids] back into a human-readable string. */
    fun decodeIds(ids: IntArray): String {
        check(nativePtr != 0L) { "SentencePiece model not loaded" }
        return nativeDecodeIds(nativePtr, ids)
    }

    /** Return the integer ID for a piece string (e.g. `"eng_Latn"`, `"</s>"`). */
    fun pieceToId(piece: String): Int {
        check(nativePtr != 0L) { "SentencePiece model not loaded" }
        return nativePieceToId(nativePtr, piece)
    }

    /** Return the piece string for a given integer [id]. */
    fun idToPiece(id: Int): String {
        check(nativePtr != 0L) { "SentencePiece model not loaded" }
        return nativeIdToPiece(nativePtr, id)
    }

    /** Return the total vocabulary size. */
    fun vocabSize(): Int {
        check(nativePtr != 0L) { "SentencePiece model not loaded" }
        return nativeGetPieceSize(nativePtr)
    }

    /** Free the native SentencePieceProcessor. Safe to call multiple times. */
    fun release() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0L
        }
    }

    // ── Native method declarations ──────────────────────────────────────

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeEncodeAsIds(ptr: Long, text: String): IntArray
    private external fun nativeDecodeIds(ptr: Long, ids: IntArray): String
    private external fun nativePieceToId(ptr: Long, piece: String): Int
    private external fun nativeIdToPiece(ptr: Long, id: Int): String
    private external fun nativeGetPieceSize(ptr: Long): Int
    private external fun nativeFree(ptr: Long)

    companion object {
        private const val TAG = "SentencePieceJni"

        init {
            System.loadLibrary("sentencepiece_jni")
        }
    }
}
