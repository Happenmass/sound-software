#!/usr/bin/env python3
"""Build pinned sherpa 2.0.1 Android QNN CLI/JNI in global cache (Python 3.12)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ARTIFACTS = {
    "sherpa-onnx-v2.0.1.tar.gz": (
        "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v2.0.1.tar.gz",
        "24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7"),
    "qnn-include-2.40.0.251030.tar.bz2": (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-2.40.0.251030.tar.bz2",
        "fafe1c9dc3f60393134f22f2c708a9ede287492fa20dedc22c9fabdcf5ebe70d"),
    "onnxruntime-android-1.28.2.zip": (
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip",
        "01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d"),
}


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def fetch(cache, name, url, expected):
    path = cache / name
    if not path.exists():
        temp = path.with_suffix(path.suffix + ".download")
        with urllib.request.urlopen(url, timeout=180) as src, temp.open("wb") as dst:
            shutil.copyfileobj(src, dst)
        temp.replace(path)
    if digest(path) != expected:
        raise ValueError(f"SHA-256 mismatch: {path}; inspect before replacing")


def trace_patch(source):
    """Two explicit diagnostic-only additions; no inference/segmentation changes."""
    patches = [
        ("sherpa-onnx/csrc/qnn/qnn-model.cc",
         '    SHERPA_ONNX_QNN_CHECK(ret, "Failed to run graphExecute");',
         '    if (debug_) SHERPA_ONNX_LOGE("QNN_VALIDATION graphExecute succeeded");'),
        ("sherpa-onnx/csrc/lfr.cc",
         "  if (actual_output_frames > output_frames_size) {",
         '  SHERPA_ONNX_LOGE("QNN_VALIDATION fixed_lfr actual=%zu target=%zu", actual_output_frames, output_frames_size);'),
    ]
    for name, anchor, diagnostic in patches:
        path = source / name
        text = path.read_text()
        if diagnostic in text:
            continue
        if text.count(anchor) != 1:
            raise ValueError(f"Pinned source patch anchor changed: {name}")
        text = text.replace(anchor, anchor + "\n" + diagnostic) if "graphExecute" in diagnostic else text.replace(anchor, diagnostic + "\n" + anchor)
        path.write_text(text)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cache", type=Path, default=Path.home() / ".cache/codex/qnn-validation")
    p.add_argument("--ndk", type=Path, default=Path.home() / "Library/Android/sdk/ndk/28.2.13676358")
    p.add_argument("--cmake-bin", type=Path, default=Path.home() / "Library/Android/sdk/cmake/3.31.6/bin")
    p.add_argument("--jobs", type=int, default=6)
    args = p.parse_args()
    cache = args.cache.expanduser().resolve()
    repo = Path(__file__).resolve().parents[2]
    if cache == repo or repo in cache.parents:
        p.error("Build/cache must be outside repository")
    if not 1 <= args.jobs <= 16:
        p.error("jobs must be 1..16")
    cache.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in ARTIFACTS.items():
        fetch(cache, name, url, expected)
    source = cache / "sherpa-onnx-2.0.1"
    qnn = cache / "qnn-include-2.40.0.251030"
    for archive, directory in [("sherpa-onnx-v2.0.1.tar.gz", source), ("qnn-include-2.40.0.251030.tar.bz2", qnn)]:
        if not directory.exists():
            with tarfile.open(cache / archive) as bundle:
                bundle.extractall(cache, filter="data")
    ort = cache / "onnxruntime-1.28.2"
    if not (ort / "jni/arm64-v8a/libonnxruntime.so").is_file():
        with zipfile.ZipFile(cache / "onnxruntime-android-1.28.2.zip") as bundle:
            for entry in bundle.infolist():
                if entry.filename.startswith(("headers/", "jni/arm64-v8a/")):
                    bundle.extract(entry, ort)
    trace_patch(source)
    # The app entry point is owned by this repo, copied only to the global build cache.
    worker_source=repo / "tools/qnn-worker/main.cc"
    shutil.copyfile(worker_source,source / "sherpa-onnx/csrc/sound-qnn-worker.cc")
    cmake_file=source / "sherpa-onnx/csrc/CMakeLists.txt"
    worker_target="add_executable(sound-qnn-worker sound-qnn-worker.cc)\ntarget_link_libraries(sound-qnn-worker sherpa-onnx-core)\n"
    if worker_target not in cmake_file.read_text():
        cmake_file.write_text(cmake_file.read_text()+"\n"+worker_target)
    # Generic graph compatibility probe is test-only; never substituted for ASR/diarization.
    shutil.copyfile(repo / "tools/diarization-worker/qnn-probe.cc",source / "sherpa-onnx/csrc/sound-speaker-qnn-probe.cc")
    probe_target='add_executable(sound-speaker-qnn-probe sound-speaker-qnn-probe.cc)\ntarget_link_libraries(sound-speaker-qnn-probe sherpa-onnx-core)\n'
    if probe_target not in cmake_file.read_text():
        cmake_file.write_text(cmake_file.read_text()+"\n"+probe_target)
    probe_include='target_include_directories(sound-speaker-qnn-probe PRIVATE "$ENV{QNN_SDK_ROOT}/include/QNN")'
    if probe_include not in cmake_file.read_text():
        cmake_file.write_text(cmake_file.read_text()+"\n"+probe_include+"\n")
    env = dict(os.environ, QNN_SDK_ROOT=str(qnn),
               SHERPA_ONNXRUNTIME_LIB_DIR=str(ort / "jni/arm64-v8a"),
               SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort / "headers"))
    cmake = str(args.cmake_bin / "cmake")
    build = cache / "build-arm64"
    opts = dict(CMAKE_BUILD_TYPE="Release", BUILD_SHARED_LIBS="ON", ANDROID_ABI="arm64-v8a",
                ANDROID_PLATFORM="android-28", SHERPA_ONNX_ENABLE_QNN="ON",
                SHERPA_ONNX_ENABLE_TTS="OFF", SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION="OFF",
                SHERPA_ONNX_ENABLE_BINARY="ON", SHERPA_ONNX_ENABLE_C_API="ON",
                SHERPA_ONNX_BUILD_C_API_EXAMPLES="OFF", SHERPA_ONNX_ENABLE_JNI="ON",
                SHERPA_ONNX_ENABLE_PYTHON="OFF", SHERPA_ONNX_ENABLE_TESTS="OFF",
                SHERPA_ONNX_ENABLE_CHECK="OFF", SHERPA_ONNX_ENABLE_PORTAUDIO="OFF",
                SHERPA_ONNX_ENABLE_WEBSOCKET="OFF", SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY="OFF",
                CMAKE_MAKE_PROGRAM=str(args.cmake_bin / "ninja"),
                CMAKE_TOOLCHAIN_FILE=str(args.ndk / "build/cmake/android.toolchain.cmake"))
    configure = [cmake, "-S", str(source), "-B", str(build), "-G", "Ninja"] + [f"-D{k}={v}" for k, v in opts.items()]
    compile_command = [cmake, "--build", str(build), "--target", "sherpa-onnx-offline", "sherpa-onnx-jni", "sound-qnn-worker", "sound-speaker-qnn-probe", "-j", str(args.jobs)]
    (cache / "build-commands.json").write_text(json.dumps({"configure": configure, "build": compile_command,
        "diagnostic_patch": "graphExecute success + fixed-LFR frame counts only; no model logic changes",
        "workerSourceSha256": digest(worker_source),
        "archives": {n: digest(cache / n) for n in ARTIFACTS}}, indent=2) + "\n")
    for command, filename in [(configure, "configure.log"), (compile_command, "build.log")]:
        print(f"Running {filename}; full log: {cache / filename}", flush=True)
        with (cache / filename).open("w") as log:
            subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    staged = cache / "staged"
    staged.mkdir(exist_ok=True)
    for src, name in [(build / "bin/sound-speaker-qnn-probe", "sound-speaker-qnn-probe"),
                      (build / "bin/sound-qnn-worker", "sound-qnn-worker"),
                      (build / "bin/sherpa-onnx-offline", "sherpa-onnx-offline-qnn"),
                      (build / "lib/libsherpa-onnx-jni.so", "libsherpa-onnx-jni.so"),
                      (ort / "jni/arm64-v8a/libonnxruntime.so", "libonnxruntime.so")]:
        shutil.copy2(src, staged / name)
    strip = args.ndk / "toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
    subprocess.run([str(strip), str(staged / "sherpa-onnx-offline-qnn"), str(staged / "libsherpa-onnx-jni.so"), str(staged / "sound-qnn-worker"),str(staged / "sound-speaker-qnn-probe")], check=True)
    print(staged)


if __name__ == "__main__":
    main()
