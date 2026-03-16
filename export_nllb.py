"""
export_nllb.py  (v2 — compatible with optimum >= 1.14)
=======================================================
Exports facebook/nllb-200-distilled-600M to ONNX format using the
high-level ORTModelForSeq2SeqLM API.  This bypasses:
  - the broken `optimum-cli` / `main_export` ImportError
  - internal `NllbOnnxConfig` / `get_encoder_decoder_models_for_export`
    APIs that change between optimum versions

Usage (from the `app/` directory, with conda env py311 active):
    python export_nllb.py

Output:
    src/main/assets/nllb_onnx_output/
        encoder_model.onnx
        decoder_model.onnx
        decoder_with_past_model.onnx
        tokenizer_config.json  (+ sentencepiece vocab files)
        ...

Requirements:
    pip install "optimum[exporters,onnxruntime]" transformers sentencepiece protobuf onnxruntime
    (torch is NOT required for this export path)
"""

import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_ID    = "facebook/nllb-200-distilled-600M"
OUTPUT_DIR  = Path(__file__).parent / "src" / "main" / "assets" / "nllb_onnx_output"

# Set to True to quantize the exported ONNX files to INT-8 afterwards.
# This shrinks the files ~4x and is recommended for mobile deployment.
QUANTIZE = False

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
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    section(f"Exporting  {MODEL_ID}")
    info(f"Output dir  : {OUTPUT_DIR.resolve()}")
    info(f"Quantize    : {QUANTIZE}")
    print()

    # ------------------------------------------------------------------
    # Step 1 — Export via ORTModelForSeq2SeqLM(export=True)
    # ------------------------------------------------------------------
    # `export=True` tells optimum to convert the PyTorch model on the fly.
    # This is the officially supported path from optimum 1.14+ and does NOT
    # rely on any internal model_configs classes.
    # ------------------------------------------------------------------
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
    # Step 2 (optional) — Quantize to INT-8 using ORTQuantizer
    # ------------------------------------------------------------------
    if QUANTIZE:
        section("Step 3 / 3 — Quantizing to INT-8")
        try:
            from optimum.onnxruntime import ORTQuantizer
            from optimum.onnxruntime.configuration import AutoQuantizationConfig

            quantized_dir = OUTPUT_DIR / "quantized"
            quantized_dir.mkdir(exist_ok=True)

            onnx_files = list(OUTPUT_DIR.glob("*.onnx"))
            if not onnx_files:
                warn("No .onnx files found to quantize — skipping.")
            else:
                qconfig = AutoQuantizationConfig.arm64(is_static=False, per_channel=False)
                for onnx_file in onnx_files:
                    info(f"  Quantizing {onnx_file.name} …")
                    quantizer = ORTQuantizer.from_pretrained(
                        str(OUTPUT_DIR),
                        file_name=onnx_file.name,
                    )
                    quantizer.quantize(
                        save_dir=str(quantized_dir),
                        quantization_config=qconfig,
                    )
                    orig_mb = onnx_file.stat().st_size / 1024 / 1024
                    q_file  = quantized_dir / onnx_file.name
                    q_mb    = q_file.stat().st_size / 1024 / 1024 if q_file.exists() else 0
                    info(f"    {onnx_file.name}: {orig_mb:.0f} MB → {q_mb:.0f} MB")
        except Exception as exc:
            warn(f"Quantization failed: {exc}")
            warn("Non-quantized files are still usable.")
    else:
        section("Step 3 / 3 — Quantization skipped")
        info("Set  QUANTIZE = True  at the top of this script to enable INT-8 quantization.")

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------
    section("Export complete!")
    onnx_files = sorted(OUTPUT_DIR.rglob("*.onnx"))
    if onnx_files:
        info("ONNX files created:")
        for f in onnx_files:
            size_mb = f.stat().st_size / 1024 / 1024
            rel     = f.relative_to(OUTPUT_DIR)
            print(f"      {rel}  ({size_mb:.1f} MB)")
    else:
        warn("No .onnx files found — something may have gone wrong above.")

    print()
    info(f"All files are in: {OUTPUT_DIR.resolve()}")


if __name__ == "__main__":
    main()
