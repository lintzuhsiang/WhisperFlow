/**
 * whisper_jni.cpp
 *
 * JNI bridge between Kotlin's WhisperCppEngine and the whisper.cpp C++ library.
 *
 * ─── How to use ────────────────────────────────────────────────────────────
 *
 * 1. Clone whisper.cpp:
 *      git clone https://github.com/ggerganov/whisper.cpp
 *
 * 2. Copy these files into  app/src/main/cpp/ :
 *      whisper.cpp   whisper.h
 *      ggml.c        ggml.h
 *      ggml-alloc.c  ggml-alloc.h
 *      ggml-backend.c ggml-backend.h
 *
 * 3. Copy this file (whisper_jni.cpp) into  app/src/main/cpp/
 *
 * 4. Build with CMakeLists.txt already provided.
 *
 * ──────────────────────────────────────────────────────────────────────────
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "whisper.h"   // whisper.cpp public API

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// nativeInitContext
//   Kotlin: private external fun nativeInitContext(modelPath: String): Long
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jlong JNICALL
Java_com_liveTranslation_ai_WhisperCppEngine_nativeInitContext(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPath)
{
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading whisper model from: %s", path);

    // whisper_init_from_file returns a whisper_context* (or null on failure).
    struct whisper_context* ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file returned null");
        return 0L;
    }

    LOGI("whisper_context allocated at %p", (void*)ctx);
    return reinterpret_cast<jlong>(ctx);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeTranscribe
//   Kotlin: private external fun nativeTranscribe(
//               contextPtr: Long, audioData: FloatArray,
//               language: String, translate: Boolean): String
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_liveTranslation_ai_WhisperCppEngine_nativeTranscribe(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    contextPtr,
        jfloatArray audioData,
        jstring  language,
        jboolean translate)
{
    auto* ctx = reinterpret_cast<struct whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        LOGE("nativeTranscribe: null context pointer");
        return env->NewStringUTF("");
    }

    // ── Unpack float array from JVM ──────────────────────────────────────
    jsize      numSamples = env->GetArrayLength(audioData);
    jfloat*    samples    = env->GetFloatArrayElements(audioData, nullptr);

    // ── Build whisper_full_params ────────────────────────────────────────
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads     = 4;       // Tune based on device core count
    params.translate     = translate == JNI_TRUE;
    params.no_context    = true;    // Don't carry context between calls (stateless chunks)
    params.single_segment= false;

    // Language: empty string → auto-detect
    const char* lang = env->GetStringUTFChars(language, nullptr);
    params.language = (lang != nullptr && lang[0] != '\0') ? lang : nullptr;

    LOGI("Running whisper_full: samples=%d, lang=%s, translate=%d",
         numSamples, params.language ? params.language : "auto", translate);

    // ── Run inference ────────────────────────────────────────────────────
    int ret = whisper_full(ctx, params, samples, (int)numSamples);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return env->NewStringUTF("");
    }

    // ── Collect segments ──────────────────────────────────────────────────
    std::string result;
    int nSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            if (!result.empty()) result += ' ';
            result += text;
        }
    }

    LOGI("Transcription (%d segments): %s", nSegments, result.c_str());
    return env->NewStringUTF(result.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeFreeContext
//   Kotlin: private external fun nativeFreeContext(contextPtr: Long)
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_liveTranslation_ai_WhisperCppEngine_nativeFreeContext(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   contextPtr)
{
    auto* ctx = reinterpret_cast<struct whisper_context*>(contextPtr);
    if (ctx != nullptr) {
        LOGI("Freeing whisper_context at %p", (void*)ctx);
        whisper_free(ctx);
    }
}
