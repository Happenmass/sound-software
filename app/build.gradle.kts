plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}
val includeQnn = providers.gradleProperty("includeQnn").getOrElse("false").toBoolean()
val includeSpeakerWorker = providers.gradleProperty("includeSpeakerWorker").getOrElse(includeQnn.toString()).toBoolean()
val includeSpeakerNpu = providers.gradleProperty("includeSpeakerNpu").getOrElse((includeQnn && includeSpeakerWorker).toString()).toBoolean()
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
val prepareSpeakerProbe by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare-speaker-runtime.py"),rootProject.file("tools/speaker-runtime-lock.json"),rootProject.file("tools/diarization-worker/qnn-probe.cc"),rootProject.file("tools/model-validation/qnn_build.py"))
    outputs.dir(layout.buildDirectory.dir("generated/speakerProbeJni"))
    commandLine("python3",rootProject.file("tools/prepare-speaker-runtime.py"),"--probe","--output",layout.buildDirectory.dir("generated/speakerProbeJni").get().asFile)
}
val prepareQnnNative by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare-qnn-runtime.py"), rootProject.file("tools/qnn-runtime-lock.json"),rootProject.file("tools/qnn-worker/main.cc"))
    outputs.dir(layout.buildDirectory.dir("generated/qnnJni"))
    commandLine("python3",rootProject.file("tools/prepare-qnn-runtime.py"),"--output",layout.buildDirectory.dir("generated/qnnJni").get().asFile)
}
android {
    namespace = "dev.soundsoftware.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.soundsoftware.app"
        minSdk = 26
        targetSdk = 35
        buildConfigField("boolean", "QNN_ENABLED", includeQnn.toString())
        buildConfigField("boolean", "SPEAKER_WORKER_ENABLED", includeSpeakerWorker.toString())
        buildConfigField("boolean", "SPEAKER_NPU_ENABLED", includeSpeakerNpu.toString())
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Target devices are 64-bit ARM (Snapdragon 8 class); drops ~100 MB of unused native libraries.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            applicationIdSuffix = ".release"
            versionNameSuffix = ""
            isDebuggable = false
        }
        debug { versionNameSuffix = "-dev" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libsound_qnn_runner.so", "**/libQnn*.so")
    }
    if (includeQnn) sourceSets.getByName("androidTest").jniLibs.srcDir(layout.buildDirectory.dir("generated/speakerProbeJni"))
    if (includeSpeakerWorker) sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/speakerJni"))
    if (includeSpeakerNpu) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/speakerNpuJni"))
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/speakerNpuAssets"))
    }
    if (includeQnn) sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/qnnJni"))
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
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

if (includeQnn) tasks.named("preBuild").configure { dependsOn(prepareQnnNative) }

if (includeSpeakerWorker) tasks.named("preBuild").configure { dependsOn(prepareSpeakerNative) }
if (includeQnn) tasks.matching { it.name == "mergeDebugAndroidTestJniLibFolders" }.configureEach { dependsOn(prepareSpeakerProbe) }

if (includeSpeakerNpu) tasks.named("preBuild").configure { dependsOn(prepareSpeakerNpu) }
