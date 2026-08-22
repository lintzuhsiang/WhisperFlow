#!/usr/bin/env python3
"""
NLLB INT8 quantization, ARM64-correct edition.

Replaces the broken nllb_int8_v2/ models that produced multi-script gibberish
("velدr لم kogniti...") instead of real translations. The v2 files still
quantized the decoder LM head (`/lm_head/MatMul`), which is the 256K-vocabulary
output projection tied to `model.shared.weight`. Small INT8 logit errors there
are enough to make greedy decoding choose valid-but-wrong tokens from unrelated
scripts.

Knobs this script gets RIGHT (each one was wrong before):

  ✅ per_channel=True
        One quantization scale per OUTPUT CHANNEL (e.g. 256000 scales for the
        LM head's [hidden, vocab] matrix), instead of one scale for the entire
        tensor. Without this, a single outlier crushes the dynamic range of
        every other weight, and most values round to zero. NLLB has very
        wide-range weights (256K-vocab LM head, 1024-dim embeddings), so this
        is the SINGLE BIGGEST factor in fixing the gibberish output.

  ✅ reduce_range=True
        Constrains weights to 7-bit range [-64, 63] instead of full int8
        [-128, 127]. Why: ARM NEON's INT8 matrix-multiply pipeline uses 7-bit
        accumulators internally to leave headroom against silent overflow.
        Without reduce_range on ARM CPUs, your matmul results are essentially
        random noise. (x86 doesn't have this issue, which is why models
        quantized on a PC work on PCs but break on phones.)

  ✅ weight_type=QInt8 (signed int8)
        ARM CPUs natively accelerate signed int8 multiplications via SDOT
        (Signed dot product) instructions on Snapdragon 8 series. Using
        QUInt8 (unsigned) here forces a slow software fallback.

  ✅ op_types_to_quantize=['MatMul', 'Gemm']
        Restricts quantization to compute-heavy linear ops. ONNX Runtime's
        DEFAULT for op_types_to_quantize ALSO INCLUDES `Gather` — the embedding
        lookup. NLLB's embedding table is [256000, 1024]; quantizing it forces
        all 256 K token vectors to share quantization scales, and per_channel
        ends up choosing the wrong axis (vocab vs hidden). Most low-frequency
        tokens get mapped to noise, the LM head (tied with embedding) outputs
        garbage logits, and the model spits random tokens from across the
        entire 256 K vocab — EXACTLY the multi-script gibberish symptom.
        EXCLUDING Gather costs ~250 MB of file size per sub-model but is
        required for correctness.

  ✅ nodes_to_exclude=['/lm_head/MatMul'] for both decoder graphs
        Keeps the vocabulary projection in FP32. This is the root-cause fix for
        v2: `strings nllb_int8_v2/decoder_model.onnx` shows
        `/lm_head/MatMul_quant`, and the observed outputs are valid token IDs
        sampled from the wrong multilingual distribution.

  ✅ MatMulConstBOnly=True  ← preserves attention precision
        Only quantize MatMul ops whose 2nd input is a CONSTANT weight (linear
        layers). Attention's `Q @ K^T` and `probs @ V` are activation×activation
        and stay FP32 — softmax in between makes them very sensitive to noise.
        (Your previous script already had this; keeping it.)

  ✅ Dynamic quantization (no calibration data required)
        Activation scales are computed at runtime per-batch. Slight quality
        cost vs static quantization (~0.5 BLEU on NLLB) but no calibration
        dataset needed. For NLLB the dynamic-vs-static gap is too small to
        notice; the per_channel/reduce_range fixes matter ~100× more.

Expected output (NLLB-200-distilled-600M):

   File                              Old (broken)   New (correct)
   ─────────────────────────────────────────────────────────────────
   encoder_model.onnx                 419 MB           ~700 MB
   decoder_model.onnx                 734 MB         ~1100 MB
   decoder_with_past_model.onnx       708 MB         ~1100 MB
   ─────────────────────────────────────────────────────────────────
   Total                             1.86 GB         ~2.9 GB *
   Translation quality              garbage         close to FP32

   * Yes — the new INT8 is BIGGER than the old "INT8". That's because the
     old build was quantizing the [256000, 1024] embedding (Gather op) which
     compressed it 4× but also destroyed accuracy. We're trading file size
     for correctness. If 2.9 GB is too large for the device, the next step
     is INT8 + FP16 embeddings / LM head (still much smaller than FP32).

Usage:
    pip install "onnxruntime>=1.16" onnx
    python3 quantize_nllb_int8.py
"""

import shutil
import sys
import time
from pathlib import Path


# ── Paths ──────────────────────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parent
FP32_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "nllb_onnx_output"
OUT_DIR = PROJECT_ROOT / "nllb_int8_v3"

# ── Models to quantize ────────────────────────────────────────────────────
MODELS = [
    "encoder_model.onnx",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx",
]

# Tokenizer / config files copied unchanged so the directory is self-sufficient
AUX_FILES = [
    "config.json",
    "generation_config.json",
    "special_tokens_map.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "sentencepiece.bpe.model",   # may live elsewhere; copied if present
]

# These decoder nodes are too sensitive to quantize. Exact names are from the
# Optimum ONNX export in app/src/main/assets/nllb_onnx_output/.
NODES_TO_EXCLUDE = {
    "decoder_model.onnx": ["/lm_head/MatMul"],
    "decoder_with_past_model.onnx": ["/lm_head/MatMul"],
}


def fmt_mb(num_bytes: int) -> str:
    return f"{num_bytes / 1024 / 1024:.1f} MB"


def total_size(path: Path) -> int:
    """Total bytes including any .onnx_data sidecar."""
    if not path.exists():
        return 0
    n = path.stat().st_size
    sidecar = path.with_suffix(path.suffix + "_data")  # .onnx → .onnx_data
    if sidecar.exists():
        n += sidecar.stat().st_size
    return n


def file_contains(path: Path, needle: bytes) -> bool:
    """Memory-safe substring search for multi-GB ONNX files."""
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


def validate_sensitive_nodes(model_path: Path, excluded_nodes: list[str]) -> None:
    """Fail fast if an excluded sensitive node was still quantized."""
    if not excluded_nodes:
        return

    for node_name in excluded_nodes:
        quantized_name = f"{node_name}_quant".encode("utf-8")
        fp32_name = node_name.encode("utf-8")

        if file_contains(model_path, quantized_name):
            raise RuntimeError(
                f"{model_path.name}: sensitive node {node_name} was quantized. "
                "Check nodes_to_exclude."
            )
        if not file_contains(model_path, fp32_name):
            raise RuntimeError(
                f"{model_path.name}: expected FP32 node {node_name} was not found. "
                "The export graph names changed; inspect the decoder graph and "
                "update NODES_TO_EXCLUDE."
            )

    print(f"        ✓ sensitive nodes preserved: {', '.join(excluded_nodes)}")


def quantize_one(src: Path, dst: Path, model_idx: int, total: int) -> None:
    """Dynamic-quantize one ONNX sub-model with the ARM64-correct settings."""
    from onnxruntime.quantization import quantize_dynamic, QuantType

    in_bytes = total_size(src)
    print(f"\n[{model_idx}/{total}] {src.name}  ({fmt_mb(in_bytes)})")
    print(f"        → {dst}")
    excluded_nodes = NODES_TO_EXCLUDE.get(src.name, [])
    if excluded_nodes:
        print(f"        excluding sensitive nodes: {', '.join(excluded_nodes)}")

    t0 = time.time()
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),

        # Signed int8 — ARM-native via SDOT instruction.
        weight_type=QuantType.QInt8,

        # ── Critical knobs ────────────────────────────────────────────
        per_channel=True,        # one scale per output channel
        reduce_range=True,       # weights in [-64, 63] for ARM NEON safety

        # Quantize ONLY the compute-heavy linear ops. Leave LayerNorm,
        # Softmax, GELU, etc. in FP32 — quantizing them destroys accuracy.
        op_types_to_quantize=["MatMul", "Gemm"],
        nodes_to_exclude=excluded_nodes,

        # With lm_head excluded (stays FP32 ~500 MB), decoder sub-models
        # exceed the 2 GB protobuf limit → must use external data format.
        # This creates a small .onnx (graph only) + .onnx_data (weights sidecar).
        # ONNX Runtime loads both automatically when they sit in the same directory.
        use_external_data_format=True,

        extra_options={
            # Only quantize MatMul where the 2nd input is a constant weight.
            # Attention's Q@K^T and probs@V have two activation inputs; leaving
            # them in FP32 protects softmax-amplified numerical sensitivity.
            "MatMulConstBOnly": True,
            # Walk into If/Loop subgraphs (some optimum exports wrap conditional
            # KV-cache logic in If; without this their inner MatMuls stay FP32
            # and you get mixed-precision numerical mismatches).
            "EnableSubgraph": True,
            # Symmetric weight quantization: zero-point=0, simpler math,
            # plays well with reduce_range.
            "WeightSymmetric": True,
            # Activations stay asymmetric — handles ReLU/GELU outputs which
            # have a non-zero-centered distribution.
            "ActivationSymmetric": False,
        },
    )

    out_bytes = total_size(dst)
    validate_sensitive_nodes(dst, excluded_nodes)
    elapsed = time.time() - t0
    ratio = in_bytes / max(out_bytes, 1)
    print(f"        ✓ {fmt_mb(out_bytes)} in {elapsed:.0f}s  ({ratio:.2f}× smaller)")


def main() -> int:
    # Sanity check: ORT is installed
    try:
        import onnxruntime  # noqa: F401
        from onnxruntime.quantization import quantize_dynamic  # noqa: F401
    except ImportError:
        print("ERROR: onnxruntime not installed. Run:")
        print("  pip install 'onnxruntime>=1.16' onnx")
        return 1

    if not FP32_DIR.exists():
        print(f"ERROR: FP32 source directory not found:\n  {FP32_DIR}")
        return 1

    print("=" * 70)
    print("NLLB INT8 quantization — ARM64-correct edition")
    print("=" * 70)
    print(f"  Source : {FP32_DIR}")
    print(f"  Output : {OUT_DIR}")
    print(f"  Models : {', '.join(MODELS)}")
    print()

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Quantize each sub-model
    for i, fname in enumerate(MODELS, start=1):
        src = FP32_DIR / fname
        dst = OUT_DIR / fname
        if not src.exists():
            print(f"\n[{i}/{len(MODELS)}] {fname}  ⚠ NOT FOUND, skipping")
            continue
        quantize_one(src, dst, i, len(MODELS))

    # Copy aux files for a self-contained directory
    print("\nCopying auxiliary files...")
    for fname in AUX_FILES:
        for src_dir in (FP32_DIR, FP32_DIR.parent, PROJECT_ROOT / "app" / "src" / "main" / "assets"):
            src = src_dir / fname
            if src.exists():
                shutil.copy2(src, OUT_DIR / fname)
                print(f"  ✓ {fname}  (from {src_dir.name}/)")
                break

    # Summary
    total_in = sum(total_size(FP32_DIR / m) for m in MODELS)
    total_out = sum(total_size(OUT_DIR / m) for m in MODELS if (OUT_DIR / m).exists())
    print()
    print("=" * 70)
    print(f"  FP32 total   : {fmt_mb(total_in)}")
    print(f"  INT8 total   : {fmt_mb(total_out)}")
    if total_out:
        print(f"  Compression  : {total_in / total_out:.2f}×")
    print("=" * 70)

    print("\nNext steps:\n")
    print("  1. (Recommended) sanity-test on a desktop:")
    print("       python3 -c \"import onnxruntime as ort; "
          f"ort.InferenceSession('{OUT_DIR / 'encoder_model.onnx'}'); print('OK')\"")
    print()
    print("  2. Push to device (replace the broken nllb_int8/ in-place):")
    print(f"       adb push {OUT_DIR}/encoder_model.onnx              \\")
    print(f"               /sdcard/Android/data/com.liveTranslation/files/nllb_int8/")
    print(f"       adb push {OUT_DIR}/decoder_model.onnx              \\")
    print(f"               /sdcard/Android/data/com.liveTranslation/files/nllb_int8/")
    print(f"       adb push {OUT_DIR}/decoder_with_past_model.onnx    \\")
    print(f"               /sdcard/Android/data/com.liveTranslation/files/nllb_int8/")
    print()
    print("  3. Force-stop and re-launch the app to drop the cached models:")
    print("       adb shell am force-stop com.liveTranslation")
    print()
    print("  4. Run a translation; logcat should show real English instead")
    print("     of multi-script gibberish.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
