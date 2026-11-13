#!/usr/bin/env python3
"""Deploy only checksum-verified speaker NPU models to an explicitly selected test phone."""
import argparse,hashlib,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[1];root=Path.home()/'.cache/codex/models/qualcomm/speaker-eres2netv2-qnn250'
lock=json.loads((repo/'tools/speaker-npu-runtime-lock.json').read_text());target='/sdcard/Android/data/dev.soundsoftware.app/files/models/diarization-npu'
for name,item in lock['models'].items():
    file=root/name
    if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=item['sha256']:
        raise SystemExit('Missing or mismatched speaker model; build and validate first: '+name)
adb=['adb','-s',a.serial]
subprocess.run(adb+['shell','mkdir','-p',target],check=True)
for name in lock['models']:
    subprocess.run(adb+['push',str(root/name),target+'/'+name],check=True)
    actual=subprocess.check_output(adb+['shell','sha256sum',target+'/'+name],text=True).split()[0]
    if actual!=lock['models'][name]['sha256']:raise SystemExit('Phone model checksum mismatch: '+name)
print('Verified speaker NPU weights deployed; CPU/ASR models and recordings were not changed')
