#!/usr/bin/env python3
"""Fetch pinned public model artifacts into the global cache, never the repo."""
import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path


def prepare(spec, cache):
    archive = cache / spec["archive"]
    cache.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        partial = archive.with_suffix(archive.suffix + ".download")
        with urllib.request.urlopen(spec["url"], timeout=120) as response, partial.open("wb") as out:
            shutil.copyfileobj(response, out)
        partial.replace(archive)
    with archive.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    if archive.stat().st_size != spec["bytes"] or digest != spec["sha256"]:
        raise ValueError(f"Archive integrity check failed: {archive}; inspect/remove manually")
    # Re-extract the verified archive so interrupted extractions cannot look complete.
    with tarfile.open(archive) as bundle:
        bundle.extractall(cache, filter="data")
    print(json.dumps({"path": str(cache / spec["directory"]), "sha256": digest}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="+", choices=["sensevoice", "qwen3", "sensevoice-qnn-sm8850"])
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/codex/models/sherpa-onnx")
    args = parser.parse_args()
    cache = args.cache.expanduser().resolve()
    repo = Path(__file__).resolve().parents[2]
    if cache == repo or repo in cache.parents:
        parser.error("Model cache must be outside the repository")
    specs = json.loads(Path(__file__).with_name("artifacts.json").read_text())
    for model in args.models:
        prepare(specs[model], cache)


if __name__ == "__main__":
    main()
