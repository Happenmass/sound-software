#!/usr/bin/env python3
"""Deploy verified model files without modifying app settings or existing recordings."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

root = Path(__file__).resolve().parent
lock = json.loads((root / "qnn24/models.json").read_text())
models = lock["models"]
default = Path.home() / ".cache/codex/models" / lock["modelDirectory"]

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--package", default="dev.soundsoftware.app")
parser.add_argument("--model-dir", type=Path, default=default)
args = parser.parse_args()
if not re.fullmatch(r"[a-zA-Z0-9_.]+", args.package):
    parser.error("Invalid Android package")

adb = ["adb", "-s", args.serial]
def run(*command, **kwargs):
    return subprocess.run([*adb, *command], check=True, text=True, **kwargs)

for name, (size, digest) in models.items():
    file = args.model_dir / name
    with file.open("rb") as stream:
        if file.stat().st_size != size or hashlib.file_digest(stream, "sha256").hexdigest() != digest:
            raise ValueError(f"Invalid model file: {file}")

if not run("shell", "pm", "path", args.package, capture_output=True).stdout.startswith("package:"):
    raise SystemExit("Install the app on the selected device first.")

remote = f"/sdcard/Android/data/{args.package}/files/models"
target = f"{remote}/qwen-htp-sm8850"
staged = f"{target}.staged-{uuid.uuid4().hex}"

def matches(directory):
    result = subprocess.run([*adb, "shell", "sha256sum", *(f"{directory}/{name}" for name in models)],
                            text=True, capture_output=True)
    return result.returncode == 0 and [line.split()[0] for line in result.stdout.splitlines()] == [
        digest for _, digest in models.values()
    ]

if not matches(target):
    run("shell", "mkdir", "-p", staged)
    try:
        run("push", *(str(args.model_dir / name) for name in models), staged + "/")
        if not matches(staged):
            raise ValueError("Model transfer failed SHA-256 verification")
        previous = staged + "-previous"
        run("shell", f'if [ -e "{target}" ]; then mv "{target}" "{previous}" || exit 1; fi; '
            f'if mv "{staged}" "{target}"; then rm -rf "{previous}"; '
            f'else if [ -e "{previous}" ]; then mv "{previous}" "{target}"; fi; exit 1; fi')
    finally:
        run("shell", "rm", "-rf", staged)
print(f"Verified Qwen3-ASR NPU model for {args.package}.")
