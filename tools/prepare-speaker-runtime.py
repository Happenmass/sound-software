#!/usr/bin/env python3
"""Package checksum-pinned speaker CPU worker (or test-only QNN probe), without weights."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--probe', action='store_true')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.cache/codex'
lock = json.loads((root / 'tools/speaker-runtime-lock.json').read_text())
kind = 'probe' if a.probe else 'cpu'
item = lock[kind]
for name, checksum in item['sources'].items():
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != checksum:
        raise SystemExit('Speaker source changed; rebuild and validate its runtime lock: ' + name)
source = cache / item['cachePath']
if not source.is_file():
    raise SystemExit('Missing speaker runtime; see tools/diarization-worker/README.md')
if hashlib.sha256(source.read_bytes()).hexdigest() != item['sha256']:
    raise SystemExit('Speaker runtime checksum mismatch; rebuild and validate before packaging')
with zipfile.ZipFile(cache / 'dependencies/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar') as z:
    if hashlib.sha256(z.read('jni/arm64-v8a/libonnxruntime.so')).hexdigest() != lock['ortSha256']:
        raise SystemExit('CPU AAR runtime changed; revalidate speaker outputs')
destination = a.output / 'arm64-v8a' / item['name']
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != item['sha256']:
    shutil.copyfile(source, destination)
destination.chmod(0o755)
print('Prepared pinned speaker ' + kind + ' runtime; no weights copied')
