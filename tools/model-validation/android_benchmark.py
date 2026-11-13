#!/usr/bin/env python3
"""Run already-staged public ASR samples via adb; does not install/start an App."""
import argparse
import json
import re
import shlex
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from benchmark import read_manifest, score, sha256, write_json


def shell(command, timeout=180):
    return subprocess.run(["adb", "shell", command], text=True, capture_output=True, timeout=timeout)


def parse_output(text):
    outputs = [json.loads(line) for line in text.splitlines() if line.startswith('{"lang"')]
    if len(outputs) != 1:
        raise ValueError("Expected exactly one sherpa JSON result")
    rtf = re.search(r"Real time factor \(RTF\): ([\d.]+) / ([\d.]+) = ([\d.]+)", text)
    load = re.search(r"recognizer created in ([\d.]+) s", text)
    if rtf is None or load is None:
        raise ValueError("Missing recognizer timing fields")
    elapsed, duration, reported = map(float, rtf.groups())
    rss = re.search(r"Max RSS \(KiB\): (\d+)", text)
    return {"text": outputs[0]["text"], "recognizer_seconds": elapsed,
            "duration_seconds": duration, "recognizer_rtf": elapsed / duration,
            "cli_reported_rtf": reported, "model_load_seconds": float(load.group(1)),
            "peak_process_rss_bytes": int(rss.group(1)) * 1024 if rss else None}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", choices=["sensevoice", "qwen3"], required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--remote-root", default="/data/local/tmp/soundsoftware-model-validation")
    p.add_argument("--repeat", type=int, default=3)
    p.add_argument("--measure-memory", action="store_true", help="Wrap CLI with Android toybox time -v")
    args = p.parse_args()
    if not 1 <= args.repeat <= 10:
        p.error("repeat must be 1..10")
    root = args.remote_root.rstrip("/")
    model = root + "/" + args.model
    command = [root + "/bin/sherpa-onnx-offline-static", "--num-threads=2", "--provider=cpu"]
    if args.model == "sensevoice":
        command += [f"--sense-voice-model={model}/model.int8.onnx", f"--tokens={model}/tokens.txt", "--sense-voice-use-itn=true"]
    else:
        command += [f"--qwen3-asr-conv-frontend={model}/conv_frontend.onnx",
                    f"--qwen3-asr-encoder={model}/encoder.int8.onnx",
                    f"--qwen3-asr-decoder={model}/decoder.int8.onnx",
                    f"--qwen3-asr-tokenizer={model}/tokenizer", "--qwen3-asr-max-new-tokens=256",
                    "--qwen3-asr-max-total-len=512", "--feat-dim=128"]
    properties = {}
    for key in ["ro.product.model", "ro.soc.model", "ro.board.platform", "ro.build.version.sdk", "ro.product.cpu.abi"]:
        result = shell("getprop " + key)
        if result.returncode:
            raise RuntimeError("ADB device unavailable")
        properties[key] = result.stdout.strip()
    doc = {"schema_version": 1, "status": "running", "model": args.model,
           "scope": "android_cli_cpu_public_short_audio_smoke", "android_validated": False,
           "npu_validated": False, "provider_requested": "cpu", "threads": 2,
           "started_at": datetime.now(timezone.utc).isoformat(), "device": properties,
           "samples": [], "limitations": ["CLI process, not application lifecycle or App PSS.",
           "Each repetition launches a new process; OS page caches are not cleared.",
           "CLI timings round to milliseconds, exclude model loading; host wall includes adb and process startup.",
           "No long meeting, VAD, diarization, thermal or energy acceptance."]}
    write_json(args.output, doc)
    try:
        for sample in read_manifest(args.manifest):
            # Only the two pinned public source model archives have staged sample layouts.
            source = sample["source"]
            folder = "sensevoice" if "sherpa-onnx-sense-voice-" in source else "qwen3" if "sherpa-onnx-qwen3-asr-" in source else None
            if folder is None:
                raise ValueError("Android smoke runner accepts only pinned public source archives")
            local = Path(sample["resolved_path"])
            if sha256(local) != sample["sha256"]:
                raise ValueError("Local sample hash mismatch")
            remote = root + "/" + folder + "/test_wavs/" + local.name
            remote_hash = shell("sha256sum " + shlex.quote(remote)).stdout.split()[0]
            if remote_hash != sample["sha256"]:
                raise ValueError("Staged device sample hash mismatch")
            measured = (["/system/bin/toybox", "time", "-v"] if args.measure_memory else []) + command + [remote]
            invocation = "LD_LIBRARY_PATH=" + shlex.quote(root + "/lib") + " " + shlex.join(measured)
            for repetition in range(args.repeat):
                t = time.perf_counter()
                result = shell(invocation)
                host_wall = time.perf_counter() - t
                if result.returncode:
                    raise RuntimeError((result.stdout + result.stderr)[-4000:])
                item = parse_output(result.stdout + result.stderr)
                item.update(id=sample["id"], repetition=repetition, sha256=sample["sha256"],
                            source=source, scope=sample["scope"], command=invocation,
                            host_wall_seconds=host_wall,
                            score=score(sample.get("reference"), item["text"], sample.get("metric", "mer")))
                doc["samples"].append(item)
                write_json(args.output, doc)
                print(json.dumps({k: item[k] for k in ["id", "repetition", "recognizer_rtf", "text"]}, ensure_ascii=False), flush=True)
        doc["status"] = "passed_execution"
        doc["android_validated"] = True
        code = 0
    except Exception as exc:
        doc["status"] = "failed"
        doc["error"] = {"type": type(exc).__name__, "message": str(exc)}
        code = 1
    doc["finished_at"] = datetime.now(timezone.utc).isoformat()
    write_json(args.output, doc)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
