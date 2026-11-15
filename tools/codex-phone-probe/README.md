# Magic V6 上的 Codex 与 Node 验证

2026-11-14，在荣耀 Magic V6（PNM-AN10、Android 17、ARM64、4 KB 内存页）通过。独立 APK 的应用 UID 为 10402，`targetSdkVersion` 为 35，清单无网络权限。ADB 用于安装和读取结果，执行在手机上完成。

APK 使用 OpenAI 发布的 Codex 0.165.0 ARM64 musl 静态二进制，以及 Pi 构建锁定的 Node.js Mobile 24.22.1。Android 安装器将可执行文件和依赖解包到 APK 的原生库目录。验证器先在专用子进程启用 Landlock ABI 6，再启动 Codex App Server。客户端通过标准输入输出初始化 App Server，以 `externalSandbox` 调用 `command/exec`，由继承 Landlock 规则的 Node 进程执行脚本。

- Node 读入任务目录里的合成文本，写出 `result.csv`，App 核对产物全文。
- 同一 App UID 的相邻目录里有一个合成文件。限制内进程的绝对路径读取、相对路径读取、列目录、覆盖、新建和删除均被拒绝。指向外部文件的符号链接读写和 Node 子进程读外部文件也被拒绝。宿主确认合成文件内容未变，目录外也没有新文件。
- 任务目录可以读写。白名单还允许访问原生运行时、Android 动态链接所需路径、`/proc` 和两个设备节点。Codex 启动会提示找不到 bubblewrap，本次命令由外围 Landlock 执行隔离。
- 本次验证的是 App Server 启动、命令执行和文件边界。模型回合、登录、任务取消及正式 App 集成待验证。

## 复测

macOS 需要 Android SDK Platform 35、Build Tools 36.1.0、NDK 28.2.13676358、JDK、zstd 和 Android 调试签名。Pi 的 Node 压缩包沿用项目缓存，如尚未准备，可按架构稿中的 Pi 构建命令准备。下载 [Codex 0.165.0 ARM64 musl 发布包](https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-aarch64-unknown-linux-musl.zst)。构建脚本检查 Codex 和 Node 两个压缩包的 SHA-256。

```sh
bash tools/codex-phone-probe/build.sh ./codex-aarch64-unknown-linux-musl.zst /tmp/codex-phone-probe.apk
adb install --no-incremental -r /tmp/codex-phone-probe.apk
adb shell run-as dev.soundsoftware.codexprobe rm -f files/probe-result.txt
adb shell am start -S -n dev.soundsoftware.codexprobe/.ProbeActivity
adb shell run-as dev.soundsoftware.codexprobe cat files/probe-result.txt
```

点击手机上的 **Sound Codex Probe** 也可运行。等待页面出现 `PASS appUid=…`。实测签名 APK 约 139 MiB。
