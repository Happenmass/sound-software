# 说话人数回归

模型复用用户级全局缓存；测试音频、声纹向量只放用户缓存或模拟器私有验证目录，不提交到项目，不调用远端服务。

快速回归：

```sh
./gradlew :core:test --tests dev.soundsoftware.core.SpeakerClusteringTest
# 可选：已有本地特征时，在同一生产聚类实现上快速重放（JSON格式见SpeakerSampleRegressionTest）
DIARIZATION_FEATURE_FIXTURE=/absolute/private/features.json ./gradlew :core:test --tests dev.soundsoftware.core.SpeakerSampleRegressionTest --rerun-tasks
# 一次重放多个私有对照（macOS/Linux路径用冒号分隔；Windows使用分号）
DIARIZATION_FEATURE_FIXTURES=/private/one.json:/private/two.json:/private/three.json ./gradlew :core:test --tests dev.soundsoftware.core.SpeakerSampleRegressionTest --rerun-tasks
```

`SpeakerClusteringTest`覆盖1–4人自动计数、同人跨原始簇、短噪声不生成新人、未归属保留ASR窗口、重叠约束、干净时长、无有效声纹及跨全录音锚点预算。

实际Android JNI与完整本地流程（仅模拟器）：先构建/安装App与测试包，部署已有`diarization`和`sensevoice`模型，将明确授权的两人测试音频放到`/sdcard/Android/data/dev.soundsoftware.app/files/validation/diarization-user-sample.wav`。

```sh
adb -s emulator-5554 shell am instrument -w -e class 'dev.soundsoftware.app.inference.DiarizationRegressionTest#automaticSpeakerCountMatchesAuthorizedFixture' -e diarizationFixture two-speaker-user-sample dev.soundsoftware.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class 'dev.soundsoftware.app.inference.DiarizationRegressionTest#automaticLocalPipelinePersistsTwoSpeakers' -e diarizationPipeline true dev.soundsoftware.app.test/androidx.test.runner.AndroidJUnitRunner
```

第一条只跑生产分离器，第二条跑`Processor`自动分离→SenseVoice本地转写→说话人赋值→保存/重开，在独立目录和偏好内运行，不修改用户录音。报告写到同一私有validation目录，只有人数/时间/哈希/警告，不输出原文。完成后删除两人测试WAV；保留报告即可。此特定两人测试需显式opt-in，默认测试跳过，不把任意录音都断言为两人。

已知人数的native路径另需用已有标注样本检查；自动估计不能代替DER/JER。没有逐时间说话人标注时，只能报告人数与工程链路，不能声称识别准确率。

2026-11-09长录音专项：`LongRecordingClusteringProbeTest`在显式`clusterProbe=baseline`下只读提取当前三条已确认双人的长录音特征（需当时清单恰为3条多算人数的长录音），结果只导出人数/时间；声纹留在`files/cluster-probe-private`。`clusterProbe=apply`只用于本次已验证修复，要求先提供绑定WAV/特征hash及录音ID的私有manifest；先验证全部候选均通过，再通过生产`Processor.rediarize`提交，最新一条重新跑完整实际分离器，前两条重放已验证特征。不是通用批量改写工具，默认全部跳过。不得在进行中的生成任务或用户正常使用期间启动instrumentation；开始/结束会停止App进程，完成后恢复普通App并清理手机临时特征/manifest。见[验证记录](../../docs/validation/long-speaker-clustering-2026-11-09.md)。
