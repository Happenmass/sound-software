#!/usr/bin/env python3
"""Stage the pinned ASR weights and notices from the shared model cache."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

root = Path(__file__).resolve().parent
cache = Path.home() / ".cache/codex"
lock = json.loads((root / "qwen-npu-worker/qnn24/models.json").read_text())
qwen = lock["models"]
source = cache / "models" / lock["modelDirectory"]

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
    for name, digest in lock["notices"].items():
        content = (source / name).read_bytes()
        if hashlib.sha256(content).hexdigest() != digest:
            raise SystemExit(f"Notice checksum mismatch: {name}")
        (licenses / f"qwen3-asr-{name}").write_bytes(content)
    (output / "asr-models/manifest.json").write_text(
        json.dumps({"qwen-htp-sm8850": qwen}, sort_keys=True) + "\n"
    )

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    stage(parser.parse_args().output)
