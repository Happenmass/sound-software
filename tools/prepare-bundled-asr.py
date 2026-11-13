#!/usr/bin/env python3
"""Stage the pinned ASR weights and notices from the shared model cache."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.request import urlopen

root = Path(__file__).resolve().parent
cache = Path.home() / ".cache/codex"
qwen = json.loads((root / "qwen-npu-worker/artifacts.json").read_text())["models"]
source = cache / "models/qwen3-asr-onspeak47-sm8850/v2.2.0-npu"
notices = {
    "onspeak-LICENSE-Qwen3-ASR.txt": (
        "https://raw.githubusercontent.com/DS-PNQ/OnSpeak47-npu-models/v2.2.0-npu/LICENSE-Qwen3-ASR.txt",
        "bc71240a7c1262077bc0cbd2b98efbb05686bdaa74c15b48b8269f74b7a0fd48",
    ),
    "onspeak-NOTICE": (
        "https://raw.githubusercontent.com/DS-PNQ/OnSpeak47-npu-models/v2.2.0-npu/NOTICE",
        "a59415d19dd93119c987acd1478782236810e6e949d701b2bbb93c4b48518d71",
    ),
}

def stage(output):
    if output.exists():
        shutil.rmtree(output)
    target = output / "asr-models/qwen-htp-sm8850"
    target.mkdir(parents=True)
    for name, (size, digest) in qwen.items():
        path = source / name
        if not path.is_file():
            raise SystemExit(f"Missing pinned model: {path}. See docs/MODELS.md.")
        with path.open("rb") as stream:
            if path.stat().st_size != size or hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                raise SystemExit(f"Model checksum mismatch: {path}")
        shutil.copyfile(path, target / name)
    licenses = output / "licenses/asr-models"
    licenses.mkdir(parents=True, exist_ok=True)
    for name, (url, digest) in notices.items():
        cached = cache / "dependencies/bundled-asr-model-notices" / name
        if cached.is_file():
            content = cached.read_bytes()
        else:
            with urlopen(url, timeout=30) as response:
                content = response.read()
        if hashlib.sha256(content).hexdigest() != digest:
            raise SystemExit(f"Notice checksum mismatch: {name}")
        (licenses / name).write_bytes(content)
    (output / "asr-models/manifest.json").write_text(
        json.dumps({"qwen-htp-sm8850": qwen}, sort_keys=True) + "\n"
    )

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    stage(parser.parse_args().output)
