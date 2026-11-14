# Sound Software · 录音豆

[简体中文](README.zh-CN.md) · [Download APK](https://github.com/Happenmass/sound-software/releases/latest)

An independent Android app for the soundcore Work D3200 recorder. Sync recordings over Bluetooth or Wi-Fi, import audio, transcribe locally, edit speakers and transcripts, and export your work. The interface follows the Android language setting and supports English and Simplified Chinese.

- Batch sync and automatic sync after recording. Automatic sync prefers Wi-Fi if a batch contains a file of at least 2 MiB.
- Play and revisit the D3200's double-tap highlights. Device sync and highlights have been tested with firmware 04.92.
- The signed NPU APK includes Qwen3-ASR for on-device transcription on a compatible Qualcomm NPU. You can also configure a transcription API.
- Connect an OpenAI-compatible notes service for meeting, interview, job interview, and lecture notes. It can choose a template automatically. The assistant searches across recordings and links answers to the original transcript.
- Export documents and share WAV audio separately. Local transcription stays on the device. The notes service receives text, and the optional cloud transcription service receives audio only when selected.

## Install and set up

Download the signed APK from [Releases](https://github.com/Happenmass/sound-software/releases/latest). The public APK supports ARM64 devices running Android 8.0 or later. Wi-Fi sync requires Android 10 or later. It installs alongside the developer build with separate storage.

1. Open the app. In **Device**, find and pair a recorder, or use **Settings → Import audio file**.
2. For local transcription, select Qwen3-ASR under **Settings → Default transcription settings**. The app prepares the model on first use.
3. To generate notes or use the assistant, configure an HTTPS endpoint and model in **Settings → Notes service**. The assistant requires a model that supports tool calls.
4. Enable **LLM proofreading** in the same settings to send consecutive transcript fragments from the same speaker to the configured service. It can join sentences split across recognition windows and correct clear recognition errors. The setting starts off and applies when you transcribe or retranscribe a recording.

The release provides one signed NPU APK. Local transcription targets ARM64 SM8850 phones with 4 KB memory pages and has been verified on the HONOR Magic V6. The app lists compatible models and their installation status under **Settings → Local models**. See the [model guide](docs/MODELS.md#transcription-models) for storage and build details.

The public package ID is `dev.soundsoftware.app.release`. Install the new APK over the previous public APK to retain its recordings and settings.

## Qwen3-ASR NPU performance

Version 0.1.8 accepts up to 24 seconds of audio per inference. The bundled Qwen3-ASR 0.6B export keeps the full 151,936-token vocabulary and uses INT8 projection weights. The 64-token prefill graph and single-token decode graph share decoder weights and a 1,024-slot KV buffer. Grouped-query attention (GQA) lets each pair of query heads reuse the same key and value head without materializing copies.

On the HONOR Magic V6, the original 24-second INT8 export explicitly copied KV heads. Removing those copies reduced the time for one complete AR1 graph call, including all 28 layers and token selection, from **191.03 to 47.26 ms/token (4.04× faster, 75% less time)** at 120 valid history positions. On the same 24-second audio clip, the previous 8-second OnSpeak47 release transcribed three consecutive chunks in **2.24 s**, and this 24-second GQA export transcribed the clip in one pass in **4.36 s**, excluding model load. The longer window preserves context across the previous 8-second boundaries.

## Speaker analysis on Qualcomm HTP

The v0.1.10 APK includes the batch-1 HTP speaker model and the local CPU fallback weights. The app unpacks them on first speaker analysis. Both speaker embedding passes share one HTP graph, including the clean-speech pass during automatic speaker counting. If HTP is unavailable or fails, speaker analysis runs on the local CPU. See the [model guide](docs/MODELS.md#speaker-diarization).

On the HONOR Magic V6, a 44.969-second two-speaker sample took **4.276 s** to analyze automatically, compared with **4.517 s** when the second pass used a separate batch-4 graph. The second pass fell from **0.805 to 0.476 s**, and peak worker RSS fell from **432.25 to 359.73 MiB**. Both versions produced the same six boundaries and speaker assignments. Timing covers speaker analysis.

## Transcript continuity

On-device transcription groups consecutive speech from each identified speaker into passages of up to five minutes. Each Qwen3-ASR inference uses at most 24 seconds of audio, including short pauses. The app packs consecutive speech ranges in order and splits a range only if it exceeds that limit on its own. With LLM proofreading enabled, the configured notes service receives the ordered text fragments for a passage. The app keeps its speaker and time range. If proofreading fails, unprocessed passages retain the recognized text.

## Build from source

Use JDK 17, Android SDK Platform 35, and the included Gradle wrapper.

```sh
bash tools/setup-dependencies.sh
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

The unsigned APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`. To build a locally signed development APK, run `./gradlew :app:assembleDebug`. To distribute a release, align and sign it with your own Android signing key.

See [building the NPU APK](docs/MODELS.md#build-the-npu-apk) for the release configuration with Qwen3-ASR and speaker acceleration.

## License

The project's original source is available under [Apache-2.0](LICENSE). See [NOTICE](NOTICE) and [third-party licenses](app/src/main/assets/licenses/) for bundled components. Bundled model weights and Qualcomm runtimes retain their upstream terms. This project is independent of Anker and soundcore.
