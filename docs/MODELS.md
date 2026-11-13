# Local models and NPU

On-device transcription in current builds uses a compatible NPU. The signed v0.1.5 NPU APK bundles Qwen3-ASR and SenseVoice weights. On other devices, transcription requires an explicitly configured and selected remote service. The app lists compatible Qwen3-ASR and SenseVoice NPU models and shows their installation status.

## Transcription models

The signed NPU APK includes the runtimes for both models. Local transcription targets ARM64 SM8850 with 4 KB memory pages and was tested on the HONOR Magic V6. On first use, the app prepares the selected model from the APK in app-private storage. Allow about 900 MB of additional space for Qwen3-ASR and 255 MB for SenseVoice. Existing matching model files can be reused. Choose the installed model in **Settings → Default transcription settings** or in the settings for an individual recording.

### Source build inputs and separate model setup

For a source build with bundled weights, use the pinned model downloads below. The download and cache preparation commands are sufficient for building. Run the deployment commands only for an APK built without bundled weights. If using ADB with multiple devices, select the target with `ANDROID_SERIAL`.

### Qwen3-ASR NPU

Download the [pinned OnSpeak47 v2.2.0-npu model](https://github.com/DS-PNQ/OnSpeak47-npu-models/releases/tag/v2.2.0-npu) with the GitHub CLI, then install it:

```sh
model_dir="$HOME/.cache/codex/models/qwen3-asr-onspeak47-sm8850/v2.2.0-npu"
gh release download v2.2.0-npu -R DS-PNQ/OnSpeak47-npu-models --dir "$model_dir" --skip-existing
python3 tools/qwen-npu-worker/deploy.py --serial SERIAL --package dev.soundsoftware.app.release
```

### SenseVoice NPU

```sh
python3 tools/model-validation/prepare_cache.py sensevoice-qnn-sm8850
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh qnn-sm8850
```

Reopen the app and confirm the model shows **Installed** under **Settings → Local models**. The Transcript tab shows **Transcribed with Qualcomm HTP** after a successful HTP graph execution.

## Speaker diarization

Use [pyannote segmentation 3.0 for sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models) and [the ERes2NetV2 3D-Speaker embedding model](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models). In the same global cache directory, extract the full precision `model.onnx` under `sherpa-onnx-pyannote-segmentation-3-0/` and save `3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx` as `3dspeaker_eres2netv2_zh.onnx`. Install them with:

```sh
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh diarization
```

### Speaker NPU model setup

Install the two CPU speaker models above. Prepare the matching masked ERes2NetV2 context and FP32 head using the [speaker worker instructions](../tools/diarization-worker/README.md#250-npu-混合方案构建和部署). Then run:

```sh
python3 tools/deploy-speaker-npu-models.py --serial SERIAL --package dev.soundsoftware.app.release
```

Close and reopen the app after installing the models. Speaker acceleration is selected automatically for eligible recordings and works with any transcription backend. After processing or reanalyzing speakers, the Transcript tab displays the verified HTP embedding execution count. CPU speaker analysis remains available when the NPU route is unavailable.

## Build the NPU APK

SenseVoice HTP transcription uses the pinned QNN 2.40 runtime and the SM8850 ten-second SenseVoice context model. It was validated on an SM8850 ARM64 phone with 4 KB memory pages. Qwen3-ASR uses an isolated QAIRT 2.51 runtime and ONNX Runtime 1.29. Speaker diarization has a separate optional HTP path using QNN 2.50.

For local development, prepare the [pinned QNN build inputs](../tools/model-validation/README.md), run `python3 tools/model-validation/qnn_build.py`, and place the required QNN 2.40 runtime files in the locations specified by [the runtime lock](../tools/qnn-runtime-lock.json). Obtain Qualcomm binaries under their upstream terms. Build using:

```sh
bash tools/setup-dependencies.sh
./gradlew :app:assembleDebug -PincludeQnn=true -PincludeQwenHtp=true
python3 tools/model-validation/prepare_cache.py sensevoice-qnn-sm8850
bash tools/deploy-models.sh qnn-sm8850
```

For the NPU release configuration, run `./gradlew :app:assembleRelease -PincludeQnn=true -PincludeQwenHtp=true -PincludeSpeakerWorker=false -PincludeSpeakerNpu=true -PincludeAsrWeights=true` and use your own Android signing key. The build reads verified weights from the shared model cache and bundles the upstream model licenses and notices under `assets/licenses/asr-models/`. Put the exact SDK files `LICENSE.pdf`, `NOTICE.txt` and `QNN_NOTICE.txt` in `~/.cache/codex/dependencies/qairt/2.40.0/notices/`. The build verifies them against the runtime lock and includes them in the NPU APK. The same files are required for QNN development builds.

Prepare the [speaker worker](../tools/diarization-worker/README.md) and extract the original QNN 2.50 notices at the root of its SDK directory. The build verifies and includes the notices. The [Qwen worker build](../tools/qwen-npu-worker/README.md) also requires Android NDK 28.2.13676358.

## QNN distribution

The [official Qualcomm QAIRT Community 2.40.0.251030 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.40.0.251030/v2.40.0.251030.zip) supplies the AI Stack License. Section 1(iv) permits object-code distribution as part of an application. Sections 2(b) and 10(h) require retaining the relevant proprietary and third-party notices. The release embeds five byte-for-byte verified runtime libraries and the SDK notices under `assets/licenses/qualcomm-qairt-2.40/`. The [runtime lock](../tools/qnn-runtime-lock.json) records the SDK paths and SHA-256 hashes. Qualcomm libraries are proprietary and covered by Qualcomm's terms. The repository's Apache-2.0 license applies to the project's own code.

The [official Qualcomm QAIRT Community 2.50.0.260828 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip) uses the same AI Stack License. The NPU APK includes the four byte-for-byte verified libraries in `assets/speaker-npu-runtime.zip`, which the app loads separately from the QNN 2.40 libraries. The original 2.50 notices are under `assets/licenses/qualcomm-qairt-2.50/`. See the [speaker runtime lock](../tools/speaker-npu-runtime-lock.json) for hashes and the exact SDK build. The source repository and v0.1.2 CPU APK carry no Qualcomm runtime binaries.

The Qwen3-ASR runtime uses the pinned Qualcomm QAIRT 2.51.0 Maven package. The APK includes its original AI Stack License and notice in `assets/licenses/qwen-npu/`, along with the ONNX Runtime, QNN execution provider and Kiss FFT licenses and notices. See the [artifact manifest](../tools/qwen-npu-worker/artifacts.json) for sources and checksums.
