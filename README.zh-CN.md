# 录音豆 · Sound Software

[English](README.md) · [下载 APK](https://github.com/Happenmass/sound-software/releases/latest)

面向 soundcore Work D3200 的独立 Android App。可以同步和导入录音，在手机上转写，编辑说话人和逐字稿，生成纪要并导出。界面默认跟随 Android 系统语言，支持简体中文和英文。

- 支持蓝牙、Wi-Fi 批量同步和录音结束后自动同步。自动同步时，单批有文件达到 2 MiB 就优先使用 Wi-Fi。
- 支持回放双击标记的设备重点。设备同步与重点已在 D3200 固件 04.92 验证。
- 本地 Qwen3-ASR、SenseVoice 支持离线转写，模型需单独安装。也可配置云端转写接口。
- 纪要支持会议、访谈、面试、课堂笔记和自动判断。录音助手可跨录音检索，回答附原文出处。
- 文档和 WAV 音频可独立导出。本地转写在手机上完成，纪要服务接收文字。选择云端转写时，音频才会上传到配置的转写接口。

## 安装和使用

从 [Releases](https://github.com/Happenmass/sound-software/releases/latest) 下载签名 APK。需要 ARM64、Android 8.0 或更高版本，Wi-Fi 快传需要 Android 10 或更高版本。发布版可与开发版并存，分别存储录音和设置。

1. 打开 App，在「设备」中配对录音豆，或在「我的」中导入音频。
2. 本地转写需先安装语音模型。在「我的 → 默认转写设置」选择已安装的模型。
3. 使用纪要或录音助手前，在「我的 → 纪要服务」配置兼容 OpenAI Chat Completions 的 HTTPS 接口和模型。录音助手要求模型支持工具调用。

在电脑上安装 Python 3.12 或更高版本和 Android Platform Tools，打开手机 USB 调试。安装并打开 App 后，连接手机并运行：

```sh
python3 tools/model-validation/prepare_cache.py sensevoice
SOUND_ANDROID_PACKAGE=dev.soundsoftware.app.release bash tools/deploy-models.sh sensevoice
```

如果使用 Qwen3-ASR，第一条命令使用 `qwen3`，第二条使用 `qwen`。说话人模型和 NPU 自建说明见[模型配置](docs/MODELS.md)。下载的模型保存在源码目录之外。

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和仓库自带的 Gradle Wrapper。

```sh
bash tools/setup-dependencies.sh
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

未签名包输出至 `app/build/outputs/apk/release/app-release-unsigned.apk`。日常开发可运行 `./gradlew :app:assembleDebug`。分发前请用自己的 Android 签名密钥签名。

公开 APK 使用 CPU 推理。源码保留 SenseVoice 和说话人分离的两条高通 HTP 路径，可在准备好匹配的运行库和模型后选择启用。详见[模型配置](docs/MODELS.md)。

## 许可证

项目原创源码采用 [Apache-2.0](LICENSE)。第三方组件见 [NOTICE](NOTICE) 和[许可文件](app/src/main/assets/licenses/)。模型权重和高通运行库另行获取，遵循各自的上游条款。本项目独立于 Anker 和 soundcore。
