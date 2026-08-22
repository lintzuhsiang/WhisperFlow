package com.liveTranslation.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.util.EnumSet

/**
 * On-device Neural Machine Translation powered by Meta's NLLB-200-distilled-600M
 * running as a three-part ONNX seq2seq pipeline:
 *
 *   1. **encoder_model.onnx**           – produces encoder hidden states
 *   2. **decoder_model.onnx**           – first auto-regressive step (no KV cache)
 *   3. **decoder_with_past_model.onnx** – subsequent steps (with KV cache)
 *
 * Tokenization uses the official **SentencePiece C++ library** via a thin
 * JNI bridge ([SentencePieceJni]) for correct CJK handling.
 *
 * Generation uses **greedy search** (argmax) with EOS-based stopping
 * and a [MAX_LENGTH] ceiling.
 *
 * ── Resource-management strategy ────────────────────────────────────────
 *
 * `OrtSession.Result` owns the native memory of every tensor it produced.
 * Closing the Result invalidates those tensors. We therefore keep each
 * decoder Result alive exactly until the *next* `session.run()` has
 * consumed its KV-cache tensors, then close it. This avoids both:
 *   • **OOM** from accumulating unclosed Results, and
 *   • **expensive deep-copies** of the KV-cache float buffers.
 *
 * A single top-level `try / finally` guarantees cleanup of all long-lived
 * resources (encoder Result, decoder attention-mask tensor, and the last
 * decoder Result) even if an exception is thrown mid-generation.
 */
class LocalNllbEngine(private val context: Context) : TranslationEngine {

    // ── ONNX Runtime ────────────────────────────────────────────────────
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var decoderWithPastSession: OrtSession? = null
    private var kvCacheBroken: Boolean = false
    private var kvModeLogged: Boolean = false

    // ── Tokenizer (JNI) ─────────────────────────────────────────────────
    private var spProcessor: SentencePieceJni? = null

    // ── NLLB language tag → added-token ID (loaded from tokenizer.json) ──
    // Populated in init by [loadLangTokenIds]. Empty until then.
    // Was previously a hand-typed `mapOf(...)` constant containing 75 wrong
    // IDs (e.g. zho_Hant=256202 which is actually zul_Latn → "zh-tw" target
    // produced Zulu output). The canonical source of truth is the
    // `added_tokens` array in tokenizer.json shipped with the model.
    private var langTokenIds: Map<String, Long> = emptyMap()

    init {
        loadTokenizer()
        loadLangTokenIds()
        loadModels()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────

    private fun loadTokenizer() {
        try {
            val processor = SentencePieceJni()

            // Try external storage first (adb-pushed), fall back to assets
            val externalModel = context.getExternalFilesDir(null)
                ?.resolve("nllb_onnx_output/sentencepiece.bpe.model")

            val loaded = if (externalModel != null && externalModel.exists()) {
                Log.i(TAG, "Loading SentencePiece from external: ${externalModel.absolutePath}")
                processor.load(externalModel.absolutePath)
            } else {
                // Assets cannot be read by path — copy to cache first
                val cacheFile = File(context.cacheDir, "sentencepiece.bpe.model")
                if (!cacheFile.exists()) {
                    context.assets.open("sentencepiece.bpe.model").use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                Log.i(TAG, "Loading SentencePiece from assets cache: ${cacheFile.absolutePath}")
                processor.load(cacheFile.absolutePath)
            }

            if (!loaded) {
                Log.e(TAG, "SentencePiece native load returned false – check logcat for C++ errors")
                return
            }

            spProcessor = processor
            Log.i(TAG, "SentencePiece vocab size: ${processor.vocabSize()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SentencePiece tokenizer", e)
        }
    }

    /**
     * Load the NLLB language-tag → token-ID map from the canonical source
     * (tokenizer.json shipped with the HuggingFace model). Replaces a
     * previously hand-typed table that drifted out of sync with the model
     * (75 wrong IDs, including `zho_Hant` mapped to the Zulu token).
     *
     * Search order: external nllb_int8/, external nllb_onnx_output/, then assets.
     * Assets is always present (bundled in APK) so this never fails on a
     * correctly built apk.
     *
     * Filtering rules:
     *   - id < 256000   → reserved fairseq tokens (<s>, <pad>, </s>, <unk>); skip
     *   - id == 256203  → `<mask>` (also at this range but not a language); skip
     *   - 256001 ≤ id ≤ 256202 → real NLLB-200 language tokens; keep
     */
    private fun loadLangTokenIds() {
        try {
            val text = readTokenizerJsonText()
            if (text == null) {
                Log.e(TAG, "tokenizer.json not found in any expected location")
                return
            }

            val root = JSONObject(text)
            val added = root.getJSONArray("added_tokens")

            val map = HashMap<String, Long>(256)
            for (i in 0 until added.length()) {
                val entry = added.getJSONObject(i)
                val id = entry.getLong("id")
                val content = entry.getString("content")
                // NLLB language codes are all the form "xxx_Yyyy" and live in
                // [256001, 256202]. Skip reserved (<s>, </s>, …) and <mask>.
                if (id < FIRST_LANG_TOKEN_ID) continue
                if (!content.contains('_')) continue   // skips <mask>
                map[content] = id
            }

            if (map.isEmpty()) {
                Log.e(TAG, "tokenizer.json had no language tokens (parse failure?)")
                return
            }

            langTokenIds = map
            Log.i(TAG, "Loaded ${map.size} NLLB lang token IDs from tokenizer.json " +
                "(zho_Hant=${map["zho_Hant"]}, eng_Latn=${map["eng_Latn"]})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tokenizer.json for lang token IDs", e)
        }
    }

    /**
     * Locate and read tokenizer.json as a UTF-8 string, or null if missing.
     *
     * Search order:
     *   1. external nllb_int8/tokenizer.json   (adb-pushed by power users)
     *   2. external nllb_onnx_output/tokenizer.json
     *   3. assets/nllb_tokenizer.json          (always bundled — see below)
     *
     * Note: build.gradle.kts excludes the whole `nllb_onnx_output/` and
     * `nllb_int8/` directories from the APK via `ignoreAssetsPattern` to
     * avoid bundling 6.9 GB of ONNX weights, which means the tokenizer.json
     * inside those dirs is ALSO excluded. We therefore keep a separate copy
     * at `assets/nllb_tokenizer.json` (~17 MB, text/JSON, compresses well)
     * as the bundled fallback so the engine can always self-bootstrap its
     * language-tag map even if the user hasn't pushed tokenizer.json to
     * external storage.
     */
    private fun readTokenizerJsonText(): String? {
        val externalDir = context.getExternalFilesDir(null)
        val candidates = listOfNotNull(
            externalDir?.resolve("$INT8_MODEL_DIR/tokenizer.json"),
            externalDir?.resolve("$FP32_MODEL_DIR/tokenizer.json")
        )
        for (f in candidates) {
            if (f.exists()) {
                Log.i(TAG, "Reading tokenizer.json from ${f.absolutePath}")
                return f.readText(Charsets.UTF_8)
            }
        }

        // Bundled fallback (always present — see build.gradle.kts comment).
        val assetCandidates = listOf(
            BUNDLED_TOKENIZER_ASSET,                   // primary, always packaged
            "$FP32_MODEL_DIR/tokenizer.json"           // legacy path, in case someone unbundled it
        )
        for (assetPath in assetCandidates) {
            try {
                val text = context.assets.open(assetPath).use { it.bufferedReader(Charsets.UTF_8).readText() }
                Log.i(TAG, "Reading tokenizer.json from assets/$assetPath")
                return text
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        Log.w(TAG, "tokenizer.json not found in external dirs or any bundled asset path")
        return null
    }

    private fun loadModels() {
        try {
            val externalDir = context.getExternalFilesDir(null)

            // ── INT8 internal storage directory (always readable by this app) ──
            val internalInt8Dir = File(context.filesDir, INT8_MODEL_DIR)

            // Attempt a one-time import from /sdcard/Download/nllb_int8/ if the
            // internal copy is missing.  On Android 11+ adb-pushed files in
            // app-specific external storage are owned by 'shell' and invisible to
            // File.listFiles(). /sdcard/Download is world-readable so we copy
            // from there into internal storage which we fully own.
            if (!File(internalInt8Dir, "encoder_model.onnx").exists()) {
                tryImportFromDownloads(internalInt8Dir)
            }

            // ── Model directory resolution ──────────────────────────────────
            // Priority:
            //  1. INT8 internal   (copied from Download, fastest + no FUSE issues)
            //  2. INT8 external   (adb push to external — works on older Android)
            //  3. FP32 external   (large, slower)
            //  4. FP32 assets     (bundled in APK, last resort)
            val externalInt8Dir = File(externalDir, INT8_MODEL_DIR)
            val externalFp32Dir = File(externalDir, FP32_MODEL_DIR)
            val internalFp32Dir = File(context.filesDir, "nllb_internal")

            val baseDir: File
            val modelVariant: String

            Log.i(TAG, "--- DIAGNOSING MODEL DIRECTORIES ---")
            Log.i(TAG, "Internal INT8 dir: ${internalInt8Dir.absolutePath}")
            internalInt8Dir.listFiles()?.forEach { f ->
                Log.i(TAG, "  internal INT8: ${f.name} (${f.length()} bytes)")
            }
            Log.i(TAG, "External files dir: ${externalDir?.absolutePath}")
            externalDir?.listFiles()?.forEach { file ->
                Log.i(TAG, "Found at root: ${file.name} (isDir=${file.isDirectory}, size=${file.length()})")
                if (file.name == INT8_MODEL_DIR) {
                    file.listFiles()?.forEach { child ->
                        Log.i(TAG, "   -> Inside nllb_int8: ${child.name} (size=${child.length()})")
                    }
                }
            }
            Log.i(TAG, "------------------------------------")

            if (File(internalInt8Dir, "encoder_model.onnx").exists()) {
                baseDir = internalInt8Dir
                modelVariant = "INT8-internal"
                Log.i(TAG, "PERF | Using INT8 models from internal storage: ${internalInt8Dir.absolutePath}")
            } else if (externalInt8Dir.exists() && File(externalInt8Dir, "encoder_model.onnx").exists()) {
                // INT8 quantized models (produced by optimum-cli) — ~2× faster, ~50% smaller
                baseDir = externalInt8Dir
                modelVariant = "INT8-external"
                Log.i(TAG, "PERF | Using INT8 quantized models from: ${externalInt8Dir.absolutePath}")
            } else if (externalFp32Dir.exists() && File(externalFp32Dir, "decoder_model.onnx_data").exists()) {
                baseDir = externalFp32Dir
                modelVariant = "FP32-external"
                Log.i(TAG, "Using FP32 models from external: ${externalFp32Dir.absolutePath}")
            } else {
                Log.i(TAG, "External models not found. Falling back to internal assets copy.")
                if (!internalFp32Dir.exists()) internalFp32Dir.mkdirs()

                val requiredModels = listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx")
                var allModelsPresent = true
                for (filename in requiredModels) {
                    val f = File(internalFp32Dir, filename)
                    if (!f.exists()) {
                        Log.i(TAG, "Copying $filename from assets...")
                        try {
                            context.assets.open("nllb_onnx_output/$filename").use { input ->
                                f.outputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (ioe: java.io.IOException) {
                            Log.e(TAG, "Missing model in assets/nllb_onnx_output/$filename", ioe)
                            allModelsPresent = false
                            break
                        }
                    }
                }
                if (!allModelsPresent) {
                    Log.e(TAG, "Not all NLLB ONNX models could be found in assets.")
                    return
                }
                baseDir = internalFp32Dir
                modelVariant = "FP32-assets"
            }

            val encoderFile = File(baseDir, "encoder_model.onnx")
            val decoderFile = File(baseDir, "decoder_model.onnx")
            val decoderWithPastFile = File(baseDir, "decoder_with_past_model.onnx")

            for (f in listOf(encoderFile, decoderFile, decoderWithPastFile)) {
                if (!f.exists()) {
                    Log.e(TAG, "Model not found: \${f.absolutePath}")
                    return
                }
            }

            // ── File-size guard ───────────────────────────────────────────
            // Important: encoder/decoder .onnx files can be tiny wrapper graphs
            // when external sidecars are present. Sidecars come in two naming
            // conventions:
            //   - ONNX official:  "<name>.onnx_data" (underscore)
            //   - ORT quantize:   "<name>.onnx.data" (dot)
            // Treat either as valid; only enforce a large floor for self-contained
            // models that don't have any sidecar.
            val minSelfContainedBytes = 200L * 1024 * 1024   // 200 MB
            val minWrapperBytes = 200L * 1024                // 200 KB

            fun hasSidecar(modelFile: File): Boolean {
                val base = modelFile.name  // e.g. "decoder_model.onnx"
                val dotName = "$base.data"            // decoder_model.onnx.data
                val underscoreName = base.removeSuffix(".onnx") + ".onnx_data" // decoder_model.onnx_data
                return File(baseDir, dotName).exists() || File(baseDir, underscoreName).exists()
            }

            val minBytesByFile = mapOf(
                encoderFile to if (hasSidecar(encoderFile)) minWrapperBytes else minSelfContainedBytes,
                decoderFile to if (hasSidecar(decoderFile)) minWrapperBytes else minSelfContainedBytes,
                decoderWithPastFile to if (hasSidecar(decoderWithPastFile)) minWrapperBytes else minSelfContainedBytes
            )

            for ((file, minBytes) in minBytesByFile) {
                if (file.length() < minBytes) {
                    Log.e(
                        TAG,
                        "Model file too small (${file.length()} bytes, minimum $minBytes): ${file.name}. " +
                            "Model set appears incomplete/corrupt; skip Local NLLB init."
                    )
                    return
                }
            }

            // P2: Encoder uses NNAPI (NPU) if available; decoders stay CPU-only.
            // NNAPI can't handle the Loop op in auto-regressive decoding, and
            // GELU's Erf op also falls back — causing costly NPU↔CPU round-trips.
            val apiOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val useNnapiEnc = ENABLE_NNAPI_ENCODER && apiOk
            val useNnapiDec = ENABLE_NNAPI_DECODER && apiOk
            try {
                val encoderOpts = buildSessionOptions(enableNnapi = useNnapiEnc)
                val decoderOpts = buildSessionOptions(
                    enableNnapi = useNnapiDec,
                    optLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT
                )


                val tLoad0 = SystemClock.elapsedRealtime()
                encoderSession = ortEnv.createSession(encoderFile.absolutePath, encoderOpts)
                val tLoad1 = SystemClock.elapsedRealtime()
                decoderSession = ortEnv.createSession(decoderFile.absolutePath, decoderOpts)
                val tLoad2 = SystemClock.elapsedRealtime()
                decoderWithPastSession = ortEnv.createSession(decoderWithPastFile.absolutePath, decoderOpts)
                val tLoad3 = SystemClock.elapsedRealtime()

                if (ENABLE_PERF_LOGS) {
                    Log.i(TAG, "PERF | session_load: encoder=${tLoad1 - tLoad0}ms, " +
                        "decoder=${tLoad2 - tLoad1}ms, decoderWP=${tLoad3 - tLoad2}ms, " +
                        "variant=$modelVariant, threads=$NLLB_INTRA_OP_THREADS, opt=ALL_OPT, " +
                        "nnapi_enc=$useNnapiEnc, nnapi_dec=$useNnapiDec")
                }
            } catch (e: Exception) {
                // If NNAPI session creation fails, fall back to CPU-only ORT.
                encoderSession?.close()
                decoderSession?.close()
                decoderWithPastSession?.close()
                encoderSession = null
                decoderSession = null
                decoderWithPastSession = null

                if (useNnapiEnc || useNnapiDec) {
                    Log.w(TAG, "NNAPI session creation failed; falling back to CPU-only ORT", e)
                    val cpuOpts = buildSessionOptions(enableNnapi = false)
                    encoderSession = ortEnv.createSession(encoderFile.absolutePath, cpuOpts)
                    decoderSession = ortEnv.createSession(decoderFile.absolutePath, cpuOpts)
                    decoderWithPastSession = ortEnv.createSession(decoderWithPastFile.absolutePath, cpuOpts)
                } else {
                    throw e
                }
            }

            Log.i(TAG, "All 3 ONNX sessions loaded (variant=$modelVariant)")
            Log.d(TAG, "  encoder   inputs : ${encoderSession?.inputNames}")
            Log.d(TAG, "  decoder   inputs : ${decoderSession?.inputNames}")
            Log.d(TAG, "  decoder  outputs : ${decoderSession?.outputInfo?.keys?.sorted()}")
            Log.d(TAG, "  decoderWP inputs : ${decoderWithPastSession?.inputNames}")
            Log.d(TAG, "  decoderWP inputs (all): ${decoderWithPastSession?.inputInfo?.keys?.sorted()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLLB ONNX models", e)
        }
    }

    /**
     * Try to copy INT8 ONNX models from /sdcard/Download/nllb_int8/ into [destDir].
     *
     * ⚠ Permission reality check (Android version matrix):
     *
     *   API ≤ 32 (Android 12 and below):
     *     READ_EXTERNAL_STORAGE is a runtime-grantable dangerous permission.
     *     We check it here; if not granted we skip and log.
     *
     *   API  33 (Android 13+):
     *     READ_EXTERNAL_STORAGE is gone. Non-media files in Downloads require
     *     MANAGE_EXTERNAL_STORAGE (too intrusive) or SAF (needs UI).
     *     On API 33+ we skip this import path and log the adb alternative.
     *
     * Dev workflow alternative (works on all API levels, no permission needed):
     *   adb shell run-as com.liveTranslation mkdir -p files/nllb_int8
     *   adb push encoder_model.onnx       "$(adb shell run-as com.liveTranslation pwd)/files/nllb_int8/"
     *   adb push decoder_model.onnx       "$(adb shell run-as com.liveTranslation pwd)/files/nllb_int8/"
     *   adb push decoder_with_past_model.onnx "$(adb shell run-as com.liveTranslation pwd)/files/nllb_int8/"
     *
     * Then restart the app — it will pick up files/nllb_int8/ directly (INT8-internal path).
     */
    private fun tryImportFromDownloads(destDir: File) {
        // On Android 13+ (API 33) READ_EXTERNAL_STORAGE doesn't exist; skip this path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(
                TAG,
                "Download import: skipped on Android 13+ (READ_EXTERNAL_STORAGE removed). " +
                    "Use 'adb shell run-as com.liveTranslation' to copy models directly to " +
                    "files/nllb_int8/ — see class KDoc for exact commands."
            )
            return
        }

        // On API ≤ 32 we still need runtime permission.
        val permGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!permGranted) {
            Log.w(
                TAG,
                "Download import: READ_EXTERNAL_STORAGE not granted at runtime \u2014 skipping. " +
                    "Grant the permission OR use 'adb shell run-as com.liveTranslation' to " +
                    "copy models directly to files/nllb_int8/."
            )
            return
        }

        val downloadSrc = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            INT8_MODEL_DIR
        )

        if (!downloadSrc.exists()) {
            Log.d(TAG, "Download import: ${downloadSrc.absolutePath} not found, skipping")
            return
        }

        val required = listOf(
            "encoder_model.onnx",
            "decoder_model.onnx",
            "decoder_with_past_model.onnx"
        )
        val minSizeBytes = 1L * 1024 * 1024   // at least 1 MB — guards against empty stubs

        val allPresent = required.all { name ->
            val f = File(downloadSrc, name)
            f.exists() && f.length() >= minSizeBytes
        }
        if (!allPresent) {
            Log.w(TAG, "Download import: not all INT8 model files found in ${downloadSrc.absolutePath}")
            return
        }

        try {
            if (!destDir.exists()) destDir.mkdirs()
            for (name in required) {
                val src = File(downloadSrc, name)
                val dst = File(destDir, name)
                if (dst.exists() && dst.length() == src.length()) {
                    Log.d(TAG, "Download import: $name already copied (${dst.length()} bytes), skipping")
                    continue
                }
                Log.i(TAG, "Download import: copying $name (${src.length()} bytes)\u2026")
                java.io.FileInputStream(src).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Download import: $name done")
            }
            Log.i(TAG, "Download import: all INT8 models imported from ${downloadSrc.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Download import failed \u2014 will try other model sources", e)
            // Clean up any partial files so a corrupted partial import doesn't
            // block the real model sources on the next launch.
            required.forEach { name -> File(destDir, name).takeIf { it.exists() }?.delete() }
        }
    }

    private fun buildSessionOptions(
        enableNnapi: Boolean,
        optLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(NLLB_INTRA_OP_THREADS)
            setInterOpNumThreads(1)
            // ALL_OPT enables constant folding, node fusion, GEMM fast-math, etc.
            // NOTE: decoder sessions use BASIC_OPT — ALL_OPT can fuse/transform
            // nodes in INT8 models in ways that break KV-cache input routing
            // (e.g. the Reshape node can no longer find past_key_values.*.encoder.*).
            setOptimizationLevel(optLevel)

            if (enableNnapi) {
                try {
                    val flags = EnumSet.noneOf(NNAPIFlags::class.java).apply {
                        if (NNAPI_USE_FP16) add(NNAPIFlags.USE_FP16)
                        if (NNAPI_DISABLE_CPU) add(NNAPIFlags.CPU_DISABLED)
                    }
                    addNnapi(flags)
                    Log.i(TAG, "NNAPI enabled for ORT (flags=$flags)")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable on this device/runtime; using CPU EP", e)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // TranslationEngine
    // ──────────────────────────────────────────────────────────────────────

    @Synchronized
    override fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text

        val sp = spProcessor
            ?: return text.also { Log.w(TAG, "Tokenizer not loaded") }
        val encoder = encoderSession
            ?: return text.also { Log.w(TAG, "Encoder session not loaded") }
        val decoder = decoderSession
            ?: return text.also { Log.w(TAG, "Decoder session not loaded") }
        val decoderWP = decoderWithPastSession
            ?: return text.also { Log.w(TAG, "Decoder-with-past session not loaded") }

        // Handle "auto" source language: use Unicode script analysis as fallback
        val srcTag = if (sourceLang == "auto" || sourceLang.isBlank()) {
            detectLanguageFromText(text)
        } else {
            toNllbTag(sourceLang)
        }
        val tgtTag = toNllbTag(targetLang)

        // NLLB language tags (e.g. "eng_Latn") are "added special tokens" with IDs
        // ABOVE the base sentencepiece vocab (256000 pieces). The raw SentencePiece
        // API cannot look them up via pieceToId() — it returns 0 (unk) for them.
        // We use a hardcoded map extracted from tokenizer.json instead.
        val srcLangId = nllbLangId(srcTag) ?: run {
            Log.w(TAG, "Source language tag '$srcTag' not in NLLB vocab — returning original")
            return text
        }
        val tgtLangId = nllbLangId(tgtTag) ?: run {
            Log.w(TAG, "Target language tag '$tgtTag' not in NLLB vocab — returning original")
            return text
        }

        if (!kvModeLogged) {
            if (ENABLE_KV_CACHE && !kvCacheBroken) {
                val needsHidden = decoderWP.inputInfo.containsKey("encoder_hidden_states")
                val needsMask = decoderWP.inputInfo.containsKey("encoder_attention_mask")
                Log.i(
                    TAG,
                    "KV-cache enabled: decoderWP needs_hidden=$needsHidden, needs_mask=$needsMask, " +
                        "inputs=${decoderWP.inputInfo.size}"
                )
            } else if (kvCacheBroken) {
                Log.w(TAG, "KV-cache disabled due to previous failure; using no-KV mode")
            } else {
                Log.i(TAG, "KV-cache disabled by config; using no-KV mode")
            }
            kvModeLogged = true
        }

        return try {
            if (ENABLE_KV_CACHE && !kvCacheBroken) {
                try {
                    runSeq2SeqGreedyWithKv(sp, encoder, decoder, decoderWP, text, srcLangId, tgtLangId)
                } catch (e: Exception) {
                    kvCacheBroken = true
                    Log.w(TAG, "KV-cache decode failed; falling back to no-KV mode", e)
                    runSeq2SeqGreedyNoKv(sp, encoder, decoder, text, srcLangId, tgtLangId)
                }
            } else {
                runSeq2SeqGreedyNoKv(sp, encoder, decoder, text, srcLangId, tgtLangId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            text
        }
    }

    override fun release() {
        encoderSession?.close()
        decoderSession?.close()
        decoderWithPastSession?.close()
        encoderSession = null
        decoderSession = null
        decoderWithPastSession = null
        spProcessor?.release()
        spProcessor = null
        Log.i(TAG, "NLLB engine released")
    }

    /** True when tokenizer + all three ONNX sessions are loaded and ready. */
    fun isReady(): Boolean {
        return spProcessor != null &&
            encoderSession != null &&
            decoderSession != null &&
            decoderWithPastSession != null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Seq2Seq greedy generation (leak-free)
    // ──────────────────────────────────────────────────────────────────────

    private fun runSeq2SeqGreedyWithKv(
        sp: SentencePieceJni,
        encoder: OrtSession,
        decoder: OrtSession,
        decoderWP: OrtSession,
        text: String,
        srcLangId: Long,    // NLLB added-token ID for the source language
        tgtTokenId: Long,   // NLLB added-token ID for the target language (forced as first output)
    ): String {

        val perfT0 = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        // ── 1. Tokenize (SentencePiece JNI) ────────────────────────────
        // srcLangId is already resolved — it's an NLLB added-token ID (256001+),
        // not a piece in the base sentencepiece vocab.
        val bpeIds = sp.encodeAsIds(text)

        // NLLB encoder input: [src_lang_id, bpe_tok₁+1, …, bpe_tokₙ+1, EOS]
        // Each SP id gets FAIRSEQ_OFFSET added — see companion-object comment.
        val inputIds = LongArray(bpeIds.size + 2)
        inputIds[0] = srcLangId
        for (i in bpeIds.indices) inputIds[i + 1] = bpeIds[i].toLong() + FAIRSEQ_OFFSET
        inputIds[inputIds.size - 1] = EOS_TOKEN_ID

        val attentionMask = LongArray(inputIds.size) { 1L }
        val inputShape = longArrayOf(1L, inputIds.size.toLong())

        Log.d(TAG, "Input ${inputIds.size} tokens: [${inputIds.take(8).joinToString()}...]")
        val perfTokenize = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        // Long-lived resources — cleaned up in the finally block below.
        var encoderResult: OrtSession.Result? = null
        var decAttMask: OnnxTensor? = null

        try {
            // ── 2. Encode ───────────────────────────────────────────────
            val inputIdsTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(inputIds), inputShape
            )
            val attMaskTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(attentionMask), inputShape
            )
            try {
                encoderResult = encoder.run(
                    mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attMaskTensor,
                    )
                )
            } finally {
                // Encoder has read these — safe to free immediately
                inputIdsTensor.close()
                attMaskTensor.close()
            }

            // encoderHidden is owned by encoderResult — stays valid until
            // encoderResult is closed in the outer finally.
            val encoderHidden = encoderResult!!
                .get("last_hidden_state")
                .orElseGet { encoderResult!!.get(0) } as OnnxTensor

            // Separate tensor for the decoder's encoder_attention_mask
            // (we cannot reuse attMaskTensor — it was just closed).
            decAttMask = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(attentionMask), inputShape
            )
            val perfEncode = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        // ── 3. Auto-regressive generation — KV cache enabled ───────────────
        //
        // Step 0 uses decoder_model (no past). Subsequent steps use
        // decoder_with_past_model with cached KV tensors.
        //
        // Generation sequence:
        //   [BOS] → forced tgt_lang → free generation → EOS
        //
        // At step 0:  input_ids = [BOS, tgt_lang] → take argmax of last pos
        // At step k:  input_ids = [t_k] + past_kv → take argmax
        val generatedIds = mutableListOf(tgtTokenId)  // forced first output token
        val decInput = mutableListOf(DECODER_START_TOKEN_ID, tgtTokenId)

        val needsHidden = decoderWP.inputInfo.containsKey("encoder_hidden_states")
        val needsMask = decoderWP.inputInfo.containsKey("encoder_attention_mask")

        // ── KV state bookkeeping ────────────────────────────────────────
        // The decoder graph emits BOTH self-attention KVs (`present.N.decoder.*`)
        // AND cross-attention encoder KVs (`present.N.encoder.*`) at step 0.
        // The decoder_with_past graph emits ONLY `present.N.decoder.*` — because
        // encoder KVs are INVARIANT across decoder steps (encoder hidden states
        // don't change), so the graph doesn't bother re-computing them.
        //
        // This means: if we naively forward the *previous* step's outputs, then
        // from step 2 onwards there are NO encoder KVs in the chain → decoderWP
        // throws "Missing Input: past_key_values.0.encoder.key" → silent fallback
        // to no-KV mode (5× slower, and broken on some INT8 exports).
        //
        // Fix: extract encoder KVs ONCE from step 0's result, persist them in a
        // map, and re-inject the same tensors into `inputs` on every subsequent
        // step. The tensors remain owned by `step0Result`, which we must keep
        // alive for the entire loop.
        var step0Result: OrtSession.Result? = null
        val encoderPastKVs = mutableMapOf<String, OnnxTensor>()
        var prevDecoderWPResult: OrtSession.Result? = null

        val perfFirstDec = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        try {
            for (step in 0 until MAX_LENGTH) {
                val lastToken = generatedIds.last()
                if (lastToken == EOS_TOKEN_ID) break

                val decInputArr = if (step == 0) {
                    decInput.toLongArray()
                } else {
                    longArrayOf(lastToken)
                }
                val decInputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(decInputArr),
                    longArrayOf(1L, decInputArr.size.toLong()),
                )

                val inputs = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to decInputTensor
                )
                if (step == 0) {
                    // decoder_model always expects encoder_hidden_states + mask
                    inputs["encoder_hidden_states"] = encoderHidden
                    inputs["encoder_attention_mask"] = decAttMask!!
                } else {
                    if (needsHidden) {
                        inputs["encoder_hidden_states"] = encoderHidden
                    }
                    if (needsMask) {
                        inputs["encoder_attention_mask"] = decAttMask!!
                    }
                    // (1) Encoder cross-attention KVs — identical every step,
                    //     persisted from step 0's decoder output.
                    for ((name, tensor) in encoderPastKVs) {
                        inputs[name] = tensor
                    }
                    // (2) Decoder self-attention KVs — from the most recent result.
                    if (step == 1) {
                        // At step 1 the previous producer is `decoder` (step 0).
                        // Its result contains BOTH encoder + decoder KVs; we only
                        // want the decoder ones here (encoder ones already added
                        // above from `encoderPastKVs`, which are the same tensors).
                        addPastKVInputs(
                            result = step0Result!!,
                            producerSession = decoder,
                            inputs = inputs,
                            onlyDecoder = true,
                            logOnce = true,
                        )
                    } else {
                        // Step ≥ 2: decoderWP only emits `present.N.decoder.*`
                        // outputs, so no filter is needed.
                        addPastKVInputs(
                            result = prevDecoderWPResult!!,
                            producerSession = decoderWP,
                            inputs = inputs,
                            onlyDecoder = false,
                            logOnce = false,
                        )
                    }
                }

                val stepResult: OrtSession.Result
                try {
                    stepResult = if (step == 0) {
                        decoder.run(inputs)
                    } else {
                        decoderWP.run(inputs)
                    }
                } finally {
                    decInputTensor.close()
                }

                // Update KV state AFTER a successful run.
                if (step == 0) {
                    step0Result = stepResult
                    // Extract encoder KVs once; they remain valid for the entire
                    // loop because `step0Result` is kept alive in the outer finally.
                    extractEncoderPastKVs(stepResult, decoder, encoderPastKVs)
                    if (ENABLE_PERF_LOGS) {
                        Log.d(TAG, "Persisted ${encoderPastKVs.size} encoder KV tensors " +
                            "from step 0 (${decoder.outputInfo.size} outputs total).")
                    }
                } else {
                    // Release the previous decoderWP result — its decoder KVs
                    // have been consumed by the run we just completed.
                    // IMPORTANT: never close step0Result here — the encoder KVs
                    // we re-inject every step point into its native memory.
                    prevDecoderWPResult?.close()
                    prevDecoderWPResult = stepResult
                }

                // ── Argmax of the last position ──────────────────────────
                val logits = stepResult
                    .get("logits")
                    .orElseGet { stepResult.get(0) } as OnnxTensor
                val newToken = argmaxLastToken(logits)

                generatedIds.add(newToken)
                if (step == 0) {
                    decInput.add(newToken)   // only needed for step 0
                }
            }
        } finally {
            prevDecoderWPResult?.let { runCatching { it.close() } }
            step0Result?.let { runCatching { it.close() } }
        }

        val perfLoop = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L
        val decodeSteps = generatedIds.size - 1  // -1 for forced tgt_lang token

            // ── 4. Detokenize ───────────────────────────────────────────────
            // Strip the forced target-language prefix; stop before EOS; undo
            // FAIRSEQ_OFFSET on content tokens (lang/special tokens bypass it).
            val outputIds = generatedIds
                .dropWhile { it == tgtTokenId }   // drop forced lang token
                .takeWhile { it != EOS_TOKEN_ID }  // stop at EOS
                .filter { !isNonOffsetId(it) }     // drop any stray special/lang ids
                .map { (it - FAIRSEQ_OFFSET).toInt() }
                .toIntArray()

            val result = sp.decodeIds(outputIds)
            Log.d(TAG, "Generated ${generatedIds.size} tokens → \"$result\"")

            if (ENABLE_PERF_LOGS) {
                val perfEnd = SystemClock.elapsedRealtime()
                val loopMs = perfLoop - perfFirstDec
                val avgStepMs = if (decodeSteps > 0)
                    "%.1f".format(loopMs.toFloat() / decodeSteps) else "N/A"
                Log.i(TAG, "PERF | nllb_translate: total=${perfEnd - perfT0}ms " +
                    "[tokenize=${perfTokenize - perfT0}ms, " +
                    "encode=${perfEncode - perfTokenize}ms, " +
                    "decode_loop=${loopMs}ms ($decodeSteps steps, avg=${avgStepMs}ms/step), " +
                    "detokenize=${perfEnd - perfLoop}ms] " +
                "input=${inputIds.size}tok → output=${generatedIds.size}tok (KV-cache mode)")
            }

            return result

        } finally {
            decAttMask?.let { runCatching { it.close() } }
            encoderResult?.let { runCatching { it.close() } }
        }
    }

    private fun runSeq2SeqGreedyNoKv(
        sp: SentencePieceJni,
        encoder: OrtSession,
        decoder: OrtSession,
        text: String,
        srcLangId: Long,
        tgtTokenId: Long,
    ): String {
        val perfT0 = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        // ── 1. Tokenize ────────────────────────────────────────────────
        // Apply FAIRSEQ_OFFSET to every SP id (see companion-object comment).
        val bpeIds = sp.encodeAsIds(text)
        val inputIds = LongArray(bpeIds.size + 2)
        inputIds[0] = srcLangId
        for (i in bpeIds.indices) inputIds[i + 1] = bpeIds[i].toLong() + FAIRSEQ_OFFSET
        inputIds[inputIds.size - 1] = EOS_TOKEN_ID

        val attentionMask = LongArray(inputIds.size) { 1L }
        val inputShape = longArrayOf(1L, inputIds.size.toLong())

        Log.d(TAG, "Input ${inputIds.size} tokens: [${inputIds.take(8).joinToString()}...]")
        val perfTokenize = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

        var encoderResult: OrtSession.Result? = null
        var decAttMask: OnnxTensor? = null

        try {
            // ── 2. Encode ───────────────────────────────────────────────
            val inputIdsTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(inputIds), inputShape
            )
            val attMaskTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(attentionMask), inputShape
            )
            try {
                encoderResult = encoder.run(
                    mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attMaskTensor,
                    )
                )
            } finally {
                inputIdsTensor.close()
                attMaskTensor.close()
            }

            val encoderHidden = encoderResult!!
                .get("last_hidden_state")
                .orElseGet { encoderResult!!.get(0) } as OnnxTensor

            decAttMask = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(attentionMask), inputShape
            )
            val perfEncode = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

            // ── 3. Auto-regressive generation — no KV cache ─────────────
            val generatedIds = mutableListOf(tgtTokenId)
            val decInput = mutableListOf(DECODER_START_TOKEN_ID, tgtTokenId)

            val perfFirstDec = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L

            for (step in 0 until MAX_LENGTH) {
                val lastToken = generatedIds.last()
                if (lastToken == EOS_TOKEN_ID) break

                val decInputArr = decInput.toLongArray()
                val decInputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(decInputArr),
                    longArrayOf(1L, decInputArr.size.toLong()),
                )

                val stepResult: OrtSession.Result
                try {
                    stepResult = decoder.run(
                        mapOf(
                            "input_ids"              to decInputTensor,
                            "encoder_hidden_states"  to encoderHidden,
                            "encoder_attention_mask" to decAttMask!!,
                        )
                    )
                } finally {
                    decInputTensor.close()
                }

                val logits = stepResult
                    .get("logits")
                    .orElseGet { stepResult.get(0) } as OnnxTensor
                val newToken = argmaxLastToken(logits)
                stepResult.close()

                generatedIds.add(newToken)
                decInput.add(newToken)
            }

            val perfLoop = if (ENABLE_PERF_LOGS) SystemClock.elapsedRealtime() else 0L
            val decodeSteps = generatedIds.size - 1

            // Undo FAIRSEQ_OFFSET on content tokens (lang/special tokens bypass it).
            val outputIds = generatedIds
                .dropWhile { it == tgtTokenId }
                .takeWhile { it != EOS_TOKEN_ID }
                .filter { !isNonOffsetId(it) }
                .map { (it - FAIRSEQ_OFFSET).toInt() }
                .toIntArray()

            val result = sp.decodeIds(outputIds)
            Log.d(TAG, "Generated ${generatedIds.size} tokens → \"$result\"")

            if (ENABLE_PERF_LOGS) {
                val perfEnd = SystemClock.elapsedRealtime()
                val loopMs = perfLoop - perfFirstDec
                val avgStepMs = if (decodeSteps > 0)
                    "%.1f".format(loopMs.toFloat() / decodeSteps) else "N/A"
                Log.i(TAG, "PERF | nllb_translate: total=${perfEnd - perfT0}ms " +
                    "[tokenize=${perfTokenize - perfT0}ms, " +
                    "encode=${perfEncode - perfTokenize}ms, " +
                    "decode_loop=${loopMs}ms ($decodeSteps steps, avg=${avgStepMs}ms/step), " +
                    "detokenize=${perfEnd - perfLoop}ms] " +
                    "input=${inputIds.size}tok → output=${generatedIds.size}tok (no-KV-cache mode)")
            }

            return result

        } finally {
            decAttMask?.let { runCatching { it.close() } }
            encoderResult?.let { runCatching { it.close() } }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // KV-cache helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Copy KV-cache tensors from [result] into [inputs], mapping them to the names
     * expected by decoder_with_past_model.onnx.
     *
     * Naming conventions vary between optimum-cli export versions:
     *   • FP32:  decoder outputs `present.N.decoder.key` → needs `past_key_values.N.decoder.key`
     *   • INT8:  decoder may output `past_key_values.N.decoder.key` directly (already correct)
     *   • Both:  decoderWP outputs `present.N.*` → needs `past_key_values.N.*` on next step
     *
     * When [onlyDecoder] is true, `*.encoder.*` entries are skipped — callers that
     * use a separate persisted encoder-KV map use this to avoid double-registration
     * (both sources alias the exact same native tensors, so the map would overwrite
     * itself, but the filter makes intent explicit and the log accurate).
     *
     * Also logs what's being added on the first call (step==1) to help diagnose mismatches.
     *
     * The tensors are NOT deep-copied; they remain owned by [result].
     * The caller MUST keep [result] alive until after the next `session.run()`.
     */
    private fun addPastKVInputs(
        result: OrtSession.Result,
        producerSession: OrtSession,
        inputs: MutableMap<String, OnnxTensor>,
        onlyDecoder: Boolean = false,
        logOnce: Boolean = false,
    ) {
        var addedCount = 0
        for (name in producerSession.outputInfo.keys) {
            // Accept both naming conventions:
            //   present.N.*          → renamed to past_key_values.N.*
            //   past_key_values.N.*  → passed through unchanged
            val pastName = when {
                name.startsWith("present")          -> name.replaceFirst("present", "past_key_values")
                name.startsWith("past_key_values")  -> name
                else                                -> continue
            }
            if (onlyDecoder && pastName.contains(".encoder.")) continue
            val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
            inputs[pastName] = tensor
            addedCount++
        }
        if (logOnce) {
            Log.d(TAG, "addPastKVInputs: added $addedCount tensors from ${producerSession.outputInfo.size} outputs " +
                "(onlyDecoder=$onlyDecoder). producer_outputs=${producerSession.outputInfo.keys.sorted().take(6)}...")
        }
    }

    /**
     * Extract encoder cross-attention KV tensors from [result] into [out], keyed by
     * the `past_key_values.N.encoder.*` names expected by decoder_with_past_model.
     *
     * Encoder cross-attention KVs are INVARIANT across auto-regressive decoder steps
     * (the encoder hidden states don't change, so neither do the K/V projections of
     * them). We extract them once after step 0 and re-inject the same tensors on
     * every subsequent step. This is the fix for the "Missing Input:
     * past_key_values.0.encoder.key" error that previously triggered a silent
     * fallback to no-KV mode.
     *
     * The tensors are NOT deep-copied; they remain owned by [result]. The caller
     * MUST keep [result] alive for the entire generation loop.
     */
    private fun extractEncoderPastKVs(
        result: OrtSession.Result,
        producerSession: OrtSession,
        out: MutableMap<String, OnnxTensor>,
    ) {
        out.clear()
        for (name in producerSession.outputInfo.keys) {
            val pastName = when {
                name.startsWith("present")         -> name.replaceFirst("present", "past_key_values")
                name.startsWith("past_key_values") -> name
                else                               -> continue
            }
            if (!pastName.contains(".encoder.")) continue
            val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
            out[pastName] = tensor
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Argmax
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the token ID with the highest logit at the **last** sequence
     * position.  Logits shape: `[batch=1, seq_len, vocab_size]`.
     */
    private fun argmaxLastToken(logitsTensor: OnnxTensor): Long {
        @Suppress("UNCHECKED_CAST")
        val logits3d = logitsTensor.value as Array<Array<FloatArray>>
        val lastPos = logits3d[0].last()

        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in lastPos.indices) {
            if (lastPos[i] > maxVal) {
                maxVal = lastPos[i]
                maxIdx = i
            }
        }
        return maxIdx.toLong()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fallback language detection from Unicode script
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Best-effort language detection based on Unicode script analysis.
     * Used as a fallback when the source language is "auto" and Whisper's
     * neural detection was unavailable. Falls back to "eng_Latn".
     */
    private fun detectLanguageFromText(text: String): String {
        var cjk = 0; var hiraganaKatakana = 0; var hangul = 0
        var arabic = 0; var cyrillic = 0; var devanagari = 0
        var thai = 0; var total = 0

        for (ch in text) {
            if (ch.isWhitespace() || ch.code < 0x20) continue
            total++
            when {
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> hiraganaKatakana++
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> cjk++
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> hangul++
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' -> arabic++
                ch in '\u0400'..'\u04FF'                              -> cyrillic++
                ch in '\u0900'..'\u097F'                              -> devanagari++
                ch in '\u0E00'..'\u0E7F'                              -> thai++
            }
        }

        if (total == 0) return "eng_Latn"

        val detected = when {
            hiraganaKatakana > 0           -> "jpn_Jpan"
            hangul > 0                     -> "kor_Hang"
            cjk > total * 0.1             -> "zho_Hans"
            arabic > total * 0.3          -> "arb_Arab"
            cyrillic > total * 0.3        -> "rus_Cyrl"
            devanagari > total * 0.3      -> "hin_Deva"
            thai > total * 0.3            -> "tha_Thai"
            else                          -> "eng_Latn"
        }
        Log.d(TAG, "Script-based language detection: $detected")
        return detected
    }

    // ──────────────────────────────────────────────────────────────────────
    // NLLB language tag mapping
    // ──────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────
    // NLLB language tag → token ID
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Looks up the NLLB token ID for a language tag such as "eng_Latn".
     *
     * NLLB language tags are "added special tokens" at IDs 256001-256202, ABOVE
     * the 256000-token base SentencePiece vocabulary. The raw SentencePiece API
     * cannot find them via pieceToId() (returns 0 = unk). We use the exact IDs
     * extracted from the tokenizer.json that ships with the NLLB-200-distilled-600M
     * HuggingFace export.
     *
     * @return the token ID, or null if the tag is not in the map.
     */
    private fun nllbLangId(tag: String): Long? = langTokenIds[tag]

    /**
     * Convert a Whisper BCP-47 / ISO 639-1 code to the NLLB "lang_Script" tag.
     *
     * Whisper emits 2-letter ISO 639-1 codes ("en", "zh", "cy"…). NLLB uses
     * 3-letter ISO 639-3 + script suffixes ("eng_Latn", "cym_Latn"…). This
     * map is exhaustive for all 99 languages Whisper can detect.
     *
     * Falls back to "eng_Latn" for unrecognised codes (rather than the
     * previous unsafe \"${bcp47}_Latn\" which produced invalid NLLB tags like
     * \"cy_Latn\" or \"af_Latn\" that are absent from the vocab).
     */
    private fun toNllbTag(bcp47: String): String = when (bcp47.trim().lowercase()) {
        // Common languages
        "en"               -> "eng_Latn"
        "zh", "zh-cn"      -> "zho_Hans"
        "zh-tw"            -> "zho_Hant"
        "ja"               -> "jpn_Jpan"
        "ko"               -> "kor_Hang"
        "es"               -> "spa_Latn"
        "fr"               -> "fra_Latn"
        "de"               -> "deu_Latn"
        "ar"               -> "arb_Arab"
        "pt"               -> "por_Latn"
        "ru"               -> "rus_Cyrl"
        "hi"               -> "hin_Deva"
        "it"               -> "ita_Latn"
        "nl"               -> "nld_Latn"
        "pl"               -> "pol_Latn"
        "tr"               -> "tur_Latn"
        "vi"               -> "vie_Latn"
        "th"               -> "tha_Thai"
        "id"               -> "ind_Latn"
        "ms"               -> "zsm_Latn"
        "tl"               -> "tgl_Latn"
        // Additional Whisper-supported languages (all 99)
        "af"               -> "afr_Latn"
        "sq"               -> "als_Latn"
        "am"               -> "amh_Ethi"
        "hy"               -> "hye_Armn"
        "as"               -> "asm_Beng"
        "az"               -> "azj_Latn"
        "ba"               -> "bak_Cyrl"
        "eu"               -> "eus_Latn"
        "be"               -> "bel_Cyrl"
        "bn"               -> "ben_Beng"
        "bs"               -> "bos_Latn"
        "br"               -> "bre_Latn"
        "bg"               -> "bul_Cyrl"
        "ca"               -> "cat_Latn"
        "hr"               -> "hrv_Latn"
        "cs"               -> "ces_Latn"
        "da"               -> "dan_Latn"
        "et"               -> "est_Latn"
        "fo"               -> "fao_Latn"
        "fi"               -> "fin_Latn"
        "gl"               -> "glg_Latn"
        "ka"               -> "kat_Geor"
        "el"               -> "ell_Grek"
        "gu"               -> "guj_Gujr"
        "ht"               -> "hat_Latn"
        "ha"               -> "hau_Latn"
        "he", "iw"         -> "heb_Hebr"
        "is"               -> "isl_Latn"
        "kn"               -> "kan_Knda"
        "kk"               -> "kaz_Cyrl"
        "km"               -> "khm_Khmr"
        "ky"               -> "kir_Cyrl"
        "lo"               -> "lao_Laoo"
        "lv"               -> "lvs_Latn"
        "lt"               -> "lit_Latn"
        "lb"               -> "ltz_Latn"
        "mk"               -> "mkd_Cyrl"
        "mg"               -> "plt_Latn"
        "ml"               -> "mal_Mlym"
        "mt"               -> "mlt_Latn"
        "mi"               -> "mri_Latn"
        "mr"               -> "mar_Deva"
        "mn"               -> "khk_Cyrl"
        "my"               -> "mya_Mymr"
        "ne"               -> "npi_Deva"
        "nb", "no"         -> "nob_Latn"
        "nn"               -> "nno_Latn"
        "oc"               -> "oci_Latn"
        "ps"               -> "pbt_Arab"
        "fa"               -> "pes_Arab"
        "pa"               -> "pan_Guru"
        "ro"               -> "ron_Latn"
        "sa"               -> "san_Deva"
        "sr"               -> "srp_Cyrl"
        "sn"               -> "sna_Latn"
        "sd"               -> "snd_Arab"
        "si"               -> "sin_Sinh"
        "sk"               -> "slk_Latn"
        "sl"               -> "slv_Latn"
        "so"               -> "som_Latn"
        "su"               -> "sun_Latn"
        "sw"               -> "swh_Latn"
        "sv"               -> "swe_Latn"
        "tg"               -> "tgk_Cyrl"
        "ta"               -> "tam_Taml"
        "tt"               -> "tat_Cyrl"
        "te"               -> "tel_Telu"
        "tk"               -> "tuk_Latn"
        "uk"               -> "ukr_Cyrl"
        "ur"               -> "urd_Arab"
        "uz"               -> "uzn_Latn"
        "cy"               -> "cym_Latn"
        "fy"               -> "fry_Latn"
        "xh"               -> "xho_Latn"
        "yi"               -> "ydd_Hebr"
        "yo"               -> "yor_Latn"
        "zu"               -> "zul_Latn"
        // Full NLLB tags passed through directly (already correct format)
        else               -> if (bcp47.contains('_')) bcp47 else {
            Log.w(TAG, "toNllbTag: unknown Whisper code '$bcp47', defaulting to eng_Latn")
            "eng_Latn"
        }
    }

    companion object {
        private const val TAG = "LocalNllbEngine"
        private const val MAX_LENGTH = 128
        private const val ENABLE_KV_CACHE = true

        // ── Performance tuning ──────────────────────────────────────────
        /** Intra-op parallelism for ONNX Runtime CPU EP.
         *  Benchmark with 2 / 4 / 6 and compare PERF | logs. */
        private const val NLLB_INTRA_OP_THREADS = 4
        /** Enable detailed per-phase timing logs (logcat prefix: PERF |). */
        private const val ENABLE_PERF_LOGS = true

        // ── NNAPI config (P2: encoder-only NPU acceleration) ────────────
        /** NPU for encoder (self-attention + FFN — mostly NNAPI-compatible).
         *  Disabled by default because some vendor stacks (e.g. Samsung/Qualcomm)
         *  try to load vendor NNAPI libs like libpenguin.so and spam errors. */
        private const val ENABLE_NNAPI_ENCODER = false
        /** Keep decoder sessions on CPU — NNAPI cannot handle the Loop op
         *  in auto-regressive decoding, and GELU's Erf op also falls back
         *  to CPU causing costly NPU↔CPU context switches per layer. */
        private const val ENABLE_NNAPI_DECODER = false
        private const val NNAPI_USE_FP16 = true
        private const val NNAPI_DISABLE_CPU = false

        // ── Model directories ───────────────────────────────────────────
        // INT8 dynamic quantization gives ~2× speedup with minimal quality loss.
        //
        // How to produce INT8 models:
        //   pip install optimum[onnxruntime]
        //   optimum-cli onnxruntime quantize \
        //       --onnx_model nllb_onnx_output/ \
        //       -o nllb_int8/ \
        //       --per_channel \
        //       --reduce_range
        //
        // Then push to device:
        //   adb push nllb_int8/ /sdcard/Android/data/com.liveTranslation/files/nllb_int8/
        /** INT8 dynamic-quantized model directory. Checked first. */
        private const val INT8_MODEL_DIR = "nllb_int8"
        /** FP32 / original ONNX export directory. */
        private const val FP32_MODEL_DIR = "nllb_onnx_output"
        /** Bundled tokenizer.json path inside `assets/`. Kept separate from
         *  `$FP32_MODEL_DIR/tokenizer.json` because the gradle config excludes
         *  the whole nllb_onnx_output/ tree from APK packaging. */
        private const val BUNDLED_TOKENIZER_ASSET = "nllb_tokenizer.json"

        // NLLB special token IDs (from the SentencePiece vocab)
        private const val EOS_TOKEN_ID = 2L              // </s>
        private const val DECODER_START_TOKEN_ID = 2L    // same as EOS for NLLB

        // ── ⚠️ fairseq offset (CRITICAL — see MEMORY.md "NLLB token offset bug") ────
        // NLLB was trained in fairseq, where SentencePiece content tokens are
        // shifted by +1 to make room for the four reserved special tokens
        // (<s>=0, <pad>=1, </s>=2, <unk>=3). HuggingFace's NllbTokenizer applies
        // this same +1 offset internally; the .onnx model expects HF-style ids.
        //
        // Without the offset, every BPE token is interpreted as its NEIGHBOUR in
        // the vocab → encoder hidden states are garbage → decoder emits random
        // tokens scattered across the 256K multilingual vocabulary (looks
        // identical to "INT8 quantization broken" but happens on FP32 too).
        //
        // Verified by comparing AutoTokenizer.from_pretrained(...) output:
        //   HF =[256079, 248059, 134531, 253935, 2]
        //   raw=[256079, 248058, 134530, 253934, 2]   ← every content tok is -1
        //
        // FIX: add FAIRSEQ_OFFSET to every SentencePiece-encoded id before
        //      feeding the model, and subtract it from every model-emitted id
        //      before decoding (skipping reserved + lang tokens).
        private const val FAIRSEQ_OFFSET = 1L

        // First NLLB language-code added token. SentencePiece vocab is 256000
        // pieces (ids 0..255999); after +1 offset the highest legitimate
        // content token in model-space is 256000. Lang codes ("ace_Arab" =
        // 256001, …) sit ABOVE that. So the offset boundary must be 256001,
        // NOT 256000 — otherwise model id 256000 (a valid content token) is
        // mistakenly treated as a special and dropped from decoder output.
        private const val FIRST_LANG_TOKEN_ID = 256001L

        // IDs for which the model id is NOT a (SentencePiece id + offset):
        //   {0,1,2,3} = reserved fairseq tokens, 256001+ = NLLB lang codes.
        // These must bypass the offset on both encode and decode paths.
        private fun isNonOffsetId(id: Long): Boolean =
            id < 4L || id >= FIRST_LANG_TOKEN_ID

        // ── NLLB language tag → added-token ID ─────────────────────────
        // Sourced at runtime from tokenizer.json's `added_tokens` array.
        // See `loadLangTokenIds()`. Hand-typed maps are forbidden — every
        // historical attempt drifted (75 wrong IDs across the previous
        // version, including zho_Hant pointing at the Zulu token).
    }
}
