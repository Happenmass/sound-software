#!/usr/bin/env bash
set -euo pipefail
# Copies already verified global-cache models to the connected development phone.
# Run prepare_cache.py first when weights are absent. Never downloads models here.
selection="${1:-sensevoice}"
adb_tool="${ADB:-adb}"
model_cache="${HOME}/.cache/codex/models/sherpa-onnx"
package_name="${SOUND_ANDROID_PACKAGE:-dev.soundsoftware.app}"
[[ "$package_name" =~ ^[a-zA-Z][a-zA-Z0-9_.]*$ ]] || { printf 'Invalid Android package name.\n' >&2; exit 2; }
phone_models="/sdcard/Android/data/$package_name/files/models"
"$adb_tool" shell pm path "$package_name" >/dev/null || { printf 'Install and launch %s first.\n' "$package_name" >&2; exit 1; }
"$adb_tool" shell mkdir -p "$phone_models"
deploy() {
  local source="$model_cache/$1" target="$phone_models/$2"
  [[ -d "$source" ]] || { printf 'Missing global model cache: %s\n' "$source" >&2; exit 1; }
  # Push directory contents so repeated invocation never creates a nested model directory.
  "$adb_tool" shell mkdir -p "$target"
  "$adb_tool" push "$source/." "$target/"
  # Subfolders created by adb are not traversable by the app uid on newer Android storage.
  "$adb_tool" shell "find '$target' -mindepth 1 -type d -exec chmod a+rwx {} +"
}
deploy_diarization() {
  local target="$phone_models/diarization"
  [[ -f "$model_cache/sherpa-onnx-pyannote-segmentation-3-0/model.onnx" && -f "$model_cache/3dspeaker_eres2netv2_zh.onnx" ]] || { printf 'Missing diarization models in %s\n' "$model_cache" >&2; exit 1; }
  "$adb_tool" shell mkdir -p "$target"
  "$adb_tool" push "$model_cache/sherpa-onnx-pyannote-segmentation-3-0/model.onnx" "$target/segmentation.onnx"
  "$adb_tool" push "$model_cache/3dspeaker_eres2netv2_zh.onnx" "$target/embedding.onnx"
}
case "$selection" in
  sensevoice) deploy sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 sensevoice ;;
  qwen) deploy sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 qwen ;;
  diarization) deploy_diarization ;;
  qnn-sm8850) deploy sherpa-onnx-qnn-SM8850-binary-10-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8 sensevoice-qnn-sm8850 ;;
  both) deploy sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 sensevoice
        deploy sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 qwen ;;
  all) deploy sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 sensevoice
       deploy sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 qwen
       deploy_diarization ;;
  *) printf 'Usage: %s [sensevoice|qwen|diarization|qnn-sm8850|both|all]\n' "$0" >&2; exit 2 ;;
esac
printf 'Models deployed; the app checks them on next use. Weights stay outside the APK.\n'
