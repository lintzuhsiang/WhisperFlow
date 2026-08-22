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
#include <chrono>
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
// Helper: build a 3-element String[] to return [transcript, detectedLanguage, timing]
// ─────────────────────────────────────────────────────────────────────────────
static jobjectArray makeResultArray(JNIEnv* env, const char* text, const char* lang,
                                    const char* timing = "") {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(3, stringClass, env->NewStringUTF(""));
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(text ? text : ""));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(lang ? lang : ""));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(timing ? timing : ""));
    return arr;
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: build a 5-element String[] for language detection results:
//   [topLang, topConfidence, secondLang, secondConfidence, elapsedMs]
// ─────────────────────────────────────────────────────────────────────────────
static jobjectArray makeDetectResult(JNIEnv* env,
                                     const char* topLang, float topConf,
                                     const char* secondLang, float secondConf,
                                     long long elapsedMs) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(5, stringClass, env->NewStringUTF(""));
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(topLang ? topLang : ""));

    char buf[32];
    snprintf(buf, sizeof(buf), "%.4f", topConf);
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(buf));

    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(secondLang ? secondLang : ""));
    snprintf(buf, sizeof(buf), "%.4f", secondConf);
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(buf));

    snprintf(buf, sizeof(buf), "%lld", elapsedMs);
    env->SetObjectArrayElement(arr, 4, env->NewStringUTF(buf));
    return arr;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeTranscribe
//   Kotlin: private external fun nativeTranscribe(
//               contextPtr: Long, audioData: FloatArray,
//               language: String, translate: Boolean,
//               prompt: String): Array<String>
//
//   Returns a 2-element String array:
//     [0] = transcript text
//     [1] = detected language code (e.g. "en", "ja", "zh")
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_liveTranslation_ai_WhisperCppEngine_nativeTranscribe(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    contextPtr,
        jfloatArray audioData,
        jstring  language,
        jboolean translate,
        jstring  prompt,
        jint     nThreads)
{
    auto* ctx = reinterpret_cast<struct whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        LOGE("nativeTranscribe: null context pointer");
        return makeResultArray(env, "", "");
    }

    // ── Unpack float array from JVM ──────────────────────────────────────
    jsize      numSamples = env->GetArrayLength(audioData);
    jfloat*    samples    = env->GetFloatArrayElements(audioData, nullptr);

    // ── Build whisper_full_params ────────────────────────────────────────
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads     = (nThreads > 0) ? nThreads : 4;  // Configurable from Kotlin
    params.translate     = translate == JNI_TRUE;
    params.single_segment= false;

    // Lower no_speech threshold so Whisper doesn't silently drop windows that
    // start with a short silence prefix (e.g. from network rebuffering).
    // Default is 0.6 — too aggressive; 0.3 preserves real speech even when
    // the encoder assigns moderate silence probability to a noisy window.
    params.no_speech_thold = 0.3f;

    // Pure greedy decode — prevents stochastic variation between windows
    // that can confuse the sliding-window stitching logic.
    params.temperature     = 0.0f;

    // Language: empty string → auto-detect
    const char* lang = env->GetStringUTFChars(language, nullptr);
    params.language = (lang != nullptr && lang[0] != '\0') ? lang : nullptr;

    // ── Context prompt (previous transcription) ──────────────────────────
    // When a non-empty prompt is supplied, feed it as initial_prompt so the
    // decoder can stitch words that were cut at the sliding-window boundary.
    // With a prompt present, we enable context carry-over (no_context=false).
    const char* promptCStr = env->GetStringUTFChars(prompt, nullptr);
    bool hasPrompt = (promptCStr != nullptr && promptCStr[0] != '\0');

    if (hasPrompt) {
        params.no_context     = false;
        params.initial_prompt = promptCStr;
    } else {
        params.no_context     = true;
        params.initial_prompt = nullptr;
    }

    LOGI("Running whisper_full: samples=%d, lang=%s, translate=%d, n_threads=%d, prompt_len=%d",
         numSamples, params.language ? params.language : "auto", translate,
         params.n_threads, hasPrompt ? (int)strlen(promptCStr) : 0);

    // ── Run inference (timed) ────────────────────────────────────────────
    auto t_start = std::chrono::steady_clock::now();
    int ret = whisper_full(ctx, params, samples, (int)numSamples);
    auto t_end = std::chrono::steady_clock::now();
    long long inference_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(prompt, promptCStr);

    LOGI("PERF | whisper_full: %lld ms (n_threads=%d, samples=%d, audio_sec=%.2f)",
         inference_ms, params.n_threads, numSamples,
         (float)numSamples / 16000.0f);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return makeResultArray(env, "", "");
    }

    // ── Extract detected language ─────────────────────────────────────────
    int lang_id = whisper_full_lang_id(ctx);
    const char* detected_lang = whisper_lang_str(lang_id);
    LOGI("Detected language: %s (id=%d)", detected_lang ? detected_lang : "unknown", lang_id);

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

    // Return inference timing as 3rd array element so Kotlin can log it
    char timing_str[32];
    snprintf(timing_str, sizeof(timing_str), "%lld", inference_ms);
    return makeResultArray(env, result.c_str(), detected_lang, timing_str);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeDetectLanguage
//   Kotlin: private external fun nativeDetectLanguage(
//               contextPtr: Long, audioData: FloatArray, nThreads: Int): Array<String>
//
// Runs language-only auto-detection on the first ≤30s of audio (Whisper's
// language detector uses the first 30s mel window). This is MUCH faster
// than a full decode and MUCH more accurate than language-token sampling
// on a short 2-3s window — because it operates on a decoder-friendly
// receptive field rather than a sliver of audio.
//
// Use it once during priming to pick a source language, then lock it and
// pass as `language` to subsequent whisper_full() calls so the decoder
// stops re-guessing per window.
//
// Returns a 5-element String[]:
//   [0] topLang           e.g. "ja"
//   [1] topConfidence     e.g. "0.9823"
//   [2] secondLang        e.g. "zh"
//   [3] secondConfidence  e.g. "0.0054"
//   [4] elapsedMs         inference wall-clock in ms
//
// On failure, all fields are empty/zero.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_liveTranslation_ai_WhisperCppEngine_nativeDetectLanguage(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    contextPtr,
        jfloatArray audioData,
        jint     nThreads)
{
    auto* ctx = reinterpret_cast<struct whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        LOGE("nativeDetectLanguage: null context pointer");
        return makeDetectResult(env, "", 0.0f, "", 0.0f, 0);
    }

    const int threads = (nThreads > 0) ? nThreads : 4;

    jsize      numSamples = env->GetArrayLength(audioData);
    jfloat*    samples    = env->GetFloatArrayElements(audioData, nullptr);

    // Whisper's language detector operates on mel of the first 30 seconds.
    // Cap the input here — extra samples beyond 30s don't help.
    constexpr int MAX_DETECT_SAMPLES = 16000 * 30;  // 30s at 16kHz
    const int detectSamples = (numSamples > MAX_DETECT_SAMPLES)
                              ? MAX_DETECT_SAMPLES
                              : (int)numSamples;

    auto t_start = std::chrono::steady_clock::now();

    // Build mel spectrogram from the provided float PCM samples.
    // Note: whisper_pcm_to_mel does NOT run full inference — it just turns
    // PCM into the mel representation needed by lang_auto_detect.
    int mel_ret = whisper_pcm_to_mel(ctx, samples, detectSamples, threads);
    if (mel_ret != 0) {
        LOGE("whisper_pcm_to_mel failed: %d", mel_ret);
        env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
        return makeDetectResult(env, "", 0.0f, "", 0.0f, 0);
    }

    const int n_langs = whisper_lang_max_id() + 1;
    std::vector<float> probs(n_langs, 0.0f);

    // Run language auto-detect. Returns the id of the most probable language,
    // and fills probs[] with the full distribution.
    int top_id = whisper_lang_auto_detect(ctx, /*offset_ms=*/0, threads, probs.data());

    auto t_end = std::chrono::steady_clock::now();
    long long elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            t_end - t_start).count();

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (top_id < 0 || top_id >= n_langs) {
        LOGE("whisper_lang_auto_detect returned invalid id %d", top_id);
        return makeDetectResult(env, "", 0.0f, "", 0.0f, elapsed_ms);
    }

    // Find the second-highest probability for confidence gap analysis.
    int   second_id   = -1;
    float second_prob = 0.0f;
    for (int i = 0; i < n_langs; ++i) {
        if (i != top_id && probs[i] > second_prob) {
            second_prob = probs[i];
            second_id   = i;
        }
    }

    const char* top_lang    = whisper_lang_str(top_id);
    const char* second_lang = (second_id >= 0) ? whisper_lang_str(second_id) : "";
    const float top_conf    = probs[top_id];

    LOGI("PERF | lang_auto_detect: top=%s(%.3f), 2nd=%s(%.3f), samples=%d, audio_sec=%.2f, elapsed=%lldms",
         top_lang ? top_lang : "?", top_conf,
         second_lang ? second_lang : "?", second_prob,
         detectSamples, (float)detectSamples / 16000.0f, elapsed_ms);

    return makeDetectResult(env,
                            top_lang    ? top_lang    : "",
                            top_conf,
                            second_lang ? second_lang : "",
                            second_prob,
                            elapsed_ms);
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
