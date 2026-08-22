"""
export_nllb.py  (v3 — FP32 export + INT8 quantization)
=======================================================
Exports facebook/nllb-200-distilled-600M to ONNX format, then optionally
quantizes to INT8 dynamic for mobile deployment.

Usage (from the `app/` directory, with conda env py311 active):

    # Full pipeline: export FP32 → quantize INT8
    python export_nllb.py

    # Already have FP32? Quantize only:
    python export_nllb.py --quantize-only

Output:
    nllb_onnx_output/   (FP32, ~6.9 GB)
    nllb_int8/          (INT8, ~1.7 GB)  ← push this to device

Push INT8 to device:
    adb push nllb_int8/ /sdcard/Android/data/com.liveTranslation/files/nllb_int8/

Requirements:
    pip install "optimum[exporters,onnxruntime]" transformers sentencepiece protobuf onnxruntime
"""

import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_ID    = "facebook/nllb-200-distilled-600M"
OUTPUT_DIR  = Path(__file__).parent / "src" / "main" / "assets" / "nllb_onnx_output"

# INT8 output — separate directory, pushed via adb (not bundled in APK)
INT8_OUTPUT_DIR = Path(__file__).parent / "nllb_int8"

# The decoder LM head is the 256K-vocabulary output projection tied to
# model.shared.weight. Quantizing it produces valid but wrong multilingual
# token IDs during greedy decode.
NODES_TO_EXCLUDE = {
    "decoder_model.onnx": ["/lm_head/MatMul"],
    "decoder_with_past_model.onnx": ["/lm_head/MatMul"],
}

# Set to True to quantize the exported ONNX files to INT-8 afterwards.
# This shrinks the files ~4x and is recommended for mobile deployment.
QUANTIZE = True

# Set to True (or pass --quantize-only) to skip FP32 export and only quantize
# existing FP32 models. Useful when you already have nllb_onnx_output/.
SKIP_EXPORT = "--quantize-only" in sys.argv

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
GREEN  = "\033[0;32m"
YELLOW = "\033[1;33m"
RED    = "\033[0;31m"
NC     = "\033[0m"

def info(msg):  print(f"{GREEN}[INFO]{NC}  {msg}")
def warn(msg):  print(f"{YELLOW}[WARN]{NC}  {msg}")
def error(msg): print(f"{RED}[ERROR]{NC} {msg}", file=sys.stderr)

def section(title):
    print(f"\n{'='*60}\n  {title}\n{'='*60}")

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
def check_deps():
    missing = []
    for pkg in ("optimum", "transformers", "onnxruntime"):
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)
    if missing:
        error(f"Missing packages: {', '.join(missing)}")
        error("Install with:")
        error('  pip install "optimum[exporters,onnxruntime]" transformers sentencepiece protobuf onnxruntime')
        sys.exit(1)

check_deps()

# ---------------------------------------------------------------------------
# Imports (after dep check)
# ---------------------------------------------------------------------------
from optimum.onnxruntime import ORTModelForSeq2SeqLM   # the stable high-level API
from transformers import AutoTokenizer

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    section(f"NLLB Export Pipeline  ({MODEL_ID})")
    info(f"FP32 dir    : {OUTPUT_DIR.resolve()}")
    info(f"INT8 dir    : {INT8_OUTPUT_DIR.resolve()}")
    info(f"Quantize    : {QUANTIZE}")
    info(f"Skip export : {SKIP_EXPORT}")
    print()

    # ------------------------------------------------------------------
    # Step 1 — Export FP32 via ORTModelForSeq2SeqLM(export=True)
    # ------------------------------------------------------------------
    if SKIP_EXPORT:
        section("Step 1 / 3 — Skipped (using existing FP32 models)")
        # Verify FP32 models exist
        required = ["encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"]
        for name in required:
            if not (OUTPUT_DIR / name).exists():
                error(f"FP32 model not found: {OUTPUT_DIR / name}")
                error("Run without --quantize-only first to export FP32 models.")
                sys.exit(1)
        info("All FP32 models found.")
    else:
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

        section("Step 1 / 3 — Loading & exporting model to ONNX")
        info("This will download ~2 GB of weights on first run. Please wait…")

        model = ORTModelForSeq2SeqLM.from_pretrained(
            MODEL_ID,
            export=True,          # <-- convert on the fly
            provider="CPUExecutionProvider",
        )

        section("Step 2 / 3 — Saving ONNX files")
        model.save_pretrained(str(OUTPUT_DIR))
        info(f"Model saved to {OUTPUT_DIR.resolve()}")

        # Save tokenizer alongside the ONNX files
        tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
        tokenizer.save_pretrained(str(OUTPUT_DIR))
        info("Tokenizer saved.")

    # ------------------------------------------------------------------
    # Step 3 — Quantize to INT-8 using onnxruntime.quantization
    #
    # Uses quantize_dynamic directly (NOT optimum's ORTQuantizer which
    # has CLI/API compatibility issues across versions).
    # Handles models with external data files (.onnx_data) automatically.
    # ------------------------------------------------------------------
    if QUANTIZE:
        section("Step 3 / 3 — Quantizing to INT-8 (dynamic)")
        from onnxruntime.quantization import quantize_dynamic, QuantType

        INT8_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

        model_names = [
            "encoder_model.onnx",
            "decoder_model.onnx",
            "decoder_with_past_model.onnx",
        ]

        for name in model_names:
            src_path = OUTPUT_DIR / name
            dst_path = INT8_OUTPUT_DIR / name

            if not src_path.exists():
                warn(f"  {name} not found — skipping.")
                continue

            # Show FP32 size (include .onnx_data sidecar if present)
            data_file = OUTPUT_DIR / f"{src_path.stem}.onnx_data"
            fp32_bytes = src_path.stat().st_size
            if data_file.exists():
                fp32_bytes += data_file.stat().st_size
            fp32_gb = fp32_bytes / (1024 ** 3)

            info(f"  Quantizing {name} ({fp32_gb:.2f} GB FP32) …")
            info(f"    This may take several minutes and use significant RAM.")

            try:
                quantize_dynamic(
                    model_input=str(src_path),
                    model_output=str(dst_path),
                    weight_type=QuantType.QInt8,
                    per_channel=True,
                    reduce_range=True,
                    op_types_to_quantize=["MatMul", "Gemm"],
                    nodes_to_exclude=NODES_TO_EXCLUDE.get(name, []),
                    extra_options={"MatMulConstBOnly": True},
                )
                int8_mb = dst_path.stat().st_size / (1024 ** 2)
                info(f"    ✓ {name}: {fp32_gb:.2f} GB → {int8_mb:.0f} MB")
            except Exception as exc:
                error(f"    ✗ Failed to quantize {name}: {exc}")
                warn(f"    You can still use the FP32 version (but it uses more memory).")

        info(f"INT8 models saved to: {INT8_OUTPUT_DIR.resolve()}")
    else:
        section("Step 3 / 3 — Quantization skipped")
        info("Set  QUANTIZE = True  at the top of this script to enable INT-8 quantization.")

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------
    section("Export complete!")

    # Show FP32 files
    fp32_files = sorted(OUTPUT_DIR.glob("*.onnx"))
    if fp32_files:
        info("FP32 models:")
        for f in fp32_files:
            data_file = OUTPUT_DIR / f"{f.stem}.onnx_data"
            total = f.stat().st_size + (data_file.stat().st_size if data_file.exists() else 0)
            print(f"      {f.name}  ({total / (1024**2):.1f} MB)")

    # Show INT8 files
    int8_files = sorted(INT8_OUTPUT_DIR.glob("*.onnx")) if INT8_OUTPUT_DIR.exists() else []
    if int8_files:
        print()
        info("INT8 models (quantized):")
        total_int8 = 0
        for f in int8_files:
            size = f.stat().st_size
            total_int8 += size
            print(f"      {f.name}  ({size / (1024**2):.1f} MB)")
        print(f"\n      Total INT8: {total_int8 / (1024**3):.2f} GB")

    print()
    info("Next step — push INT8 models to device:")
    print(f"      adb push {INT8_OUTPUT_DIR.resolve()}/ "
          f"/sdcard/Android/data/com.liveTranslation/files/nllb_int8/")
    print()
    info("The Android app auto-detects INT8 models and uses them preferentially.")


if __name__ == "__main__":
    main()
