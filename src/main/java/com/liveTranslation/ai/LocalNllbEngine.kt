package com.liveTranslation.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer

/**
 * On-device Neural Machine Translation backed by Meta's NLLB-200 model
 * (No Language Left Behind) running inside ONNX Runtime.
 *
 * ─── File placement guide ──────────────────────────────────────────────────
 *
 *  ONNX model  →  app/src/main/assets/
 *                   └── nllb-200-distilled-600M.onnx
 *
 *  Tokenizer   →  app/src/main/assets/
 *                   ├── sentencepiece.bpe.model   (SentencePiece vocab)
 *                   └── tokenizer_config.json     (optional metadata)
 *
 *  Source:
 *   • ONNX export: https://huggingface.co/Helsinki-NLP/opus-mt or
 *                  https://github.com/bhaskatripathi/nllb_onnx
 *   • Recommended: facebook/nllb-200-distilled-600M converted via
 *                  Optimum: `optimum-cli export onnx --model facebook/nllb-200-distilled-600M`
 *
 * ──────────────────────────────────────────────────────────────────────────
 *
 * NOTE: Full tokenization (SentencePiece → token IDs) and beam-search
 *       decoding are not trivial; in production use a JNI wrapper around
 *       the sentencepiece C++ library or port the tokenizer logic to Kotlin.
 *       The stubs below clearly mark every location that needs real
 *       implementation.
 */
class LocalNllbEngine(private val context: Context) : TranslationEngine {

    // ONNX Runtime environment and inference session
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    init {
        loadModel()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            // The Optimum CLI export produces a split encoder/decoder model under
            // nllb_onnx_output/.  Load the encoder as a smoke-test; full
            // encoder-decoder beam-search is TODO (needs SentencePiece + decoder loop).
            val encoderFile = context.filesDir.resolve(ENCODER_FILENAME)
            if (!encoderFile.exists()) {
                context.assets.open(ENCODER_ASSET_PATH).use { src ->
                    encoderFile.outputStream().use { dst -> src.copyTo(dst) }
                }
                Log.i(TAG, "NLLB encoder copied to ${encoderFile.absolutePath}")
            }

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                // Do NOT call addNnapi() – it may not support all seq2seq ops
            }

            ortSession = ortEnv.createSession(encoderFile.absolutePath, opts)
            Log.i(TAG, "NLLB encoder session ready. Full decoder loop is TODO.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLLB model – translate() will echo source text", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // TranslationEngine implementation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Translate [text] from [sourceLang] into [targetLang].
     *
     * NLLB uses its own language tag format; see [toNllbTag] for the mapping.
     *
     * This runs synchronously and should be called from Dispatchers.Default.
     */
    override fun translate(text: String, sourceLang: String, targetLang: String): String {
        val session = ortSession ?: run {
            Log.w(TAG, "translate() called but session is null")
            return text  // Fall back: return original text
        }

        return try {
            // ── Step 1: Tokenize ─────────────────────────────────────────
            // TODO: Replace with real SentencePiece tokenizer that produces
            //       NLLB vocab IDs.  Example libraries:
            //         • com.github.decitrig:sentencepiece-java  (JNI)
            //         • Kotlin port of fast_tokenizer
            val inputIds: LongArray = tokenize(text, sourceLang)
            val attentionMask: LongArray = LongArray(inputIds.size) { 1L }

            // ── Step 2: Build ONNX tensors ────────────────────────────────
            val shape = longArrayOf(1L, inputIds.size.toLong())  // [batch=1, seq_len]

            val inputTensor        = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds),   shape)
            val attentionMaskTensor= OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), shape)

            // Many seq2seq ONNX exports also expect forced_bos_token_id for the
            // target language.  Check your specific model's input names:
            //   session.inputNames.forEach { Log.d(TAG, "Input: $it") }
            val forcedBosId = LongArray(1) { nllbTokenId(toNllbTag(targetLang)) }
            val forcedBosTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(forcedBosId), longArrayOf(1L, 1L))

            // ── Step 3: Run inference ─────────────────────────────────────
            val inputs = mapOf(
                "input_ids"           to inputTensor,
                "attention_mask"      to attentionMaskTensor,
                "forced_bos_token_id" to forcedBosTensor
            )

            val results = session.run(inputs)

            // ── Step 4: Decode output token IDs → text ────────────────────
            // TODO: Replace with real beam-search / greedy decoder that calls
            //       sentencepiece.decode().
            val outputIds = (results[0].getValue() as Array<LongArray>)[0]
            detokenize(outputIds)
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            text  // Fall back to original text on error
        }
    }

    override fun release() {
        ortSession?.close()
        ortSession = null
        ortEnv.close()
        Log.i(TAG, "NLLB ORT session released")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tokenizer stubs  (replace with real SentencePiece JNI calls)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * STUB – Convert [text] to a sequence of NLLB vocabulary token IDs.
     *
     * Real implementation:  load `sentencepiece.bpe.model` from assets and
     * call SentencePieceProcessor.encode(text) → List<Int>.toLongArray()
     */
    private fun tokenize(text: String, sourceLang: String): LongArray {
        // Placeholder: splits on whitespace and maps each "word" to its length.
        // THIS WILL NOT PRODUCE MEANINGFUL TRANSLATIONS.
        Log.w(TAG, "tokenize() is a stub – output will be meaningless")
        return longArrayOf(
            nllbTokenId(toNllbTag(sourceLang)),  // language token first
            *text.split(" ").map { it.length.toLong() }.take(64).toLongArray()
        )
    }

    /**
     * STUB – Decode output token IDs back to a human-readable string.
     *
     * Real implementation: SentencePieceProcessor.decode(ids.map { it.toInt() })
     */
    private fun detokenize(ids: LongArray): String {
        Log.w(TAG, "detokenize() is a stub – returning placeholder")
        return "[Translation placeholder – implement SentencePiece decoder]"
    }

    // ──────────────────────────────────────────────────────────────────────
    // NLLB language tag helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Maps a BCP-47 language code to the NLLB-200 special token tag.
     *
     * Full list: https://github.com/facebookresearch/flores/blob/main/flores200/README.md
     */
    private fun toNllbTag(bcp47: String): String = when (bcp47.lowercase()) {
        "en"          -> "eng_Latn"
        "zh", "zh-cn" -> "zho_Hans"
        "zh-tw"       -> "zho_Hant"
        "ja"          -> "jpn_Jpan"
        "ko"          -> "kor_Hang"
        "es"          -> "spa_Latn"
        "fr"          -> "fra_Latn"
        "de"          -> "deu_Latn"
        "ar"          -> "arb_Arab"
        "pt"          -> "por_Latn"
        "ru"          -> "rus_Cyrl"
        "hi"          -> "hin_Deva"
        else          -> "${bcp47}_Latn"  // Best-effort fallback
    }

    /**
     * STUB – Look up a language tag's integer token ID from the NLLB vocabulary.
     *
     * Real implementation: load the sentencepiece model and call
     * SentencePieceProcessor.pieceToId(tag)
     */
    private fun nllbTokenId(nllbTag: String): Long {
        // These IDs are illustrative only; actual IDs depend on the SentencePiece vocab.
        return when (nllbTag) {
            "eng_Latn" -> 256047L
            "zho_Hans" -> 256099L
            "jpn_Jpan" -> 256093L
            "kor_Hang" -> 256182L
            else       -> 256000L  // Unknown: use a safe fallback
        }
    }

    companion object {
        private const val TAG = "LocalNllbEngine"

        // Optimum CLI export places the encoder here inside assets/
        private const val ENCODER_ASSET_PATH = "nllb_onnx_output/encoder_model.onnx"
        // Filename used in app's internal storage cache
        private const val ENCODER_FILENAME   = "nllb_encoder_model.onnx"
    }
}
