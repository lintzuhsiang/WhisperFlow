package com.liveTranslation.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloud Speech-to-Text engine backed by OpenAI's Whisper API.
 *
 * This is the cloud counterpart to [WhisperCppEngine]. Same contract:
 *   PCM 16-bit mono 16 kHz in → text out.
 *
 * Why cloud at all? Local whisper-medium can't run real-time on Snapdragon
 * 8 Elite (RTF ~5x). The dual-track player's lookahead window (~8 s) is
 * larger than this engine's typical end-to-end latency (~1–1.5 s), so the
 * user sees subtitles as if they were generated on-device.
 *
 * Concurrency: OkHttp is thread-safe; multiple coroutines may call
 * [transcribe] in parallel. OpenAI also doesn't enforce per-key concurrency
 * caps in practice for the Whisper endpoint, so we benefit from running
 * several windows simultaneously inside the lookahead.
 */
class OpenAITranscriptionEngine(
    private val apiKey: String,
    private val modelName: String = "whisper-1",
    /** Override for self-hosted gateways or test mocks. */
    private val baseUrl: String = "https://api.openai.com/v1"
) : SpeechToTextEngine {

    private val client: OkHttpClient = OkHttpClient.Builder()
        // Generous timeouts: Whisper API can take 1–3 s for a 3 s window
        // and we don't want to lose work mid-flight on a slow link.
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Last detected language. The Whisper API returns this in
     * verbose-json response format. Stored atomically because [transcribe]
     * may be invoked concurrently from multiple ASR workers.
     */
    private val lastDetectedLang = AtomicReference("")
    override val detectedLanguage: String get() = lastDetectedLang.get()

    override fun transcribe(
        audioBuffer: ShortArray,
        sourceLang: String,
        targetLang: String,
        prompt: String
    ): String {
        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenAI API key missing — returning empty transcription")
            return ""
        }
        if (audioBuffer.isEmpty()) return ""

        // Convert PCM (S16LE, 16 kHz mono) to a tiny in-memory WAV blob.
        // Whisper API accepts wav/mp3/m4a/etc. WAV is zero-effort to build
        // and the network overhead at 16 kHz mono is small (~32 KB per second).
        val wavBytes = pcmToWav(audioBuffer, SAMPLE_RATE, channels = 1)

        // Multipart body. Field names follow the public REST spec.
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "audio.wav",
                body = wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", modelName)
            // verbose_json gives us {text, language, segments[]} so we can
            // capture the detected language without an extra round-trip.
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")

        // Source language hint (only when caller is sure — avoids forcing
        // the wrong language when "auto" is passed)
        if (sourceLang.isNotBlank() && sourceLang.lowercase() != "auto") {
            multipartBuilder.addFormDataPart("language", sourceLang.lowercase())
        }

        // Prompt: helps stitch words across sliding-window boundaries, same
        // role as in WhisperCppEngine.transcribe(prompt = ...)
        if (prompt.isNotBlank()) {
            multipartBuilder.addFormDataPart("prompt", prompt.take(MAX_PROMPT_CHARS))
        }

        // targetLang == "en" toggles the /translations endpoint, which
        // produces English text from any source language. Useful when the
        // caller explicitly wants whisper.cpp's "translate" behaviour.
        // For all other targets we transcribe in source language and let
        // a downstream MT engine handle the translation.
        val endpoint = if (targetLang.equals("en", ignoreCase = true) &&
                           !sourceLang.equals("en", ignoreCase = true)) {
            "$baseUrl/audio/translations"
        } else {
            "$baseUrl/audio/transcriptions"
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBuilder.build())
            .build()

        val started = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - started
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(500)
                    Log.e(TAG, "Whisper API ${response.code} after ${elapsed}ms: $errorBody")
                    return ""
                }
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val text = json.optString("text", "").trim()
                val detected = json.optString("language", "")
                if (detected.isNotBlank()) {
                    lastDetectedLang.set(detected)
                }
                Log.d(TAG, "transcribed ${audioBuffer.size} samples in ${elapsed}ms → \"${text.take(60)}\"")
                text
            }
        } catch (e: Exception) {
            // Catch IOException, JSONException, etc. — we never want a single
            // network blip to crash the ASR pipeline. Returning "" lets the
            // caller skip this window and move on.
            Log.e(TAG, "Whisper API call failed", e)
            ""
        }
    }

    override fun release() {
        // OkHttpClient pools sockets; explicit shutdown lets the JVM exit
        // without waiting for keepalive idle.
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ────────────────────────────────────────────────────────────────────
    // PCM → WAV
    // ────────────────────────────────────────────────────────────────────

    /**
     * Wrap a PCM-S16LE buffer in a minimal RIFF/WAVE header.
     * No external libs (avoids ffmpeg/AudioRecord roundtrips). Header is 44
     * bytes regardless of payload size.
     */
    private fun pcmToWav(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val byteCount = pcm.size * 2  // 16-bit samples
        val totalSize = 44 + byteCount
        val out = ByteArrayOutputStream(totalSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF chunk descriptor
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + byteCount)              // file size - 8
            put("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt sub-chunk
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                          // PCM header length
            putShort(1)                         // audioFormat = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)   // byte rate
            putShort((channels * 2).toShort())  // block align
            putShort(16)                        // bits per sample
            // data sub-chunk
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(byteCount)
        }
        out.write(header.array())

        // Payload — pre-allocate a buffer to avoid the per-sample boxing of
        // ByteBuffer.putShort() when called in a tight loop.
        val payload = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) payload.putShort(s)
        out.write(payload.array())
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "OpenAITranscription"
        private const val SAMPLE_RATE = 16_000
        // OpenAI accepts up to 244-token prompts; our windows are short so
        // we trim aggressively to keep request body small.
        private const val MAX_PROMPT_CHARS = 480
    }
}
