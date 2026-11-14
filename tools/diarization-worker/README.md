# 说话人 CPU / NPU worker

HTP 支持已在 SM8850 真机的 App UID 下验证。下面说明自建 CPU 和 HTP 组件的方法。

CPU worker 只并行原有独立前向：仍为 sherpa-onnx 2.0.1、相同 ORT 1.28.2、Pyannote segmentation-3.0 的 10 秒窗口/1 秒步长和 ERes2NetV2。窗口输出和声纹按原索引恢复顺序后才聚类；CPU 路径沿用 Kotlin 的干净语音区间、额外声纹和全局聚类。

## 构建与打包

前置依赖沿用 `tools/model-validation/qnn_build.py` 已校验的全局 source/ORT 缓存，以及 Android NDK 28.2.13676358/CMake 3.31.6。CPU worker 构建本身关闭 QNN；不需要 QNN 模型。

```sh
python3 tools/diarization-worker/build.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeSpeakerWorker=true
```

原生代码只在全局缓存编译。`tools/speaker-runtime-lock.json` 固定源码和二进制 SHA；重新编译后必须先通过输出一致性与性能验证，再审阅更新锁文件。Gradle 校验并复制 CPU 可执行文件至主包的 `libsound_speaker_worker.so`。它复用原 AAR 的同字节 ORT。

`-PincludeSpeakerWorker=true` 打包并行 CPU worker，生产中仅 SM8850 且录音至少 20 秒时启用。短录音及其他芯片使用 JNI。

## 协议与生命周期

`SOUND_DIAR` 协议版本 1，先 ready 帧，再以序号、人数（0=自动）、WAV 路径请求。响应须匹配协议、CPU 后端、PID 和请求序号。一次分析只创建一个 worker 进程，HTP 上下文在全部窗口间复用；结束、异常和取消关闭进程，取消不触发重算。计算失败会明确提示并回到原 CPU JNI，保持本地处理。

仅接受 16kHz 单声道 WAV、至多 80 分钟。输出限制为 2MB，超时取 90 秒与录音时长两倍的较大值。原始 float 音频仍在父进程供自动模式使用；长录音的额外内存与热负载需要继续验证。

## 真机验收

只放公开/合成测试录音到独立 `validation/speaker-npu`，不要导入用户录音库。测试文件：`dialog.wav`（两声）、`single.wav`、`three.wav`。手机序列号由调用者明确指定。

```sh
adb -s SERIAL shell am instrument -w -r -e runSpeakerNpu true -e class 'dev.soundsoftware.app.inference.SpeakerNpuValidationTest#cpuReferenceAndNativeTimers' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
adb -s SERIAL shell am instrument -w -r -e runSpeakerNpu true -e class 'dev.soundsoftware.app.inference.SpeakerNpuValidationTest#compareParallelWithoutChangingAnyTurnOrIdentity' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
adb -s SERIAL shell am instrument -w -r -e runSpeakerNpu true -e class 'dev.soundsoftware.app.inference.SpeakerNpuValidationTest#pairedSpeedAndControlQuality' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
adb -s SERIAL shell am instrument -w -r -e runSpeakerNpu true -e class 'dev.soundsoftware.app.inference.SpeakerNpuValidationTest#productionFactoryAndCpuFallbackPreserveResult' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
```

比较的是每段的毫秒起止与人物归属完全一致，允许标签编号整体换名；不允许时间容差或只比较人数。交替执行原 JNI/并行版，报告含完整逐段结果、三轮耗时和热状态。独立重算保留改稿的回归另见 `QnnPhoneValidationTest#recalculateSpeakersPreservesEdits`。


## 2.50 NPU 混合方案：构建和部署

固定 Community SDK 2.50.0.260828（Android build 2.50.0.260828221209），完整 ZIP SHA 在 `tools/speaker-npu-runtime-lock.json`。SDK、编译结果和权重均位于全局缓存。

官方完整 SDK 的 GET 下载入口：
`https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip`。
解压后本机 SDK 路径为 `~/.cache/codex/dependencies/qairt/2.50.0/sdk/qairt/2.50.0.260828`。只需校验和解压一次，不能使用仅含 Windows Genie 的 AppBuilder ZIP 代替。下载大文件时可用 HTTP/1.1、续传和重试；HEAD 403 不是 GET 不可用的判据。

本机已验证 SDK 自带 aarch64-oe 工具在 ARM Linux 容器运行；这不是宣称 Qualcomm 官方支持 macOS ARM VM。可用本目录 `Dockerfile.qairt` 重建相同依赖容器：

```sh
docker build -f tools/diarization-worker/Dockerfile.qairt -t sound-qairt250-tools tools/diarization-worker
docker run --rm \
  -v "$HOME"/.cache/codex/dependencies/qairt/2.50.0/sdk/qairt/2.50.0.260828:/sdk:ro \
  -v "$HOME"/.cache/codex/models/sherpa-onnx:/models:ro \
  -v "$HOME"/.cache/codex/models/qualcomm:/out \
  -v "$PWD/tools/diarization-worker":/work:ro \
  sound-qairt250-tools python /work/export-npu-models.py
```

该命令会先执行 9 种长度的 FP32 等价测试。

用 QAIRT 转换：

```sh
python /sdk/bin/aarch64-oe-linux-gcc11.2/qairt-converter \
  --input_network /out/speaker-eres2netv2-qnn250/embedding-masked-trunk.onnx \
  --output_path /out/speaker-eres2netv2-qnn250/embedding-masked-trunk.dlc \
  --source_model_input_layout features NCHW --desired_input_layout features NCHW \
  --source_model_output_layout trunk NCHW --desired_output_layout trunk NCHW \
  --float_bitwidth 16 --target_backend HTP --target_soc_model SM8850
```

在选定 SM8850 测试手机的独立 `/data/local/tmp/sound-qnn250` 内放入 **2.50 同版本**的 `qnn-context-binary-generator`、`libQnnModelDlc.so`、HTP/System/Prepare/V81 Stub/Skel 和生成的 DLC。用该目录作为 `LD_LIBRARY_PATH` 的首项与 `ADSP_LIBRARY_PATH`，执行：

```sh
qnn-context-binary-generator --backend ./libQnnHtp.so --model ./libQnnModelDlc.so \
  --dlc_path ./embedding-masked-trunk.dlc --binary_file eres2netv2-masked --output_dir .
```

将生成的 `.bin` 与同次导出的 `embedding-head.onnx` 放在全局 `~/.cache/codex/models/qualcomm/speaker-eres2netv2-qnn250/`。重新生成 context 后核对锁文件并复测质量和耗时。

```sh
python3 tools/diarization-worker/build-npu.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeSpeakerNpu=true
python3 tools/deploy-speaker-npu-models.py --serial SERIAL
```

`-PincludeSpeakerNpu=true` 打包独立 NPU worker 与 2.50 库 asset ZIP。Qwen3-ASR 同样使用 QAIRT 2.50，在独立进程中运行。锁文件内已验证的 PNM-AN10 安装配套权重后，各时长默认走 HTP。第一轮局部声纹和自动人数的干净语音复提共用同一张 batch 1 图、HTP 上下文和 CPU FP32 head。第二轮仍每次最多向 worker 传四段，worker 按顺序逐条运行 batch 1。每段使用自己的有效长度掩码。Pyannote 切分、fbank、统计池化和聚类仍在 CPU。权重缺失直接走 CPU；模型校验、HTP 执行或响应失败会取消本次 worker 结果并使用本地 CPU 重算。手动指定人数只执行第一轮。batch 1 图及 head 共约 49 MB 权重单独部署到 `diarization-npu`。安装公开版后，用 `--package dev.soundsoftware.app.release` 部署模型。构建时从同版 SDK 根目录验证并复制原始许可及声明。

## NPU 真机验收

依次安装主包和测试包，等待每个安装成功；公开/合成 WAV 放到手机独立 `validation/speaker-npu-v2/`，文件为 `dialog.wav`、`single.wav`、`single-long.wav`、`three.wav`。长单人对照由公开短中文片段循环 8 次，间隔 250ms 生成；CPU/NPU 速度比较仍使用同一段原 44.969 秒双人录音。

```sh
adb -s SERIAL shell am instrument -w -r -e runSpeakerHtp true -e class 'dev.soundsoftware.app.inference.SpeakerHtpValidationTest#numericQualityAndOneTwoThreeSpeakerControls' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
adb -s SERIAL shell am instrument -w -r -e runSpeakerHtp true -e class 'dev.soundsoftware.app.inference.SpeakerHtpValidationTest#pairedCpuNpuSpeedAndAttribution' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
adb -s SERIAL shell am instrument -w -r -e runSpeakerHtp true -e class 'dev.soundsoftware.app.inference.SpeakerHtpValidationTest#actualNpuCancellationAndExplicitLocalFallback' dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
```

质量模式会同时算 CPU 和 NPU，验证归一化方向、每段归属并输出真实 HTP 次数；速度模式必须 `validatedEmbeddings=0`，排除双算开销。空语音/单窗不需要声纹时不冒充 NPU。自然会议 DER/JER 和长热负载尚待验收。

用同一条双人样本核对两轮图调用和说话人归属：

```sh
adb -s SERIAL shell am instrument -w -r -e runSpeakerHtp true \
  -e class 'dev.soundsoftware.app.inference.SpeakerHtpValidationTest#sharedGraphSpeedAndAttribution' \
  dev.soundsoftware.app.test/dev.soundsoftware.app.AssistantTestRunner
```

测试将总耗时、第二轮耗时、图调用次数和完整归属写入 `validation/speaker-npu-v2/shared-graph.json`。
