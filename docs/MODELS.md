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

## Qualcomm HTP on SM8850

The Releases page provides a CPU APK and an optional NPU APK. Both use the same release package ID and signing key. Install the NPU APK on an ARM64 Snapdragon 8 Elite Gen 5 (SM8850) phone with 4 KB memory pages. The app checks compatibility before running SenseVoice on HTP.

Install the model separately using Python 3.12+ and Android Platform Tools. Open the NPU app once, connect the phone with USB debugging enabled, then run:

```sh
python3 tools/model-validation/prepare_cache.py sensevoice-qnn-sm8850
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh qnn-sm8850
```

Import audio or open a synced recording. Select **SenseVoice · Qualcomm NPU** in **Transcription settings**, then start transcription. The Transcript tab shows **Transcribed with Qualcomm HTP** only after the backend confirms a successful HTP graph execution. Qwen3-ASR uses CPU. On the HONOR Magic V6 (PNM-AN10), the NPU APK can also accelerate speaker embeddings for recordings at least 20 seconds long. Audio segmentation and final clustering use CPU.

### Speaker NPU model setup

Install the two CPU speaker models above. Prepare the matching masked ERes2NetV2 context and FP32 head using the [speaker worker instructions](../tools/diarization-worker/README.md#250-npu-混合方案构建和部署). Then run:

```sh
python3 tools/deploy-speaker-npu-models.py --serial SERIAL --package dev.soundsoftware.app.release
```

Close and reopen the app after installing the models. Speaker acceleration is selected automatically for eligible recordings and works with any transcription backend. After processing or reanalyzing speakers, the Transcript tab displays the verified HTP embedding execution count. CPU speaker analysis remains available when the NPU route is unavailable.

## Build the NPU APK

SenseVoice HTP transcription uses the pinned QNN 2.40 runtime and the SM8850 ten-second SenseVoice context model. It was validated on an SM8850 ARM64 phone with 4 KB memory pages. Qwen3-ASR uses the CPU. Speaker diarization has a separate optional HTP path using QNN 2.50.

For local development, prepare the [pinned QNN build inputs](../tools/model-validation/README.md), run `python3 tools/model-validation/qnn_build.py`, and place the required QNN 2.40 runtime files in the locations specified by [the runtime lock](../tools/qnn-runtime-lock.json). Obtain Qualcomm binaries under their upstream terms. Build using:

```sh
bash tools/setup-dependencies.sh
./gradlew :app:assembleDebug -PincludeQnn=true -PincludeSpeakerWorker=false -PincludeSpeakerNpu=false
python3 tools/model-validation/prepare_cache.py sensevoice-qnn-sm8850
bash tools/deploy-models.sh qnn-sm8850
```

For the NPU release configuration, run `./gradlew :app:assembleRelease -PincludeQnn=true -PincludeSpeakerWorker=false -PincludeSpeakerNpu=true` and use your own Android signing key. Put the exact SDK files `LICENSE.pdf`, `NOTICE.txt` and `QNN_NOTICE.txt` in `~/.cache/codex/dependencies/qairt/2.40.0/notices/`. The build verifies them against the runtime lock and includes them in the NPU APK. The same files are required for QNN development builds.

Prepare the [speaker worker](../tools/diarization-worker/README.md) and extract the original QNN 2.50 notices at the root of its SDK directory. The build verifies both SDKs’ notices and includes them in separate APK folders.

## QNN distribution

The [official Qualcomm QAIRT Community 2.40.0.251030 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.40.0.251030/v2.40.0.251030.zip) supplies the AI Stack License. Section 1(iv) permits object-code distribution as part of an application. Sections 2(b) and 10(h) require retaining the relevant proprietary and third-party notices. The release embeds five byte-for-byte verified runtime libraries and the SDK notices under `assets/licenses/qualcomm-qairt-2.40/`. The [runtime lock](../tools/qnn-runtime-lock.json) records the SDK paths and SHA-256 hashes. Qualcomm libraries are proprietary and covered by Qualcomm's terms. The repository's Apache-2.0 license applies to the project's own code.

The [official Qualcomm QAIRT Community 2.50.0.260828 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip) uses the same AI Stack License. The NPU APK includes the four byte-for-byte verified libraries in `assets/speaker-npu-runtime.zip`, which the app loads separately from the QNN 2.40 libraries. The original 2.50 notices are under `assets/licenses/qualcomm-qairt-2.50/`. See the [speaker runtime lock](../tools/speaker-npu-runtime-lock.json) for hashes and the exact SDK build. The source repository and CPU APK carry no Qualcomm runtime binaries.
