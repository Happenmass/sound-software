# Local models and NPU

The APK includes the pinned sherpa-onnx CPU runtime. Qwen3-ASR, SenseVoice and the optional speaker diarization models are installed separately. See the upstream terms for each model.

## CPU transcription

Run `python3 tools/model-validation/prepare_cache.py sensevoice` or `python3 tools/model-validation/prepare_cache.py qwen3` (Python 3.12+). This verifies the pinned archive against [the model manifest](../tools/model-validation/artifacts.json) and extracts it under `~/.cache/codex/models/sherpa-onnx`. Then run `SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh sensevoice` or use `qwen` as the last argument.

SenseVoice supports selecting a language. Qwen3-ASR is the default for mixed Chinese and English. Settings shows whether each model is ready.

## Speaker diarization

Use [pyannote segmentation 3.0 for sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models) and [the ERes2NetV2 3D-Speaker embedding model](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models). In the same global cache directory, extract the full precision `model.onnx` under `sherpa-onnx-pyannote-segmentation-3-0/` and save `3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx` as `3dspeaker_eres2netv2_zh.onnx`. Install them with:

```sh
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh diarization
```

## Qualcomm HTP development builds

SenseVoice HTP transcription uses the pinned QNN 2.40 runtime and the SM8850 ten-second SenseVoice context model. It was validated on an SM8850 ARM64 phone with 4 KB memory pages. Qwen3-ASR uses the CPU. Speaker diarization has a separate optional HTP path using QNN 2.50.

For local development, prepare the [pinned QNN build inputs](../tools/model-validation/README.md), run `python3 tools/model-validation/qnn_build.py`, and place the required QNN 2.40 runtime files in the locations specified by [the runtime lock](../tools/qnn-runtime-lock.json). Obtain Qualcomm binaries under their upstream terms. Build using:

```sh
bash tools/setup-dependencies.sh
./gradlew :app:assembleDebug -PincludeQnn=true -PincludeSpeakerWorker=false -PincludeSpeakerNpu=false
python3 tools/model-validation/prepare_cache.py sensevoice-qnn-sm8850
bash tools/deploy-models.sh qnn-sm8850
```

See [speaker worker setup](../tools/diarization-worker/README.md) for its separate NPU build. The APK offered on the Releases page uses CPU inference and carries no Qualcomm binaries.
