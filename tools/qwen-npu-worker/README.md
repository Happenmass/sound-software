# Qwen3-ASR on Qualcomm HTP

The current worker targets ARM64 SM8850 hardware with 4 KB pages. It takes up to 384,000 mono 16 kHz samples per call. The encoder, 64-token prefill and single-token decode run on HTP through QAIRT 2.50. Both decoder graphs share weights and a 1,024-slot FP16 KV cache. The full 151,936-token vocabulary and INT8 projection weights come from the pinned Qwen3-ASR 0.6B export.

Build with JDK 17, Python 3.11 or newer, the pinned QAIRT 2.50 SDK and Android NDK 28.2.13676358:

```sh
./gradlew :app:assembleDebug -PincludeQwenHtp=true
```

Add `-PincludeAsrWeights=true` to bundle the verified model. The APK extracts it on first use. Follow the [model guide](../../docs/MODELS.md#transcription-models) to prepare the shared cache or deploy model files separately. [qnn24/models.json](qnn24/models.json) pins the model files and notices.

Select **Qwen3-ASR NPU** in transcription settings. The app keeps one worker per recording. It reports approximate timestamps based on the source audio windows. For a device check, run `QwenHtpPhoneTest` with the instrumentation argument `runQwenHtp=true` and place public or synthetic fixtures `mixed.wav` and `dialog.wav` under the app's external files directory at `validation/qwen-htp/`.

The OnSpeak47 8-second worker used through v0.1.7 is preserved in the [v0.1.7 source](https://github.com/Happenmass/sound-software/tree/v0.1.7/tools/qwen-npu-worker).
