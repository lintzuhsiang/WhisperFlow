#!/usr/bin/env bash
# tail_logs.sh — stream the WhisperFlow Android logs with system noise filtered out.
#
# Usage:
#   ./tail_logs.sh              # follow live
#   ./tail_logs.sh -d           # since most recent app launch ("dump")
#   ./tail_logs.sh > out.log    # capture to file

set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not in PATH. Install platform-tools." >&2
  exit 1
fi

if [[ -z "$(adb devices | sed -n '2p' | awk '{print $1}')" ]]; then
  echo "No Android device connected." >&2
  exit 1
fi

# Tags we care about (paired with priority floor: V=verbose, D=debug, I=info,
# W=warning, E=error). The wildcard *:S silences everything not listed.
TAGS=(
  "WhisperFlow"
  "WhisperCpp"
  "WhisperCppEngine"
  "OpenAITranscription"
  "GptTranslationEngine"
  "VideoTranslationActivity"
  "DualTrackPlayer"
  "HiddenTrackPlayer"
  "WhisperAudioProcessor"
  "SubtitleStore"
  "LocalNllbEngine"
  "AudioCaptureService"
  "PERF"
  "BENCH"
  # Always-on: system crashes
  "AndroidRuntime"
  "DEBUG"
  "libc"
)

FILTER=""
for tag in "${TAGS[@]}"; do
  FILTER+="$tag:V "
done
FILTER+="*:S"

if [[ "${1:-}" == "-d" ]]; then
  # Dump mode — print last buffer once, no follow.
  exec adb logcat -d $FILTER
else
  # Clear so we only see fresh stuff, then follow.
  adb logcat -c 2>/dev/null || true
  exec adb logcat $FILTER
fi
