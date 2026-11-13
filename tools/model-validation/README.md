# 模型验证工具

该目录实现可重复的短音频 smoke evaluation。它把模型运行、评测归一化和结果记录放在 App 之外；不包含用户会议录音、模型权重或 API 密钥。它不取代 `docs/VALIDATION_PLAN.md` 中的真实设备和会议验收。

## 固定环境与全局缓存

Python 3.12；sherpa-onnx 2.0.1 是此次实际验证版本。1.12.31 的 Python API 没有 `from_qwen3_asr`，不能直接运行本脚本。权重来源、文件大小和本次下载计算的 SHA-256 见 `artifacts.json`。这些 hash 用于固定后续重跑输入，不是发布者签名。

```sh
uv venv --python 3.12 ~/.cache/codex/model-validation-venv
uv pip install --python ~/.cache/codex/model-validation-venv/bin/python -r tools/model-validation/requirements.txt
export SOUND_MODEL_CACHE="$HOME/.cache/codex/models/sherpa-onnx"
~/.cache/codex/model-validation-venv/bin/python tools/model-validation/prepare_cache.py sensevoice qwen3
```

`prepare_cache.py` 复用现有 archive，校验大小/hash 后安全解包。可单独准备 `sensevoice-qnn-sm8850`，但它是 SM8850 专用 QNN context binary，不能传给 ONNX Runtime。缓存不得位于项目内。脚本不扫描或读取其他私人项目录音。

## 桌面真实运行

```sh
~/.cache/codex/model-validation-venv/bin/python tools/model-validation/benchmark.py \
  --model sensevoice \
  --model-dir "$SOUND_MODEL_CACHE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" \
  --manifest tools/model-validation/manifests/public-short-v1.json \
  --output /tmp/sensevoice-result.json
~/.cache/codex/model-validation-venv/bin/python tools/model-validation/benchmark.py \
  --model qwen3 \
  --model-dir "$SOUND_MODEL_CACHE/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25" \
  --manifest tools/model-validation/manifests/public-short-v1.json \
  --output /tmp/qwen3-result.json
```

默认 2 CPU threads、每条 3 次。仅接受 16 kHz mono、有限浮点数、0–30 秒 WAV；不在评测中悄悄重采样或截断。模型加载与识别耗时分开记录，RTF 不包含加载、文件解码、VAD 和说话人处理。进程 RSS 是生命周期峰值，不是模型独占内存。

Manifest 要求唯一 ID、音频路径、source、scope；reference 可省略，省略时不计算质量指标。路径支持 `${SOUND_MODEL_CACHE}`。`public-short-v1.json` 的 4 条样本合计 52.027 秒，来自上游示例，包含中文、英文、数字噪声音频和粤英混合；**不是普通话/英语会议评测集**。带参考文本的两条引用上游 archive 的 transcript.txt，尚未独立听校，不能用其分数选定产品模型。`codeswitch.wav` 实际是英/法/意/西混合且 44.1 kHz，未误归为中英会议样本。

归一化版本 v1：NFKC、casefold、移除/分隔标点；CER 按字符、WER 按词、MER 按汉字和英文词/数字计单位。**不做简繁转换、数字改写、同义词容错**。空参考单列 silence_insertions，不产生虚假的 0% 错误率。样本 hash、模型 hash、包版本、运行环境与原始输出均写入 JSON；中途失败保留已完成数据并退出非零。

## Android CPU CLI

已经在 HONOR PNM-AN10 / SM8850 / API 37 上运行。独立目录为 `/data/local/tmp/soundsoftware-model-validation`；不安装或启动其他 App，不改系统库。运行前确认只有一个 adb 目标；多目标时用 Android 的 `ANDROID_SERIAL` 环境变量明确选择（不要把序列号写进报告）。

官方 [2.0.1 Android Termux static archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android-aarch64-termux-static.tar.bz2) 中的 `bin/sherpa-onnx-offline` 仍动态依赖 libc++。本机 NDK r28 的 libc++ 缺少 `std::__ndk1::__hash_memory`；使用 [Termux libc++ 30 aarch64](https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/libc%2B%2B_30_aarch64.deb) 中的 `libc++_shared.so` 才启动成功。它仅用于独立 CLI；不要据此替换 App 的 C++ 运行库。

先把 `bin/sherpa-onnx-offline` 上传为目标目录的 `bin/sherpa-onnx-offline-static`，把该 libc++ 放入 `lib/`，`chmod 755` CLI。把两个完整模型目录分别上传为 `sensevoice/` 和 `qwen3/`（含 test_wavs）。这里上传权重只用于手机验证，仓库不保存副本。

```sh
~/.cache/codex/model-validation-venv/bin/python tools/model-validation/android_benchmark.py \
  --model sensevoice --manifest tools/model-validation/manifests/public-short-v1.json \
  --output /tmp/android-sensevoice.json
~/.cache/codex/model-validation-venv/bin/python tools/model-validation/android_benchmark.py \
  --model qwen3 --manifest tools/model-validation/manifests/public-short-v1.json \
  --output /tmp/android-qwen3.json
```

手机每次 repetition 都是新 CLI 进程，系统 page cache 不清除；不称为“冷启动”或与桌面热进程完全等价。`--measure-memory --repeat 1` 额外用 toybox time -v 记录最大 RSS，仍不是 App PSS。脚本会核对手机样本 SHA-256；模型完整性由传输前的全局缓存 hash 及 adb 传输保证，当前未在手机逐模型重算。JSON 中保留精确命令与设备型号/SoC/API，不记录设备唯一标识。

## Android / QNN 集成边界

- [2.0.1 官方 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) 与 CPU 模型可用；不能由 QNN 配置类存在推断原生库编译了 QNN。
- [官方 QNN sample 构建脚本](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/scripts/apk/build-apk-qnn-vad-asr-simulate-streaming.sh.in) 显式开启 `SHERPA_ONNX_ENABLE_QNN=ON`，使用 QNN headers/libs `2.40.0.251030`。
- SM8850 的 SenseVoice 10 秒模型：`senseVoice.qnnConfig.contextBinary=model.bin`、`backendLib=libQnnHtp.so`、`systemLib=libQnnSystem.so`、`provider=qnn`；`model` 为空。见 [官方 Kotlin idx 9022](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)。10 秒固定图需要上层切段和短段 padding 行为验证，不能直接喂整段会议。
- 官方 QNN libs 包含多代 Stub/Skel（含 V79、V81）。不得凭“骁龙8系”就选择相同 DSP 文件或复用其他 SoC 的 context binary。
- Qwen3 的 ONNX CPU 包与上述 SenseVoice QNN binary 是两条运行链路；当前没有验证 Qwen3 NPU。

## 工具测试

```sh
python3 -m unittest discover -s tools/model-validation -p 'test_*.py' -v
```

测试覆盖评分边界、清单唯一性、原子结果保存、Android 输出解析与启动失败，不用伪推理结果冒充模型通过。

## 2026-11-09 App 隔离接入

SenseVoice SM8850 QNN 已以独立子进程接入 App，保留 CPU JNI。构建与芯片条件见 [NPU 配置](../../docs/MODELS.md)。

## QNN复用版worker

`qnn_build.py`从项目`tools/qnn-worker/main.cc`复制到全局缓存，新增sound-qnn-worker目标；recognizer在stdin循环外构造一次。更新并审核`tools/qnn-runtime-lock.json`中的binary/source SHA后打包，默认不替换CPU JNI。SDK/backend/模型沿用固定版本，构建不会另下载权重。生命周期、协议、PCM等价及三轮对照见[复用版报告](../../docs/validation/qnn-context-reuse-2026-11-09.md)。
