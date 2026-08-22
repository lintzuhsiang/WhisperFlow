#!/usr/bin/env python3
"""
Desktop test for the freshly-quantized NLLB INT8 models.

The whole point: Mac can run the same .onnx files as the phone (ONNX is
platform-agnostic; ORT produces bit-identical numerical results across
ARM/x86 modulo SIMD speed). So we don't have to push 2-3 GB to the device
on every iteration just to find out the model still outputs gibberish —
we test in 5 seconds locally.

If "ありがとうございました。" → "Thank you very much." here, the same
.onnx files will translate correctly on the phone.

This script faithfully mirrors the Kotlin LocalNllbEngine.kt logic:
  • Same input layout: [src_lang_id, ...bpe..., EOS]
  • Same KV-cache management: extract encoder past KVs once from step 0,
    persist them for the whole loop; per-step decoder past KVs from the
    previous result.
  • Same greedy argmax decoding.
  • Same EOS / forced-target-language handling.

Usage:
    pip install "onnxruntime>=1.16" sentencepiece numpy
    python3 test_nllb_quantized.py [path/to/nllb_int8_v3]

If no path given, defaults to ./nllb_int8_v3/.
"""

import sys
import time
from pathlib import Path

np = None

# ── Paths ─────────────────────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parent
DEFAULT_MODEL_DIR = PROJECT_ROOT / "nllb_int8_v3"
SP_MODEL = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "sentencepiece.bpe.model"

# ── NLLB tokens ───────────────────────────────────────────────────────────
EOS_TOKEN_ID = 2
DECODER_START_TOKEN_ID = 2  # NLLB uses EOS as decoder start

# ⚠️ fairseq offset — see MEMORY.md "Critical NLLB Bug"
# HuggingFace NllbTokenizer adds +1 to every SentencePiece content token id
# before feeding the model. The .onnx model expects HF-style ids. Without this
# offset, every BPE token is off by one and the model outputs multi-script
# gibberish (looks identical to "INT8 broken" but happens on FP32 too).
FAIRSEQ_OFFSET = 1
# First NLLB language-code added token. SentencePiece vocab is 256000 pieces
# (ids 0..255999); after +1 offset the highest legitimate content token in
# model-space is 256000. Lang codes start at 256001, NOT 256000 — so the
# offset boundary must be 256001, otherwise model id 256000 (a valid content
# token) is mistakenly skipped during decode.
FIRST_LANG_TOKEN_ID = 256001
# These ids bypass the offset (model id == raw value, no SP mapping):
#   0 <s>, 1 <pad>, 2 </s>, 3 <unk>, and all language tokens (256001+).
NON_OFFSET_IDS = {0, 1, 2, 3}

# ── FLORES-200 lang tag → token ID (extracted from tokenizer.json) ────────
# Subset; mirrors what's in LocalNllbEngine.kt.
LANG_TOKEN_IDS = {
    "eng_Latn": 256047,
    "jpn_Jpan": 256079,
    "zho_Hans": 256201,
    "zho_Hant": 256202,
    "kor_Hang": 256098,
    "fra_Latn": 256057,
    "spa_Latn": 256161,
    "deu_Latn": 256042,
    "rus_Cyrl": 256147,
    "ara_Arab": 256011,  # arb_Arab
}

# ── Test cases ────────────────────────────────────────────────────────────
# Each tuple: (source language, target language, text, expected_keywords)
# expected_keywords = words that SHOULD appear in a correct translation.
# We use "any of these" matching to allow paraphrase variation.
TEST_CASES = [
    ("jpn_Jpan", "eng_Latn", "ありがとうございました。",
        ["thank", "thanks"]),
    ("jpn_Jpan", "eng_Latn", "今日は良い天気ですね。",
        ["today", "weather", "nice", "good"]),
    ("jpn_Jpan", "eng_Latn", "12時前に寝ました。",
        ["12", "bed", "slept", "before", "sleep"]),
    ("zho_Hans", "eng_Latn", "你好世界",
        ["hello", "world"]),
    ("eng_Latn", "zho_Hans", "Thank you very much.",
        ["谢", "感"]),
    ("eng_Latn", "jpn_Jpan", "Hello, how are you?",
        ["こんにち", "元気", "お元気"]),
]

# Decoding parameters
MAX_LENGTH = 64    # plenty for short test sentences


def check_lm_head_not_quantized(model_dir: Path) -> bool:
    """Warn if the decoder vocabulary projection is still INT8-quantized."""
    def contains(path: Path, needle: bytes) -> bool:
        overlap = max(len(needle) - 1, 0)
        previous = b""
        with path.open("rb") as f:
            while True:
                chunk = f.read(8 * 1024 * 1024)
                if not chunk:
                    return False
                haystack = previous + chunk
                if needle in haystack:
                    return True
                previous = haystack[-overlap:] if overlap else b""

    ok = True
    for name in ("decoder_model.onnx", "decoder_with_past_model.onnx"):
        path = model_dir / name
        quantized = contains(path, b"/lm_head/MatMul_quant")
        fp32 = contains(path, b"/lm_head/MatMul")

        if quantized:
            print(f"  Graph check: ❌ {name} has /lm_head/MatMul_quant")
            ok = False
        elif fp32:
            print(f"  Graph check: ✓ {name} keeps /lm_head/MatMul in FP32")
        else:
            print(f"  Graph check: ⚠ {name} has no recognizable /lm_head/MatMul node")

    return ok


# ──────────────────────────────────────────────────────────────────────────
# Tokenization
# ──────────────────────────────────────────────────────────────────────────

def load_tokenizer():
    try:
        import sentencepiece as spm
    except ImportError:
        sys.exit("ERROR: install sentencepiece — pip install sentencepiece")
    if not SP_MODEL.exists():
        sys.exit(f"ERROR: tokenizer not found at {SP_MODEL}")
    sp = spm.SentencePieceProcessor()
    sp.load(str(SP_MODEL))
    return sp


def encode_for_nllb(sp, text: str, src_lang: str):
    """[src_lang_id, ...bpe+1..., EOS] — fairseq-offset HF-compatible layout.

    Each SentencePiece content token id is shifted by FAIRSEQ_OFFSET so the
    model receives HuggingFace-style ids (matches AutoTokenizer ground truth).
    """
    if src_lang not in LANG_TOKEN_IDS:
        sys.exit(f"Unknown src_lang '{src_lang}'")
    bpe = sp.encode_as_ids(text)
    bpe_offset = [b + FAIRSEQ_OFFSET for b in bpe]
    return np.array(
        [[LANG_TOKEN_IDS[src_lang]] + bpe_offset + [EOS_TOKEN_ID]],
        dtype=np.int64,
    )


# ──────────────────────────────────────────────────────────────────────────
# Greedy decode with KV cache (mirrors LocalNllbEngine.runSeq2SeqGreedyWithKv)
# ──────────────────────────────────────────────────────────────────────────

def split_kv_outputs(output_names, outputs):
    """Returns (encoder_past_dict, decoder_past_dict, logits)."""
    enc, dec, logits = {}, {}, None
    for name, tensor in zip(output_names, outputs):
        if name == "logits":
            logits = tensor
            continue
        # rename present.* → past_key_values.*
        if name.startswith("present"):
            past_name = name.replace("present", "past_key_values", 1)
        elif name.startswith("past_key_values"):
            past_name = name
        else:
            continue
        if ".encoder." in past_name:
            enc[past_name] = tensor
        elif ".decoder." in past_name:
            dec[past_name] = tensor
    return enc, dec, logits


def translate(encoder, decoder, decoder_wp, sp, text, src_lang, tgt_lang):
    """Greedy seq2seq decode mirroring the Android app's Kotlin code."""
    src_lang_id = LANG_TOKEN_IDS[src_lang]
    tgt_lang_id = LANG_TOKEN_IDS[tgt_lang]

    # 1) Encode source
    input_ids = encode_for_nllb(sp, text, src_lang)
    attention_mask = np.ones_like(input_ids)

    enc_inputs = {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
    }
    enc_out = encoder.run(None, enc_inputs)
    enc_names = [o.name for o in encoder.get_outputs()]
    encoder_hidden = enc_out[enc_names.index("last_hidden_state")]

    # 2) Decoder step 0 — needs encoder_hidden + mask, no past
    dec_input_ids = np.array([[DECODER_START_TOKEN_ID, tgt_lang_id]], dtype=np.int64)
    dec0_inputs = {
        "input_ids": dec_input_ids,
        "encoder_hidden_states": encoder_hidden,
        "encoder_attention_mask": attention_mask,
    }
    dec0_out = decoder.run(None, dec0_inputs)
    dec0_names = [o.name for o in decoder.get_outputs()]
    encoder_past, decoder_past, logits = split_kv_outputs(dec0_names, dec0_out)

    # First generated token = argmax of last position
    new_tok = int(np.argmax(logits[0, -1]))
    generated = [tgt_lang_id, new_tok]

    # Pre-resolve which inputs decoderWP wants
    wp_input_names = {i.name for i in decoder_wp.get_inputs()}
    wp_output_names = [o.name for o in decoder_wp.get_outputs()]

    # 3) Auto-regressive loop with decoderWP
    for step in range(1, MAX_LENGTH):
        if generated[-1] == EOS_TOKEN_ID:
            break

        step_inputs = {
            "input_ids": np.array([[generated[-1]]], dtype=np.int64),
        }
        if "encoder_hidden_states" in wp_input_names:
            step_inputs["encoder_hidden_states"] = encoder_hidden
        if "encoder_attention_mask" in wp_input_names:
            step_inputs["encoder_attention_mask"] = attention_mask

        # Inject persisted encoder past KVs (static across all steps)
        step_inputs.update(encoder_past)
        # Inject decoder past KVs from previous step
        step_inputs.update(decoder_past)

        step_out = decoder_wp.run(None, step_inputs)
        _, decoder_past, logits = split_kv_outputs(wp_output_names, step_out)

        new_tok = int(np.argmax(logits[0, -1]))
        generated.append(new_tok)

    # 4) Detokenize: drop forced target-lang prefix, stop at EOS, undo fairseq offset
    # Model output ids are HF-style (offset by +1 vs raw SentencePiece). To feed
    # back into sp.decode_ids() we must subtract FAIRSEQ_OFFSET, but ONLY for
    # content tokens (not <s>/<pad>/</s>/<unk> or lang tokens 256000+).
    output_ids = []
    for tid in generated:
        if tid == tgt_lang_id:
            continue
        if tid == EOS_TOKEN_ID:
            break
        if tid in NON_OFFSET_IDS or tid >= FIRST_LANG_TOKEN_ID:
            # Special tokens — skip them in output (they don't decode to text)
            continue
        output_ids.append(tid - FAIRSEQ_OFFSET)
    return sp.decode_ids(output_ids), generated


# ──────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────

def main(model_dir: Path) -> int:
    print("=" * 72)
    print("NLLB INT8 desktop test — Mac mirror of Android inference")
    print("=" * 72)
    print(f"  Models : {model_dir}")
    print()

    # Sanity check model files exist
    for name in ("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"):
        p = model_dir / name
        if not p.exists():
            sys.exit(f"ERROR: missing {p}")

    lm_head_ok = check_lm_head_not_quantized(model_dir)
    if not lm_head_ok:
        print("  This model set is expected to produce multi-script gibberish.")
        print("  Re-run quantize_nllb_int8.py; v3 excludes /lm_head/MatMul.\n")
        return 2

    try:
        import numpy as numpy_module
        import onnxruntime as ort
    except ImportError:
        sys.exit("ERROR: install dependencies — pip install 'onnxruntime>=1.16' sentencepiece numpy")

    global np
    np = numpy_module

    print(f"  ORT    : {ort.__version__}")
    print(f"  Device : {ort.get_device()}")
    print()

    # Load tokenizer
    print("Loading tokenizer…")
    sp = load_tokenizer()
    print(f"  SentencePiece vocab size: {sp.vocab_size()}\n")

    # Load ONNX sessions
    print("Loading ONNX sessions…")
    so = ort.SessionOptions()
    # Match the Android app's settings as closely as possible
    so.intra_op_num_threads = 4
    so.inter_op_num_threads = 1

    t = time.time()
    encoder = ort.InferenceSession(str(model_dir / "encoder_model.onnx"), so)
    print(f"  encoder_model.onnx              loaded ({time.time() - t:.1f}s)")
    t = time.time()
    decoder = ort.InferenceSession(str(model_dir / "decoder_model.onnx"), so)
    print(f"  decoder_model.onnx              loaded ({time.time() - t:.1f}s)")
    t = time.time()
    decoder_wp = ort.InferenceSession(str(model_dir / "decoder_with_past_model.onnx"), so)
    print(f"  decoder_with_past_model.onnx    loaded ({time.time() - t:.1f}s)")
    print()

    # Run test cases
    print("=" * 72)
    print(f"  {'src':<10}{'tgt':<10}{'time':<8}{'verdict':<10}  text → translation")
    print("=" * 72)

    n_pass = 0
    for src, tgt, text, keywords in TEST_CASES:
        t0 = time.time()
        try:
            translation, gen_ids = translate(encoder, decoder, decoder_wp, sp, text, src, tgt)
            elapsed_ms = (time.time() - t0) * 1000

            # Verdict: pass if any expected keyword appears (case-insensitive)
            tr_lower = translation.lower()
            ok = any(kw.lower() in tr_lower for kw in keywords)
            verdict = "✅ PASS" if ok else "❌ FAIL"
            if ok:
                n_pass += 1

            short_src = src[:9]
            short_tgt = tgt[:9]
            print(f"  {short_src:<10}{short_tgt:<10}{elapsed_ms:>5.0f}ms  {verdict:<10}  "
                  f"{text!r}\n            → {translation!r}")
            print(f"            tokens: {gen_ids[:8]}{'...' if len(gen_ids) > 8 else ''}")
            print()
        except Exception as exc:
            print(f"  {src:<10}{tgt:<10}  ❌ ERROR  {text!r}: {exc}")
            print()

    # Summary
    print("=" * 72)
    print(f"  Results: {n_pass} / {len(TEST_CASES)} passed")
    print("=" * 72)

    if n_pass == len(TEST_CASES):
        print("\n  ✅ All translations look correct! Safe to push to device.")
        return 0
    elif n_pass == 0:
        print("\n  ❌ Every test produced gibberish. Quantization is still broken.")
        print("     Possible next steps:")
        print("       • Confirm the graph check says /lm_head/MatMul stayed FP32")
        print("       • Check the FP32 source by running this script against")
        print("         app/src/main/assets/nllb_onnx_output/")
        print("       • If FP32 passes, exclude more decoder output-adjacent MatMuls")
        print("       • If FP32 fails too, the issue is token layout / generation logic")
        return 2
    else:
        print(f"\n  ⚠ Partial: {len(TEST_CASES) - n_pass} test(s) failed.")
        print("     Likely a quality regression from INT8 (acceptable depending on")
        print("     which language pair). Inspect the output above to decide.")
        return 1


if __name__ == "__main__":
    model_dir = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_MODEL_DIR
    sys.exit(main(model_dir))
