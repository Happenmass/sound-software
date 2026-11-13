# Qwen3-ASR on Qualcomm HTP

This optional build targets ARM64 SM8850 hardware with 4 KB pages. It was verified on the HONOR Magic V6 (PNM-AN10). It uses the OnSpeak47 Qwen3-ASR 0.6B model, fine-tuned for Vietnamese, Chinese and English with a pruned vocabulary. The encoder, prefill and token decoder run on HTP. The worker does WAV preparation and token handling on the CPU. It reports a failed segment if HTP execution fails. Hotwords can be used when generating notes.

Build with JDK 17, Python 3.12+ and Android NDK 28.2.13676358:

```sh
./gradlew :app:assembleDebug -PincludeQwenHtp=true
```

Add `-PincludeSpeakerNpu=true` to include the separate speaker accelerator. Add `-PincludeAsrWeights=true` to bundle the pinned Qwen3-ASR weights. The Qwen worker uses its own process and runtime directory, with ONNX Runtime 1.29, the Qualcomm QNN EP 2.7 and QAIRT 2.51. The build verifies its dependencies against `artifacts.json` and includes upstream licenses and notices. Builds with bundled weights prepare the model on first use.

Download the pinned [OnSpeak47 NPU model release](https://github.com/DS-PNQ/OnSpeak47-npu-models/releases/tag/v2.2.0-npu) into the shared cache. For builds without bundled weights, deploy it after installing and opening the app:

```sh
model_dir="$HOME/.cache/codex/models/qwen3-asr-onspeak47-sm8850/v2.2.0-npu"
gh release download v2.2.0-npu -R DS-PNQ/OnSpeak47-npu-models --dir "$model_dir"
python3 tools/qwen-npu-worker/deploy.py --serial SERIAL
```

Select **Qwen3-ASR NPU** in the transcription settings. The app uses the same analysis flow as the CPU profile, with up to eight seconds per model window and one worker per recording. Timestamps are approximate window boundaries. For a hardware check, run `QwenHtpPhoneTest` with the instrumentation argument `runQwenHtp=true` and place public/synthetic fixtures `mixed.wav` and `dialog.wav` under the app's external files directory at `validation/qwen-htp/`.
