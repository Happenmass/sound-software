# 录音豆 · Sound Software

[English](README.md) · [下载 APK](https://github.com/Happenmass/sound-software/releases/latest)

面向 soundcore Work D3200 的独立 Android App。可以同步和导入录音，在手机上转写，编辑说话人和逐字稿，生成纪要并导出。界面默认跟随 Android 系统语言，支持简体中文和英文。

- 支持蓝牙、Wi-Fi 批量同步和录音结束后自动同步。自动同步时，单批有文件达到 2 MiB 就优先使用 Wi-Fi。
- 支持回放双击标记的设备重点。设备同步与重点已在 D3200 固件 04.92 验证。
- 签名 NPU APK 内置 Qwen3-ASR 权重，可在兼容的高通 NPU 上离线转写。也可配置云端转写接口。
- 纪要支持会议、访谈、面试、课堂笔记和自动判断。录音助手可跨录音检索，回答附原文出处。
- 文档和 WAV 音频可独立导出。本地转写在手机上完成，纪要服务接收文字。选择云端转写时，音频才会上传到配置的转写接口。

## 安装和使用

从 [Releases](https://github.com/Happenmass/sound-software/releases/latest) 下载签名 APK。需要 ARM64、Android 8.0 或更高版本，Wi-Fi 快传需要 Android 10 或更高版本。发布版可与开发版并存，分别存储录音和设置。

1. 打开 App，在「设备」中配对录音豆，或在「我的」中导入音频。
2. 在「我的 → 默认转写设置」选择 Qwen3-ASR，首次使用时 App 自动准备模型。
3. 使用纪要或录音助手前，在「我的 → 纪要服务」配置兼容 OpenAI Chat Completions 的 HTTPS 接口和模型。录音助手要求模型支持工具调用。

Release 提供一个签名的 NPU APK。本地转写适用于 SM8850、ARM64、4 KB 内存页设备，已在荣耀 Magic V6 验证。「我的 → 本地模型」显示兼容模型及安装状态。存储与构建说明见[配置文档](docs/MODELS.md#transcription-models)。

公开版包名为 `dev.soundsoftware.app.release`。已有公开版可以直接覆盖安装，保留原录音和设置。

## Qwen3-ASR NPU 性能

v0.1.8 单次可转写最长 24 秒音频。内置 Qwen3-ASR 0.6B 使用 INT8 投影权重，保留 151,936 个词表条目。64-token prefill 和逐 token 解码共享解码器权重及一份 1,024 槽 KV 缓冲。分组查询注意力（GQA）让每两个 query head 直接复用同一个 KV head，省去显式复制。

在荣耀 Magic V6 上，相比最初显式复制 KV head 的 24 秒 INT8 导出版，去掉复制后，120 个有效历史位置下的 **AR1 整图从 191.03 降到 47.26 ms/token，耗时减少 75%，速度约为 4.04 倍**。计时包含 28 层解码和选词。同一段 24 秒音频，上一公开版的 8 秒 OnSpeak47 分三次识别耗时 **2.24 秒**，本版 24 秒 GQA 一次识别耗时 **4.36 秒**，均排除模型加载。24 秒输入能保留原 8 秒切点两侧的上下文。

## 高通 HTP 说话人分析

0.1.9 的两轮声纹共用一张 batch 1 HTP 图。自动判断人数时，第二轮干净语音沿用第一轮的 HTP 上下文。HTP 不可用或执行失败时，使用本地 CPU 完成说话人分析。说话人权重按[配置文档](docs/MODELS.md#speaker-npu-model-setup)单独安装。

荣耀 Magic V6 使用同一段 44.969 秒双人音频，相比第二轮单独使用 batch 4 图，自动人数分析从 **4.517 秒降到 4.276 秒**，第二轮从 **0.805 秒降到 0.476 秒**，worker 峰值 RSS 从 **432.25 MiB 降到 359.73 MiB**。两版的六段边界和说话人归属完全一致。耗时仅计说话人分析。

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和仓库自带的 Gradle Wrapper。

```sh
bash tools/setup-dependencies.sh
./gradlew :core:test :app:lintRelease :app:assembleRelease
```

未签名包输出至 `app/build/outputs/apk/release/app-release-unsigned.apk`。日常开发可运行 `./gradlew :app:assembleDebug`。分发前请用自己的 Android 签名密钥签名。

启用 Qwen3-ASR NPU 转写和声纹加速的发布构建命令见[构建说明](docs/MODELS.md#build-the-npu-apk)。

## 许可证

项目原创源码采用 [Apache-2.0](LICENSE)。第三方组件见 [NOTICE](NOTICE) 和[许可文件](app/src/main/assets/licenses/)。随包模型权重和高通运行库遵循各自的上游条款。本项目独立于 Anker 和 soundcore。
