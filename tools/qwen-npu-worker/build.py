#!/usr/bin/env python3
"""Build an isolated Qwen HTP worker and package the unmodified runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

source = Path(__file__).resolve().parent
lock = json.loads((source / "artifacts.json").read_text())
cache = Path.home() / ".cache/codex/dependencies/qwen-asr-htp/2.51.0"

def fetch(name):
    url, expected = lock["runtime"][name]
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / name
    if not output.is_file():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp:
            staging = Path(temp.name)
        try:
            with urllib.request.urlopen(url, timeout=45) as response, staging.open("wb") as dest:
                shutil.copyfileobj(response, dest)
            verify(staging, expected)
            staging.replace(output)
        finally:
            staging.unlink(missing_ok=True)
    verify(output, expected)
    return output

def verify(path, expected):
    with path.open("rb") as f:
        actual = hashlib.file_digest(f, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"Unexpected SHA-256 for {path}")

def extract(archive, entry, dest):
    with archive.open(entry) as src, dest.open("wb") as out:
        shutil.copyfileobj(src, out)

def build(jni, assets):
    sdk = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    toolchains = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt"
    compiler = next(toolchains.glob("*/bin/aarch64-linux-android26-clang"))
    jni = jni / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    notices = assets / "licenses/qwen-npu"
    notices.mkdir(parents=True, exist_ok=True)
    for name in lock["runtime"]:
        if name.endswith((".txt", "-LICENSE")):
            shutil.copyfile(fetch(name), notices / name)

    with tempfile.TemporaryDirectory(prefix="qwen-htp-", dir=cache) as temp:
        work = Path(temp)
        include = work / "include"
        include.mkdir()
        runtime = work / "runtime"
        runtime.mkdir()
        jars = {
            "onnxruntime.aar": ["libonnxruntime.so"],
            "onnxruntime-qnn.aar": ["libonnxruntime_providers_qnn.so"],
            "qnn-runtime.aar": ["libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so",
                                "libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so"],
        }
        for name, libraries in jars.items():
            with zipfile.ZipFile(fetch(name)) as archive:
                for library in libraries:
                    extract(archive, f"jni/arm64-v8a/{library}", runtime / library)
                if name == "onnxruntime.aar":
                    for entry in archive.namelist():
                        if entry.startswith("headers/") and entry.endswith(".h"):
                            extract(archive, entry, include / Path(entry).name)
                if name == "qnn-runtime.aar":
                    for entry in ("LICENSE.pdf", "NOTICE.txt"):
                        extract(archive, entry, notices / f"qairt-{entry}")

        with zipfile.ZipFile(fetch("kissfft.zip")) as archive:
            prefix = archive.namelist()[0].split("/")[0]
            for entry in ("kiss_fft.c", "kiss_fft.h", "kiss_fftr.c", "kiss_fftr.h", "_kiss_fft_guts.h", "kiss_fft_log.h"):
                extract(archive, f"{prefix}/{entry}", include / entry)
            for entry in ("COPYING", "LICENSES/BSD-3-Clause"):
                extract(archive, f"{prefix}/{entry}", notices / f"kissfft-{Path(entry).name}")

        objects = []
        for name in ("kiss_fft", "kiss_fftr"):
            obj = work / f"{name}.o"
            subprocess.run([compiler, "-O3", "-c", include / f"{name}.c", "-o", obj], check=True)
            objects.append(obj)
        worker = jni / "libsound_qwen_htp_worker.so"
        subprocess.run([str(compiler)+"++", "-O3", "-std=c++20", "-Wall", "-Wextra",
                        "-static-libstdc++", "-s", "-I", include, source / "main.cc",
                        *objects, "-L", runtime, "-lonnxruntime", "-o", worker], check=True)

        with zipfile.ZipFile(assets / "qwen-htp-runtime.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for library in sorted(runtime.iterdir()):
                archive.write(library, library.name)
        manifest = dict(models={name: size for name, (size, _) in lock["models"].items()},
                        libraries={lib.name: lib.stat().st_size for lib in sorted(runtime.iterdir())})
        (assets / "qwen-htp-manifest.json").write_text(json.dumps(manifest)+"\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    build(args.jni, args.assets)
