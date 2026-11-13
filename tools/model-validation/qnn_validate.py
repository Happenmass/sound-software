#!/usr/bin/env python3
"""Validate SM8850 HTP execution, no fallback and fixed 10 s input boundaries."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

import numpy as np
import soundfile as sf
from android_benchmark import parse_output
from benchmark import sha256, write_json

RATE = 16000
MAX_SAMPLES = 10 * RATE


def require_window(count):
    if not 0 < count <= MAX_SAMPLES:
        raise ValueError(f"QNN 10-second graph requires 1..{MAX_SAMPLES} samples; got {count}")


def split_windows(samples):
    """Lossless fixed-window validation only; production should choose VAD boundaries."""
    if len(samples) == 0:
        raise ValueError("Empty recording")
    return [(start, min(start + MAX_SAMPLES, len(samples)))
            for start in range(0, len(samples), MAX_SAMPLES)]


def is_digital_silence(samples):
    """Skip exact zero PCM only; this does not replace speech VAD for actual noise."""
    return len(samples) > 0 and not np.any(samples)


def adb_shell(command, timeout=90):
    return subprocess.run(["adb", "shell", command], capture_output=True, text=True, timeout=timeout)


def classify_success(output):
    return ("backendId: 6" in output and "loaded " in output and "libQnnHtp.so" in output
            and "QNN_VALIDATION graphExecute succeeded" in output)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cache", type=Path, default=Path.home() / ".cache/codex/qnn-validation")
    p.add_argument("--models", type=Path, default=Path.home() / ".cache/codex/models/sherpa-onnx")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--remote", default="/data/local/tmp/soundsoftware-model-validation")
    args = p.parse_args()
    cache, root = args.cache, args.remote.rstrip("/")
    fixtures = cache / "fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    source = args.models / "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/test_wavs"
    zh, sr = sf.read(source / "zh.wav", dtype="int16")
    en, en_sr = sf.read(source / "en.wav", dtype="int16")
    assert sr == en_sr == RATE and zh.ndim == en.ndim == 1
    exact = np.pad(zh, (0, MAX_SAMPLES - len(zh)))
    long = np.concatenate([exact, en])
    fixtures_data = {"zh": zh, "en": en, "just_below_10s": exact[:-1], "exact_10s": exact,
                     "silence_100ms": np.zeros(1600, dtype="int16"), "silence_10s": np.zeros(MAX_SAMPLES, dtype="int16"),
                     "long_unsafe_probe": long}
    windows = split_windows(long)
    for index, (start, end) in enumerate(windows):
        fixtures_data[f"long_segment_{index}"] = long[start:end]
    for name, samples in fixtures_data.items():
        sf.write(fixtures / (name + ".wav"), samples, RATE, subtype="PCM_16")
    remote_fixtures = root + "/qnn-fixtures"
    subprocess.run(["adb", "shell", "mkdir", "-p", remote_fixtures], check=True, capture_output=True)
    subprocess.run(["adb", "push", *[str(fixtures / (name + ".wav")) for name in fixtures_data], remote_fixtures + "/"],
                   check=True, capture_output=True)
    model_dir = root + "/sensevoice-qnn-sm8850"
    base = ["/system/bin/toybox", "time", "-v", root + "/qnn-runtime/sherpa-onnx-offline-qnn",
            "--provider=qnn", "--debug=true", "--num-threads=2", "--sense-voice-use-itn=true",
            f"--tokens={model_dir}/tokens.txt", f"--sense-voice.qnn-context-binary={model_dir}/model.bin",
            f"--sense-voice.qnn-backend-lib={root}/qnn-libs/libQnnHtp.so",
            f"--sense-voice.qnn-system-lib={root}/qnn-libs/libQnnSystem.so"]
    prefix = "LD_LIBRARY_PATH=" + shlex.quote(root + "/qnn-runtime:" + root + "/qnn-libs") + " ADSP_LIBRARY_PATH=" + shlex.quote(root + "/qnn-libs") + " "
    doc = {"schema_version": 1, "status": "running", "scope": "SM8850_QNN_HTP_public_and_synthetic_boundary_validation",
           "started_at": datetime.now(timezone.utc).isoformat(), "npu_validated": False,
           "model_graph_backend": "HTP_requested", "cpu_work": "WAV decoding, features, LFR, CTC postprocessing remain CPU",
           "limits": ["Public/synthetic boundary fixtures, not user meeting quality", "CLI process, not App/JNI lifecycle",
                      "Timing includes debug diagnostics and time wrapper; not a controlled throughput benchmark"],
           "samples": [], "guards": [], "negative_controls": [], "quality_warnings": [],
           "fixture_source": "SenseVoice upstream public zh.wav/en.wav; zeros/padding/concatenation generated locally",
           "fixture_manifest": [{"name": name, "samples": len(samples), "sha256": sha256(fixtures / (name + ".wav"))}
                                for name, samples in fixtures_data.items()]}
    write_json(args.output, doc)
    try:
        for name in fixtures_data:
            local = fixtures / (name + ".wav")
            remote = remote_fixtures + "/" + local.name
            actual = adb_shell("sha256sum " + shlex.quote(remote)).stdout.split()[0]
            if actual != sha256(local):
                raise ValueError("Phone fixture hash mismatch")
        for name in fixtures_data:
            if name == "long_unsafe_probe":
                # Explicit negative demonstration of upstream truncation, never the guarded path.
                iterations = 1
            else:
                require_window(len(fixtures_data[name]))
                iterations = 3 if name in {"zh", "en"} else 1
            for repetition in range(iterations):
                command = prefix + shlex.join(base + [remote_fixtures + "/" + name + ".wav"])
                started = time.perf_counter()
                proc = adb_shell(command)
                output = proc.stdout + proc.stderr
                log = cache / f"qnn-{name}-{repetition}.log"
                log.write_text(output)
                item = {"name": name, "repetition": repetition, "command": command, "exit_code": proc.returncode,
                        "host_wall_seconds": time.perf_counter() - started, "raw_log_sha256": sha256(log),
                        "htp_graph_success": classify_success(output),
                        "truncation_warning": "Recognition result may be truncated/incomplete" in output,
                        "fixed_lfr": re.findall(r"fixed_lfr actual=(\d+) target=(\d+)", output)}
                if proc.returncode or not item["htp_graph_success"]:
                    item["failure_log"] = output[-8000:]
                    doc["samples"].append(item)
                    raise RuntimeError(f"HTP evidence missing or failed: {name}")
                item.update(parse_output(output))
                raw_result = next(json.loads(line) for line in output.splitlines() if line.startswith('{"lang"'))
                duration = len(fixtures_data[name]) / RATE
                item["timestamps_beyond_input"] = [t for t in raw_result.get("timestamps", []) if t > duration]
                item["evidence_lines"] = [line for line in output.splitlines() if any(marker in line for marker in
                    ["backendId:", "loaded ", "graphExecute succeeded", "fixed_lfr", "Initializing HtpProvider", "Truncate it"])]
                doc["samples"].append(item)
                write_json(args.output, doc)
                print(json.dumps({k: item[k] for k in ["name", "repetition", "htp_graph_success", "text"]}, ensure_ascii=False), flush=True)
        for count in [0, MAX_SAMPLES + 1, len(long)]:
            try:
                require_window(count)
                rejected = False
            except ValueError:
                rejected = True
            doc["guards"].append({"samples": count, "rejected_before_inference": rejected})
        for name in ["silence_100ms", "silence_10s"]:
            raw = next(s for s in doc["samples"] if s["name"] == name)
            doc["guards"].append({"fixture": name, "digital_zero_pcm_skipped_before_inference": is_digital_silence(fixtures_data[name])})
            if raw["text"].strip():
                doc["quality_warnings"].append({"fixture": name, "type": "raw_silence_hallucination", "text": raw["text"],
                    "mitigation": "Exact-zero guard handles digital silence; real recordings still require VAD and silence quality evaluation"})
        missing = [arg.replace("/qnn-libs/libQnnHtp.so", "/qnn-libs/does-not-exist.so") for arg in base]
        command = prefix + shlex.join(missing + [remote_fixtures + "/zh.wav"])
        negative = adb_shell(command)
        output = negative.stdout + negative.stderr
        doc["negative_controls"].append({"type": "missing_HTP_backend", "command": command, "exit_code": negative.returncode,
            "failed_without_fallback": negative.returncode != 0 and "graphExecute succeeded" not in output and '{"lang"' not in output,
            "output": output[-5000:]})
        # Constructed long fixture places second utterance at exactly 10 s.
        segments = [next(s for s in doc["samples"] if s["name"] == f"long_segment_{i}") for i in range(len(windows))]
        baseline_zh = next(s["text"] for s in doc["samples"] if s["name"] == "exact_10s")
        baseline_en = next(s["text"] for s in doc["samples"] if s["name"] == "en")
        doc["window_coverage"] = {"input_samples": len(long), "windows": windows,
            "processed_samples": sum(end - start for start, end in windows),
            "has_no_gap_or_overlap": windows[0][0] == 0 and windows[-1][1] == len(long)
                and all(a[1] == b[0] for a, b in zip(windows, windows[1:])),
            "segment_text_matches_separate_baselines": [segments[0]["text"] == baseline_zh, segments[1]["text"] == baseline_en]}
        unsafe = next(s for s in doc["samples"] if s["name"] == "long_unsafe_probe")
        assert unsafe["truncation_warning"], "Upstream oversized behavior changed; revisit guard evidence"
        assert all(g.get("rejected_before_inference", g.get("digital_zero_pcm_skipped_before_inference")) for g in doc["guards"])
        assert all(n["failed_without_fallback"] for n in doc["negative_controls"])
        assert doc["window_coverage"]["has_no_gap_or_overlap"]
        assert all(doc["window_coverage"]["segment_text_matches_separate_baselines"])
        assert not any(s["truncation_warning"] for s in doc["samples"] if s["name"] != "long_unsafe_probe")
        doc.update(status="passed_execution_and_window_guards", npu_validated=True, model_graph_backend="QNN_HTP_backend_id_6")
        code = 0
    except Exception as exc:
        doc.update(status="failed", error={"type": type(exc).__name__, "message": str(exc)})
        code = 1
    doc["finished_at"] = datetime.now(timezone.utc).isoformat()
    write_json(args.output, doc)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
