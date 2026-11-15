#!/usr/bin/env python3
"""Build the 24-second Qwen worker against the pinned QAIRT SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

source = Path(__file__).resolve().parent
tools = source.parent.parent
cache = Path.home() / ".cache/codex"
sdk = cache / "dependencies/qairt/2.50.0/sdk/qairt/2.50.0.260828"
runtime = json.loads((tools / "speaker-npu-runtime-lock.json").read_text())["libraries"]
models = json.loads((source / "models.json").read_text())["models"]


def build(jni, assets):
    android = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    compilers = android / "ndk/28.2.13676358/toolchains/llvm/prebuilt"
    compiler = next(compilers.glob("*/bin/aarch64-linux-android26-clang++"))
    jni = jni / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        compiler, "-O3", "-std=c++20", "-Wall", "-Wextra", "-static-libstdc++", "-s",
        "-I", sdk / "include/QNN", source / "main.cc", "-ldl",
        "-o", jni / "libsound_qwen_htp_worker.so",
    ], check=True)

    libraries = {}
    with zipfile.ZipFile(assets / "qwen-htp-runtime.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name, item in sorted(runtime.items()):
            path = cache / item["cachePath"]
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != item["sha256"]:
                    raise SystemExit(f"Unexpected QAIRT library checksum: {name}")
            archive.write(path, name)
            libraries[name] = path.stat().st_size

    subprocess.run([
        "python3", tools / "prepare-qnn-notices.py", "--sdk", "2.50", "--output", assets,
    ], check=True)
    manifest = {"models": {name: size for name, (size, _) in models.items()}, "libraries": libraries}
    (assets / "qwen-htp-manifest.json").write_text(json.dumps(manifest) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    build(args.jni, args.assets)
