#!/usr/bin/env bash
# =============================================================================
# prepare_models.sh
# Verifies, downloads (if needed), and copies Whisper GGML models into the
# Android app's assets directory.
#
# Usage: Run from the `app/` directory of your Android project.
#   bash prepare_models.sh
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHISPER_DIR="$(cd "$SCRIPT_DIR/../whisper.cpp" && pwd)"
MODELS_DIR="$WHISPER_DIR/models"
ASSETS_DIR="$SCRIPT_DIR/src/main/assets"
DOWNLOAD_SCRIPT="$MODELS_DIR/download-ggml-model.sh"

MODELS=("tiny.en" "medium")

# Map model tag -> actual filename
declare -A MODEL_FILES
MODEL_FILES["tiny.en"]="ggml-tiny.en.bin"
MODEL_FILES["medium"]="ggml-medium.bin"

# --- Colour helpers ----------------------------------------------------------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Colour

info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# --- Pre-flight checks -------------------------------------------------------
info "=== Whisper Model Preparation Script ==="
info "whisper.cpp directory : $WHISPER_DIR"
info "Models directory      : $MODELS_DIR"
info "Assets destination    : $ASSETS_DIR"
echo ""

if [ ! -d "$WHISPER_DIR" ]; then
    error "whisper.cpp directory not found at: $WHISPER_DIR"
    error "Clone it with: git clone https://github.com/ggml-org/whisper.cpp ../whisper.cpp"
    exit 1
fi

if [ ! -d "$MODELS_DIR" ]; then
    error "Models directory not found at: $MODELS_DIR"
    exit 1
fi

if [ ! -f "$DOWNLOAD_SCRIPT" ]; then
    error "Download script not found at: $DOWNLOAD_SCRIPT"
    exit 1
fi

# Ensure the assets directory exists
mkdir -p "$ASSETS_DIR"
info "Assets directory is ready."
echo ""

# --- Download missing models -------------------------------------------------
for model_tag in "${MODELS[@]}"; do
    filename="${MODEL_FILES[$model_tag]}"
    filepath="$MODELS_DIR/$filename"

    if [ -f "$filepath" ]; then
        size=$(du -sh "$filepath" | cut -f1)
        info "✔  $filename already exists ($size) — skipping download."
    else
        warn "✘  $filename not found. Downloading model '$model_tag'..."
        # The download script must be run from the models directory
        pushd "$MODELS_DIR" > /dev/null
            bash download-ggml-model.sh "$model_tag"
        popd > /dev/null

        # Verify the download succeeded
        if [ ! -f "$filepath" ]; then
            error "Download failed for $filename. Check your internet connection and try again."
            exit 1
        fi
        info "✔  Download complete: $filename"
    fi
done

echo ""

# --- Copy models to assets ---------------------------------------------------
info "Copying models into $ASSETS_DIR ..."
for model_tag in "${MODELS[@]}"; do
    filename="${MODEL_FILES[$model_tag]}"
    src="$MODELS_DIR/$filename"
    dst="$ASSETS_DIR/$filename"

    if [ -f "$dst" ]; then
        warn "  $filename already exists in assets — overwriting."
    fi

    cp "$src" "$dst"

    if [ -f "$dst" ]; then
        size=$(du -sh "$dst" | cut -f1)
        info "  ✔  Copied $filename → assets/ ($size)"
    else
        error "  Copy failed for $filename."
        exit 1
    fi
done

echo ""
info "=== All models prepared successfully! ==="
info "Files in $ASSETS_DIR :"
ls -lh "$ASSETS_DIR"/*.bin 2>/dev/null || warn "No .bin files found (unexpected)."
