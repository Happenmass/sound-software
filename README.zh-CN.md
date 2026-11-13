# 录音豆 · Sound Software

[English](README.md) · [下载 APK](https://github.com/Happenmass/sound-software/releases/latest)

面向 soundcore Work D3200 的独立 Android App。可以同步和导入录音，在手机上转写，编辑说话人和逐字稿，生成纪要并导出。界面默认跟随 Android 系统语言，支持简体中文和英文。

- 支持蓝牙、Wi-Fi 批量同步和录音结束后自动同步。自动同步时，单批有文件达到 2 MiB 就优先使用 Wi-Fi。
- 支持回放双击标记的设备重点。设备同步与重点已在 D3200 固件 04.92 验证。
- Qwen3-ASR 和 SenseVoice 可在兼容的高通 NPU 上离线转写，模型权重单独安装。也可配置云端转写接口。
- 纪要支持会议、访谈、面试、课堂笔记和自动判断。录音助手可跨录音检索，回答附原文出处。
- 文档和 WAV 音频可独立导出。本地转写在手机上完成，纪要服务接收文字。选择云端转写时，音频才会上传到配置的转写接口。

## 安装和使用

从 [Releases](https://github.com/Happenmass/sound-software/releases/latest) 下载签名 APK。需要 ARM64、Android 8.0 或更高版本，Wi-Fi 快传需要 Android 10 或更高版本。发布版可与开发版并存，分别存储录音和设置。

1. 打开 App，在「设备」中配对录音豆，或在「我的」中导入音频。
2. 本地转写需先安装语音模型。在「我的 → 默认转写设置」选择已安装的模型。
3. 使用纪要或录音助手前，在「我的 → 纪要服务」配置兼容 OpenAI Chat Completions 的 HTTPS 接口和模型。录音助手要求模型支持工具调用。

Release 提供一个签名的 NPU APK。本地转写适用于 SM8850、ARM64、4 KB 内存页设备，已在荣耀 Magic V6 验证。「我的 → 本地模型」显示兼容模型及安装状态。模型安装步骤见[配置文档](docs/MODELS.md#transcription-models)。

公开版包名为 `dev.soundsoftware.app.release`。已有公开版可以直接覆盖安装，保留原录音和设置。

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和仓库自带的 Gradle Wrapper。

```sh
bash tools/setup-dependencies.sh
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

未签名包输出至 `app/build/outputs/apk/release/app-release-unsigned.apk`。日常开发可运行 `./gradlew :app:assembleDebug`。分发前请用自己的 Android 签名密钥签名。

同时启用两种 NPU 转写和声纹加速的发布构建命令见[构建说明](docs/MODELS.md#build-the-npu-apk)。

## 许可证

项目原创源码采用 [Apache-2.0](LICENSE)。第三方组件见 [NOTICE](NOTICE) 和[许可文件](app/src/main/assets/licenses/)。模型权重和高通运行库另行获取，遵循各自的上游条款。本项目独立于 Anker 和 soundcore。
