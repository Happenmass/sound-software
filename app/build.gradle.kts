plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}
val includeSpeakerWorker = providers.gradleProperty("includeSpeakerWorker").getOrElse("false").toBoolean()
val includeSpeakerNpu = providers.gradleProperty("includeSpeakerNpu").getOrElse("false").toBoolean()
val includeQwenHtp = providers.gradleProperty("includeQwenHtp").getOrElse("false").toBoolean()
val includePi = providers.gradleProperty("includePi").getOrElse("false").toBoolean()
val preparePi by tasks.registering(Exec::class) {
    val source = rootProject.file("tools/pi-agent")
    val sdk = android.sdkDirectory
    val jni = layout.buildDirectory.dir("generated/piJni")
    val assets = layout.buildDirectory.dir("generated/piAssets")
    inputs.files(fileTree(source) { include("*.mjs", "*.json", "*.cc", "*.py") })
    outputs.dirs(jni, assets)
    commandLine("python3", source.resolve("build.py"), "--ndk", sdk.resolve("ndk/28.2.13676358"),
        "--jni", jni.get().asFile, "--assets", assets.get().asFile)
}
val includeAsrWeights = providers.gradleProperty("includeAsrWeights").getOrElse("false").toBoolean()
val includeSpeakerWeights = providers.gradleProperty("includeSpeakerWeights").getOrElse(includeAsrWeights.toString()).toBoolean()
val prepareSpeakerWeights by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/bundledSpeakerAssets")
    val cache = file(System.getProperty("user.home") + "/.cache/codex/models")
    inputs.files(rootProject.file("tools/prepare-bundled-speaker.py"),
        rootProject.file("tools/speaker-npu-runtime-lock.json"))
    inputs.dir(rootProject.file("tools/diarization-worker/model-notices"))
    inputs.files(cache.resolve("sherpa-onnx/sherpa-onnx-pyannote-segmentation-3-0/model.onnx"),
        cache.resolve("sherpa-onnx/3dspeaker_eres2netv2_zh.onnx"))
    if (includeSpeakerNpu) inputs.files(cache.resolve("qualcomm/speaker-eres2netv2-qnn250/eres2netv2-masked.bin"),
        cache.resolve("qualcomm/speaker-eres2netv2-qnn250/embedding-head.onnx"))
    inputs.property("includeHtp", includeSpeakerNpu)
    outputs.dir(output)
    val args = listOf("python3", rootProject.file("tools/prepare-bundled-speaker.py").path,
        "--output", output.get().asFile.path)
    commandLine(if (includeSpeakerNpu) args + "--include-htp" else args)
}
val prepareAsrWeights by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/bundledAsrAssets")
    inputs.files(rootProject.file("tools/prepare-bundled-asr.py"), rootProject.file("tools/qwen-npu-worker/qnn24/models.json"))
    outputs.dir(output)
    commandLine("python3", rootProject.file("tools/prepare-bundled-asr.py"), "--output", output.get().asFile)
}
val prepareQwenHtp by tasks.registering(Exec::class) {
    val worker = rootProject.file("tools/qwen-npu-worker/qnn24")
    val jni = layout.buildDirectory.dir("generated/qwenHtpJni")
    val assets = layout.buildDirectory.dir("generated/qwenHtpAssets")
    inputs.files(worker.listFiles()?.filter { it.extension in setOf("cc", "hpp", "json", "py") }.orEmpty())
    inputs.files(rootProject.file("tools/speaker-npu-runtime-lock.json"), rootProject.file("tools/prepare-qnn-notices.py"))
    outputs.dirs(jni, assets)
    commandLine("python3", worker.resolve("build.py"), "--jni", jni.get().asFile, "--assets", assets.get().asFile)
}
val prepareSpeakerNpu by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare-speaker-npu-runtime.py"),rootProject.file("tools/speaker-npu-runtime-lock.json"))
    inputs.files(rootProject.fileTree("tools/diarization-worker") { include("*.cc","*.h","*.py") })
    outputs.dir(layout.buildDirectory.dir("generated/speakerNpuJni"))
    outputs.dir(layout.buildDirectory.dir("generated/speakerNpuAssets"))
    commandLine("python3",rootProject.file("tools/prepare-speaker-npu-runtime.py"),"--jni",layout.buildDirectory.dir("generated/speakerNpuJni").get().asFile,"--assets",layout.buildDirectory.dir("generated/speakerNpuAssets").get().asFile)
}
val prepareSpeakerNative by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare-speaker-runtime.py"),rootProject.file("tools/speaker-runtime-lock.json"),rootProject.file("tools/diarization-worker/build.py"),rootProject.file("tools/diarization-worker/main.cc"),rootProject.file("tools/diarization-worker/parallel.h"))
    outputs.dir(layout.buildDirectory.dir("generated/speakerJni"))
    commandLine("python3",rootProject.file("tools/prepare-speaker-runtime.py"),"--output",layout.buildDirectory.dir("generated/speakerJni").get().asFile)
}
fun registerQnnNotices(taskName: String, sdk: String, cachePath: String, lockName: String) =
    tasks.register<Exec>(taskName) {
        val output = layout.buildDirectory.dir("generated/$taskName")
        val sdkNotices = file(System.getProperty("user.home") + "/.cache/codex/dependencies/qairt/$cachePath")
        inputs.files(rootProject.file("tools/prepare-qnn-notices.py"), rootProject.file("tools/$lockName"))
        inputs.files(sdkNotices.resolve("LICENSE.pdf"), sdkNotices.resolve("NOTICE.txt"), sdkNotices.resolve("QNN_NOTICE.txt"))
        outputs.dir(output)
        commandLine("python3", rootProject.file("tools/prepare-qnn-notices.py"), "--sdk", sdk, "--output", output.get().asFile)
    }
val prepareSpeakerNpuNotices = registerQnnNotices("speakerNpuNotices", "2.50",
    "2.50.0/sdk/qairt/2.50.0.260828", "speaker-npu-runtime-lock.json")
android {
    namespace = "dev.soundsoftware.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.soundsoftware.app"
        minSdk = 26
        targetSdk = 35
        buildConfigField("boolean", "SPEAKER_WORKER_ENABLED", includeSpeakerWorker.toString())
        buildConfigField("boolean", "SPEAKER_NPU_ENABLED", includeSpeakerNpu.toString())
        buildConfigField("boolean", "QWEN_HTP_ENABLED", includeQwenHtp.toString())
        buildConfigField("boolean", "PI_ENABLED", includePi.toString())
        buildConfigField("boolean", "ASR_WEIGHTS_BUNDLED", includeAsrWeights.toString())
        buildConfigField("boolean", "SPEAKER_WEIGHTS_BUNDLED", includeSpeakerWeights.toString())
        versionCode = 12
        versionName = "0.1.10"
        testInstrumentationRunner = "dev.soundsoftware.app.AssistantTestRunner"
        // Target devices are 64-bit ARM (Snapdragon 8 class); drops ~100 MB of unused native libraries.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            applicationIdSuffix = ".release"
            versionNameSuffix = ""
            isDebuggable = false
        }
        debug {
            versionNameSuffix = "-dev"
            applicationIdSuffix = providers.gradleProperty("debugApplicationIdSuffix").orNull
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    if (includeAsrWeights) androidResources.noCompress += listOf("bin", "onnx")
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libsound_qwen_htp_worker.so", "**/libsound_speaker_npu_worker.so", "**/libQnn*.so")
    }
    if (includeAsrWeights) sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundledAsrAssets"))
    if (includeSpeakerWeights) sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundledSpeakerAssets"))
    if (includeQwenHtp) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/qwenHtpJni"))
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/qwenHtpAssets"))
    }
    if (includePi) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/piJni"))
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/piAssets"))
        packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    }
    if (includeSpeakerWorker) sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/speakerJni"))
    if (includeSpeakerNpu) {
        if (!includeQwenHtp) sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/speakerNpuNotices"))
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/speakerNpuJni"))
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/speakerNpuAssets"))
    }
}
dependencies {
    implementation(project(":core"))
    implementation(files(providers.gradleProperty("sherpaAar").getOrElse(System.getProperty("user.home") + "/.cache/codex/dependencies/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar")))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

if (includeSpeakerWorker) tasks.named("preBuild").configure { dependsOn(prepareSpeakerNative) }

if (includeSpeakerNpu) tasks.named("preBuild").configure { dependsOn(prepareSpeakerNpu, prepareSpeakerNpuNotices) }

if (includeQwenHtp) tasks.named("preBuild").configure { dependsOn(prepareQwenHtp) }
if (includePi) tasks.named("preBuild").configure { dependsOn(preparePi) }

if (includeAsrWeights) {
    require(includeQwenHtp) { "Bundled ASR weights require the Qwen NPU engine." }
    tasks.named("preBuild").configure { dependsOn(prepareAsrWeights) }
}

if (includeSpeakerWeights) tasks.named("preBuild").configure { dependsOn(prepareSpeakerWeights) }
