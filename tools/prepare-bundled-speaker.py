#!/usr/bin/env python3
"""Stage verified speaker model assets from the shared cache."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path.home() / ".cache/codex/models"
CPU = CACHE / "sherpa-onnx"
HTP = CACHE / "qualcomm/speaker-eres2netv2-qnn250"


def stage(output, include_htp):
    lock = json.loads((ROOT / "tools/speaker-npu-runtime-lock.json").read_text())
    cpu = {
        "segmentation.onnx": (CPU / "sherpa-onnx-pyannote-segmentation-3-0/model.onnx", 5992913),
        "embedding.onnx": (CPU / "3dspeaker_eres2netv2_zh.onnx", 71441526),
    }
    groups = {
        "diarization": {
            name: (path, size, lock["originalModels"][name])
            for name, (path, size) in cpu.items()
        }
    }
    if include_htp:
        groups["diarization-npu"] = {
            name: (HTP / name, info["bytes"], info["sha256"])
            for name, info in lock["models"].items()
        }
    if output.exists():
        shutil.rmtree(output)
    assets = output / "speaker-models"
    manifest = {}
    for group, files in groups.items():
        target = assets / group
        target.mkdir(parents=True)
        manifest[group] = {}
        for name, (source, size, digest) in files.items():
            if not source.is_file():
                raise SystemExit(f"Missing pinned model: {source}. See docs/MODELS.md.")
            with source.open("rb") as stream:
                if source.stat().st_size != size or hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                    raise SystemExit(f"Model checksum mismatch: {source}")
            shutil.copyfile(source, target / name)
            manifest[group][name] = [size, digest]
    (assets / "manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")
    licenses = output / "licenses/speaker-models"
    shutil.copytree(ROOT / "tools/diarization-worker/model-notices", licenses)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-htp", action="store_true")
    args = parser.parse_args()
    stage(args.output, args.include_htp)
