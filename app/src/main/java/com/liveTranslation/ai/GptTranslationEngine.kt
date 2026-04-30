package com.liveTranslation.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud translation engine using OpenAI-compatible APIs. 
 * This can be configured to use gpt-4o-mini, gpt-3.5-turbo, etc.
 */
class GptTranslationEngine(
    private val apiKey: String,
    private val modelName: String = "gpt-4o-mini"
) : TranslationEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is missing")
            return text
        }
        if (text.isBlank()) return text

        val srcName = if (sourceLang == "auto") "the detected language" else langName(sourceLang)
        val tgtName = langName(targetLang)

        val systemPrompt =
            "Real-time translation. Translate from $srcName to $tgtName. " +
            "Return only the translation text."

        val payload = JsonObject().apply {
            addProperty("model", modelName)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to text)
            )))
            addProperty("temperature", 0.0)
            addProperty("max_tokens", 120)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "API Error (${response.code}): $errorBody")
                    return text
                }

                val responseStr = response.body?.string() ?: ""
                val jsonResponse = gson.fromJson(responseStr, JsonObject::class.java)
                
                jsonResponse.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                    ?.trim() ?: text
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during GPT API call", e)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in GPT translation", e)
            text
        }
    }

    override fun release() {
        // OkHttpClient handles its own connection pools, but we can clean up if needed
        client.dispatcher.executorService.shutdown()
        Log.i(TAG, "GptTranslationEngine released")
    }

    companion object {
        private const val TAG = "GptTranslationEngine"

        private val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "ja" to "Japanese",
            "zh" to "Chinese",
            "zh-cn" to "Simplified Chinese",
            "zh-tw" to "Traditional Chinese",
            "ko" to "Korean",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "ar" to "Arabic",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "hi" to "Hindi",
            "it" to "Italian",
            "nl" to "Dutch",
            "pl" to "Polish",
            "tr" to "Turkish",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "id" to "Indonesian",
            "ms" to "Malay",
            "tl" to "Filipino"
        )

        private fun langName(code: String): String =
            LANGUAGE_NAMES[code.lowercase()] ?: code
    }
}
