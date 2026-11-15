# 本机常用版构建

Magic V6 日常使用的安装包沿用公开版 `dev.soundsoftware.app.release` 和原发布证书。先在发布分支构建 `release`，并核对 `versionCode` 不低于手机上已安装的版本。尚未公开的候选包可以沿用即将发布的版本号。

```sh
./gradlew :app:assembleRelease -PincludeQwenHtp=true -PincludeSpeakerNpu=true \
  -PincludeAsrWeights=true -PincludePi=true -Dorg.gradle.jvmargs="-Xmx5g -Dfile.encoding=UTF-8"

bash tools/sign-phone-release.sh app/build/outputs/apk/release/app-release-unsigned.apk /tmp/SoundSoftware-phone.apk
adb -s SERIAL install -r /tmp/SoundSoftware-phone.apk
```

签名脚本从本机 Keychain 读取现有发布密钥的密码，检查 APK 包名、签名证书和对齐。模型和运行库按[模型配置](MODELS.md)准备。自动化和调试仍可使用独立 debug 包。安装到常用手机时使用签名后的 release 包，录音库与 GitHub 公开版共用同一份应用数据。
