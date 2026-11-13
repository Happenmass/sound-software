# Sound Software · 录音豆

[简体中文](README.zh-CN.md) · [Download APK](https://github.com/Happenmass/sound-software/releases/latest)

An independent Android app for the soundcore Work D3200 recorder. Sync recordings over Bluetooth or Wi-Fi, import audio, transcribe locally, edit speakers and transcripts, and export your work. The interface follows the Android language setting and supports English and Simplified Chinese.

- Batch sync and automatic sync after recording. Automatic sync prefers Wi-Fi if a batch contains a file of at least 2 MiB.
- Play and revisit the D3200's double-tap highlights. Device sync and highlights have been tested with firmware 04.92.
- Run Qwen3-ASR or SenseVoice on a compatible Qualcomm NPU, or configure a transcription API. Install local model weights separately.
- Connect an OpenAI-compatible notes service for meeting, interview, job interview, and lecture notes. It can choose a template automatically. The assistant searches across recordings and links answers to the original transcript.
- Export documents and share WAV audio separately. Local transcription stays on the device. The notes service receives text, and the optional cloud transcription service receives audio only when selected.

## Install and set up

Download the signed APK from [Releases](https://github.com/Happenmass/sound-software/releases/latest). The public APK supports ARM64 devices running Android 8.0 or later. Wi-Fi sync requires Android 10 or later. It installs alongside the developer build with separate storage.

1. Open the app. In **Device**, find and pair a recorder, or use **Settings → Import audio file**.
2. For local transcription, install Qwen3-ASR or SenseVoice using the commands below. In **Settings → Default transcription settings**, select the model you installed.
3. To generate notes or use the assistant, configure an HTTPS endpoint and model in **Settings → Notes service**. The assistant requires a model that supports tool calls.

The release provides one signed NPU APK. Local transcription targets ARM64 SM8850 phones with 4 KB memory pages and has been verified on the HONOR Magic V6. The app lists compatible models and their installation status under **Settings → Local models**. See the [model setup guide](docs/MODELS.md#transcription-models) to install either model.

The public package ID is `dev.soundsoftware.app.release`. Install the new APK over the previous public APK to retain its recordings and settings.

## Build from source

Use JDK 17, Android SDK Platform 35, and the included Gradle wrapper.

```sh
bash tools/setup-dependencies.sh
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

The unsigned APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`. To build a locally signed development APK, run `./gradlew :app:assembleDebug`. To distribute a release, align and sign it with your own Android signing key.

See [building the NPU APK](docs/MODELS.md#build-the-npu-apk) for the release configuration with both transcription engines and speaker acceleration.

## License

The project's original source is available under [Apache-2.0](LICENSE). See [NOTICE](NOTICE) and [third-party licenses](app/src/main/assets/licenses/) for bundled components. Model weights and Qualcomm runtimes are obtained separately under their upstream terms. This project is independent of Anker and soundcore.
