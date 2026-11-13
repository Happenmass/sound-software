#!/usr/bin/env python3
"""Package pinned QNN executable/libraries from the global cache. No models or JNI replacement."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];lock=json.loads((root/'tools/qnn-runtime-lock.json').read_text());cache=Path.home()/'.cache/codex'
if hashlib.sha256((root/'tools/qnn-worker/main.cc').read_bytes()).hexdigest()!=lock['workerSourceSha256']:raise SystemExit('QNN worker source changed; rebuild and update its lock')
out=a.output/'arm64-v8a';out.mkdir(parents=True,exist_ok=True)
for name,item in lock['files'].items():
 src=cache/item['cachePath']
 if not src.is_file():raise SystemExit('Missing pinned QNN runtime: run tools/model-validation/qnn_build.py and prepare the documented QNN 2.40 cache first')
 if hashlib.sha256(src.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('QNN runtime checksum mismatch: '+name)
 target=out/name
 if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=item['sha256']:shutil.copyfile(src,target)
 target.chmod(0o755)
# The child shares the existing AAR's byte-identical ORT. Do not replace the CPU JNI (it includes diarization).
with zipfile.ZipFile(cache/'dependencies/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar') as z:
 if hashlib.sha256(z.read('jni/arm64-v8a/libonnxruntime.so')).hexdigest()!=lock['ortSha256']:raise SystemExit('AAR ORT changed; revalidate isolated QNN integration')
print('Prepared pinned QNN child runtime; no weights copied')
