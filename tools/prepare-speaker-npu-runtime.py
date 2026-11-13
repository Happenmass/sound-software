#!/usr/bin/env python3
"""Package a pinned isolated speaker runtime and executable, without any model weights."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--jni',type=Path,required=True);p.add_argument('--assets',type=Path,required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[1];cache=Path.home()/'.cache/codex';lock=json.loads((repo/'tools/speaker-npu-runtime-lock.json').read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
for name,checksum in lock['sources'].items():
 if sha(repo/name)!=checksum:raise SystemExit('Speaker NPU source changed; rebuild and revalidate lock: '+name)
worker=cache/lock['worker']['cachePath']
if sha(worker)!=lock['worker']['sha256']:raise SystemExit('Speaker NPU binary changed; rebuild and validate its lock')
dest=a.jni/'arm64-v8a/libsound_speaker_npu_worker.so';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(worker,dest);dest.chmod(0o755)
a.assets.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.assets/'speaker-npu-runtime.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for name,item in sorted(lock['libraries'].items()):
  source=cache/item['cachePath']
  if sha(source)!=item['sha256']:raise SystemExit('Speaker NPU library checksum mismatch: '+name)
  info=zipfile.ZipInfo(name);info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o444<<16;z.writestr(info,source.read_bytes())
manifest={'sdk':lock['sdk'],'validatedDeviceModels':lock['validatedDeviceModels'],'libraries':{k:v['sha256'] for k,v in lock['libraries'].items()},'models':lock['models'],'originalModels':lock['originalModels']}
(a.assets/'speaker-npu-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Prepared isolated speaker QAIRT 2.50 runtime and worker; ASR runtime unchanged; no weights copied')
