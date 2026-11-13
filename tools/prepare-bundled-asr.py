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
sensevoice = json.loads((root / "model-validation/artifacts.json").read_text())["sensevoice-qnn-sm8850"]
models = {
    "qwen-htp-sm8850": (cache / "models/qwen3-asr-onspeak47-sm8850/v2.2.0-npu", qwen),
    "sensevoice-qnn-sm8850": (cache / "models/sherpa-onnx" / sensevoice["directory"], {
        "model.bin": [254193664, "522763135f536bc43e9d122bac39254f49df4afbb003462d6d8bcdb77051de30"],
        "tokens.txt": [315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"],
    }),
}
notices = {
    "onspeak-LICENSE-Qwen3-ASR.txt": (
        "https://raw.githubusercontent.com/DS-PNQ/OnSpeak47-npu-models/v2.2.0-npu/LICENSE-Qwen3-ASR.txt",
        "bc71240a7c1262077bc0cbd2b98efbb05686bdaa74c15b48b8269f74b7a0fd48",
    ),
    "onspeak-NOTICE": (
        "https://raw.githubusercontent.com/DS-PNQ/OnSpeak47-npu-models/v2.2.0-npu/NOTICE",
        "a59415d19dd93119c987acd1478782236810e6e949d701b2bbb93c4b48518d71",
    ),
    "sensevoice-MODEL_LICENSE": (
        "https://raw.githubusercontent.com/modelscope/FunASR/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE",
        "7dba975a2069691db4992b0592d70828b330d2f8a30a71450f4e152a554e84f8",
    ),
}

def stage(output):
    for model, (source, entries) in models.items():
        target = output / "asr-models" / model
        target.mkdir(parents=True, exist_ok=True)
        for name, (size, digest) in entries.items():
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
    (licenses / "sensevoice-NOTICE").write_text(
        "SenseVoiceSmall by the FunAudioLLM team, Alibaba Group.\n"
        "https://huggingface.co/FunAudioLLM/SenseVoiceSmall\n"
        f"SM8850 QNN conversion distributed by sherpa-onnx: {sensevoice['url']}\n"
        "The model and its conversion are subject to the accompanying FunASR model license.\n"
    )
    manifest = {model: entries for model, (_, entries) in models.items()}
    (output / "asr-models/manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    stage(parser.parse_args().output)
