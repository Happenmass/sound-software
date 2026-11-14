# Local models and NPU

On-device transcription in current builds uses a compatible NPU. The signed NPU APK bundles Qwen3-ASR weights. On other devices, transcription requires an explicitly configured and selected remote service. The app lists Qwen3-ASR and its installation status on compatible devices.

## Transcription models

The signed NPU APK includes the Qwen3-ASR runtime and weights. Local transcription targets ARM64 SM8850 with 4 KB memory pages and was tested on the HONOR Magic V6. On first use, the app prepares the selected model from the APK in app-private storage. Allow about 964 MB of additional space for model preparation. Existing matching model files can be reused. Choose Qwen3-ASR NPU in **Settings → Default transcription settings** or for an individual recording. Older local profiles, including SenseVoice, migrate to Qwen3-ASR. Existing transcripts retain their original text and provenance.

### Source build inputs and separate model setup

For a source build with bundled weights, use the pinned model downloads below. The download and cache preparation commands are sufficient for building. Run the deployment commands only for an APK built without bundled weights. If using ADB with multiple devices, select the target with `ANDROID_SERIAL`.

### Qwen3-ASR NPU

The pinned 24-second W8 GQA export is bundled in the [v0.1.8 NPU APK](https://github.com/Happenmass/sound-software/releases/tag/v0.1.8). For a source build, extract the assets from that signed APK into the shared model cache. The build checks each file against the pinned hashes.

```sh
model_dir="$HOME/.cache/codex/models/qwen3-asr-sm8850/0.6b-w8fp16-24s-kv1024-gqa64-shared-kv"
apk=SoundSoftware-0.1.8-NPU-SM8850-arm64-v8a.apk
mkdir -p "$model_dir"
unzip -j "$apk" 'assets/asr-models/qwen-htp-sm8850/*' -d "$model_dir"
unzip -p "$apk" assets/licenses/asr-models/qwen3-asr-LICENSE > "$model_dir/LICENSE"
unzip -p "$apk" assets/licenses/asr-models/qwen3-asr-NOTICE > "$model_dir/NOTICE"
```

For an APK built without bundled weights, run:

```sh
python3 tools/qwen-npu-worker/deploy.py --serial SERIAL --package dev.soundsoftware.app.release
```

Reopen the app and confirm the model shows **Installed** under **Settings → Local models**. The Transcript tab shows **Transcribed with Qualcomm HTP** after a successful HTP graph execution.

## Speaker diarization

Builds with bundled speaker weights prepare them from the APK on first speaker analysis, including when a recording is transcribed or speakers are recalculated. The app can reuse matching weights already installed on an upgraded device. The CPU files need about 74 MiB of private storage. On a supported device, the HTP graph and FP32 head add about 47 MiB. A failure to prepare or run HTP falls back to the CPU models. The Transcript tab displays the verified HTP graph call count.

### Speaker NPU model setup

For a source build, use [pyannote segmentation 3.0 for sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models) and [the ERes2NetV2 3D-Speaker embedding model](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models). In the shared `~/.cache/codex/models/sherpa-onnx/` directory, extract the full precision `model.onnx` under `sherpa-onnx-pyannote-segmentation-3-0/` and save `3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx` as `3dspeaker_eres2netv2_zh.onnx`. Prepare the matching batch-1 context and FP32 head using the [speaker worker instructions](../tools/diarization-worker/README.md#250-npu-混合方案构建和部署). Both embedding passes reuse that HTP context.

`-PincludeSpeakerWeights=true` bundles the CPU files and, when `-PincludeSpeakerNpu=true` is set, the two HTP files. The flag defaults to the value of `includeAsrWeights`. The build verifies all model hashes and includes the upstream model notices under `assets/licenses/speaker-models/`.

For v0.1.9 and other APKs built without bundled speaker weights, install the models separately and reopen the app:

```sh
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh diarization
python3 tools/deploy-speaker-npu-models.py --serial SERIAL --package dev.soundsoftware.app.release
```

## Build the NPU APK

Qwen3-ASR calls QAIRT 2.50 directly from an isolated worker process. The optional speaker accelerator uses the same SDK in a separate worker.

The public APK includes the on-device Pi assistant runtime. Install Node.js 22.19 or newer, Python 3.11 or newer, and Android NDK 28.2.13676358.

```sh
bash tools/setup-dependencies.sh
./gradlew :app:assembleRelease -PincludeQwenHtp=true -PincludeSpeakerNpu=true -PincludeAsrWeights=true -PincludePi=true \
  -Dorg.gradle.jvmargs="-Xmx5g -Dfile.encoding=UTF-8"
```

Use your Android signing key for distribution. The build verifies weights from the shared cache and bundles upstream model notices under `assets/licenses/asr-models/`. For a development APK, use `:app:assembleDebug` with the same flags.

Prepare the [speaker worker](../tools/diarization-worker/README.md) and extract the original QNN 2.50 notices at the root of its SDK directory. The build verifies and includes the notices. The [Qwen worker build](../tools/qwen-npu-worker/README.md) also requires Android NDK 28.2.13676358.

## QNN distribution

The [official Qualcomm QAIRT Community 2.50.0.260828 SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip) uses the same AI Stack License. The NPU APK includes four byte-for-byte verified libraries in each worker’s runtime archive. The original 2.50 notices are under `assets/licenses/qualcomm-qairt-2.50/`. See the [speaker runtime lock](../tools/speaker-npu-runtime-lock.json) for hashes and the exact SDK build. The source repository and v0.1.2 CPU APK carry no Qualcomm runtime binaries.
