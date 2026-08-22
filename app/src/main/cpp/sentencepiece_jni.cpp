/**
 * sentencepiece_jni.cpp
 *
 * Thin JNI bridge between Kotlin's SentencePieceJni class and the
 * official SentencePiece C++ library.
 *
 * Exposes exactly 7 native methods: load, encodeAsIds, decodeIds,
 * pieceToId, idToPiece, getPieceSize, free.
 *
 * The SentencePieceProcessor* is passed across the JNI boundary as a
 * jlong (same pattern used by whisper_jni.cpp for whisper_context*).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "sentencepiece_processor.h"

#define LOG_TAG "SentencePieceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// All functions live in the com.liveTranslation.ai.SentencePieceJni class.
// JNI naming: Java_<package>_<Class>_<method>  (dots → underscores).

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// nativeLoad(modelPath: String): Long
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeLoad(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPath)
{
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading SentencePiece model from: %s", path);

    auto* sp = new sentencepiece::SentencePieceProcessor();
    const auto status = sp->Load(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!status.ok()) {
        LOGE("SentencePiece load failed: %s", status.ToString().c_str());
        delete sp;
        return 0L;
    }

    LOGI("SentencePiece loaded – vocab size: %d", sp->GetPieceSize());
    return reinterpret_cast<jlong>(sp);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeEncodeAsIds(ptr: Long, text: String): IntArray
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jintArray JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeEncodeAsIds(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ptr,
        jstring text)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);
    const char* str = env->GetStringUTFChars(text, nullptr);

    std::vector<int> ids;
    sp->Encode(str, &ids);
    env->ReleaseStringUTFChars(text, str);

    jintArray result = env->NewIntArray(static_cast<jsize>(ids.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()), ids.data());
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeDecodeIds(ptr: Long, ids: IntArray): String
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeDecodeIds(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ptr,
        jintArray ids)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);

    jsize  len   = env->GetArrayLength(ids);
    jint*  elems = env->GetIntArrayElements(ids, nullptr);

    std::vector<int> idVec(elems, elems + len);
    env->ReleaseIntArrayElements(ids, elems, JNI_ABORT);

    std::string decoded;
    sp->Decode(idVec, &decoded);
    return env->NewStringUTF(decoded.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// nativePieceToId(ptr: Long, piece: String): Int
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativePieceToId(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ptr,
        jstring piece)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);
    const char* str = env->GetStringUTFChars(piece, nullptr);
    int id = sp->PieceToId(str);
    env->ReleaseStringUTFChars(piece, str);
    return id;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeIdToPiece(ptr: Long, id: Int): String
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeIdToPiece(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ptr,
        jint    id)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);
    const auto& piece = sp->IdToPiece(id);
    return env->NewStringUTF(piece.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeGetPieceSize(ptr: Long): Int
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeGetPieceSize(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   ptr)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);
    return sp->GetPieceSize();
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeFree(ptr: Long)
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_liveTranslation_ai_SentencePieceJni_nativeFree(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   ptr)
{
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(ptr);
    if (sp != nullptr) {
        LOGI("Freeing SentencePieceProcessor at %p", (void*)sp);
        delete sp;
    }
}

} // extern "C"
