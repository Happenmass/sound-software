#!/usr/bin/env python3
"""Reproducible short-file ASR smoke evaluation. Never downloads weights."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import re
import resource
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def score_tokens(text: str, mode: str) -> list[str]:
    # NFKC, casefold; punctuation separates Latin words. Numbers are not rewritten.
    text = unicodedata.normalize("NFKC", text).casefold()
    if mode == "cer":
        return [c for c in text if not c.isspace() and unicodedata.category(c)[0] not in "PS"]
    if mode == "wer":
        return re.findall(r"[^\W_]+(?:['’][^\W_]+)*", text, re.UNICODE)
    if mode == "mer":
        return re.findall(r"[\u3400-\u9fff]|[a-z0-9]+(?:['’][a-z0-9]+)*|[^\W_\u3400-\u9fff]+", text)
    raise ValueError(f"Unknown scoring mode: {mode}")


def edit_distance(reference: list[str], hypothesis: list[str]) -> int:
    row = list(range(len(hypothesis) + 1))
    for i, ref in enumerate(reference, 1):
        nxt = [i]
        for j, hyp in enumerate(hypothesis, 1):
            nxt.append(min(row[j] + 1, nxt[j - 1] + 1, row[j - 1] + (ref != hyp)))
        row = nxt
    return row[-1]


def score(reference: str | None, hypothesis: str, mode: str) -> dict | None:
    if reference is None:
        return None
    ref, hyp = score_tokens(reference, mode), score_tokens(hypothesis, mode)
    errors = edit_distance(ref, hyp)
    return {"metric": mode, "errors": errors, "reference_units": len(ref),
            "error_rate": errors / len(ref) if ref else None,
            "silence_insertions": len(hyp) if not ref else None}


def resolve_path(value: str, base: Path) -> Path:
    value = os.path.expandvars(os.path.expanduser(value))
    if "$" in value:
        raise ValueError("Unresolved environment variable in sample path")
    path = Path(value)
    return path if path.is_absolute() else base / path


def read_manifest(path: Path) -> list[dict]:
    doc = json.loads(path.read_text())
    if doc.get("schema_version") != 1 or not isinstance(doc.get("samples"), list):
        raise ValueError("Manifest must have schema_version=1 and samples array")
    if not doc["samples"]:
        raise ValueError("Manifest contains no samples")
    seen = set()
    for s in doc["samples"]:
        if not s.get("id") or s["id"] in seen:
            raise ValueError("Sample IDs must be nonempty and unique")
        seen.add(s["id"])
        if not s.get("source") or not s.get("scope"):
            raise ValueError("Sample source and scope are required")
        if s.get("metric", "mer") not in {"cer", "wer", "mer"}:
            raise ValueError("Unsupported metric")
        s["resolved_path"] = str(resolve_path(s["path"], path.parent).resolve())
    return doc["samples"]


def model_files(kind: str, directory: Path) -> list[Path]:
    names = (["model.int8.onnx", "tokens.txt"] if kind == "sensevoice" else
             ["conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
              "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json"])
    paths = [directory / n for n in names]
    for p in paths:
        if not p.is_file():
            raise FileNotFoundError(f"Required model artifact missing: {p}")
    return paths


def make_recognizer(kind: str, directory: Path, threads: int):
    import sherpa_onnx
    if kind == "sensevoice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(directory / "model.int8.onnx"), tokens=str(directory / "tokens.txt"),
            num_threads=threads, provider="cpu", language="auto", use_itn=True)
    method = getattr(sherpa_onnx.OfflineRecognizer, "from_qwen3_asr", None)
    if method is None:
        raise RuntimeError("Installed sherpa-onnx lacks Qwen3-ASR API; use pinned requirements")
    return method(conv_frontend=str(directory / "conv_frontend.onnx"),
                  encoder=str(directory / "encoder.int8.onnx"),
                  decoder=str(directory / "decoder.int8.onnx"),
                  tokenizer=str(directory / "tokenizer"), num_threads=threads,
                  provider="cpu", max_total_len=512, max_new_tokens=256)


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    temp.replace(path)


def run(args) -> int:
    result = {"schema_version": 1, "status": "running",
              "started_at": datetime.now(timezone.utc).isoformat(),
              "scope": "desktop_cpu_short_audio_smoke",
              "android_validated": False, "npu_validated": False,
              "platform": {"system": platform.system(), "release": platform.release(),
                           "machine": platform.machine(), "python": platform.python_version()},
              "model": args.model, "provider_requested": "cpu", "threads": args.threads,
              "normalization": "v1:NFKC/casefold/punctuation separators/no number rewriting",
              "samples": [], "limitations": ["Short-file smoke only; no VAD or diarization.",
              "Desktop timings do not predict phone throughput, energy or memory.",
              "Public examples are not held-out D3200 meetings."]}
    write_json(args.output, result)
    try:
        import soundfile as sf
        import numpy as np
        result["packages"] = {n: importlib.metadata.version(n) for n in ["sherpa-onnx", "numpy", "soundfile"]}
        samples = read_manifest(args.manifest)
        artifacts = model_files(args.model, args.model_dir)
        result["model_artifacts"] = [{"name": str(p.relative_to(args.model_dir)),
                                     "bytes": p.stat().st_size, "sha256": sha256(p)} for p in artifacts]
        t = time.perf_counter()
        recognizer = make_recognizer(args.model, args.model_dir, args.threads)
        result["model_load_seconds"] = time.perf_counter() - t
        for sample in samples:
            path = Path(sample["resolved_path"])
            digest = sha256(path)
            if sample.get("sha256") and sample["sha256"] != digest:
                raise ValueError(f"Sample hash mismatch: {sample['id']}")
            samples_float, sample_rate = sf.read(path, dtype="float32", always_2d=True)
            if sample_rate != 16000 or samples_float.shape[1] != 1:
                raise ValueError(f"Expected 16kHz mono input: {sample['id']}")
            samples_float = samples_float[:, 0]
            duration = len(samples_float) / sample_rate
            if not 0 < duration <= args.max_seconds or not np.isfinite(samples_float).all():
                raise ValueError(f"Invalid/too long smoke input: {sample['id']} ({duration}s)")
            for repetition in range(args.repeat):
                t = time.perf_counter()
                stream = recognizer.create_stream()
                stream.accept_waveform(sample_rate, samples_float)
                recognizer.decode_stream(stream)
                text = stream.result.text
                elapsed = time.perf_counter() - t
                item = {"id": sample["id"], "repetition": repetition,
                        "source": sample["source"], "scope": sample["scope"], "sha256": digest,
                        "duration_seconds": duration, "recognizer_seconds": elapsed,
                        "recognizer_rtf": elapsed / duration, "text": text,
                        "score": score(sample.get("reference"), text, sample.get("metric", "mer"))}
                result["samples"].append(item)
                print(json.dumps({k: item[k] for k in ["id", "repetition", "recognizer_rtf", "text"]}, ensure_ascii=False), flush=True)
                write_json(args.output, result)
        result["status"] = "passed_execution"
        result["peak_process_rss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == "darwin" else 1024)
        result["rss_note"] = "Process lifetime high-water mark, not Android PSS or model-only memory"
        return_code = 0
    except Exception as exc:
        result["status"] = "failed"
        result["error"] = {"type": type(exc).__name__, "message": str(exc)}
        print(f"Failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return_code = 1
    result["finished_at"] = datetime.now(timezone.utc).isoformat()
    write_json(args.output, result)
    return return_code


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", choices=["qwen3", "sensevoice"], required=True)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--threads", type=int, default=2)
    p.add_argument("--repeat", type=int, default=3)
    p.add_argument("--max-seconds", type=float, default=30)
    args = p.parse_args()
    if args.threads < 1 or args.repeat < 1 or not 0 < args.max_seconds <= 30:
        p.error("threads/repeat must be positive; max-seconds must be within (0,30]")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
