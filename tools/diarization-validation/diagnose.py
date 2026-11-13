#!/usr/bin/env python3
"""Local sherpa 2.0.1 diagnostic. No transcript, audio or embeddings enter project artifacts.
Optional private feature output can be replayed by the actual Kotlin SpeakerClustering test.
"""
import argparse,hashlib,json,time,re
from pathlib import Path
import numpy as np
import soundfile as sf
import sherpa_onnx as so

p=argparse.ArgumentParser();p.add_argument('audio',type=Path);p.add_argument('--expected',type=int,required=True);p.add_argument('--threshold',type=float,default=.5);p.add_argument('--features',action='store_true');p.add_argument('--label',default='sample');a=p.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}',a.label),'Use a simple cache label'
root=Path.home()/'.cache/codex/diarization-validation';root.mkdir(parents=True,exist_ok=True);root.chmod(0o700)
models=Path.home()/'.cache/codex/models/sherpa-onnx';wave,sr=sf.read(a.audio,dtype='float32');assert sr==16000 and wave.ndim==1,'Use canonical 16kHz mono input'
config=so.OfflineSpeakerDiarizationConfig(segmentation=so.OfflineSpeakerSegmentationModelConfig(pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(str(models/'sherpa-onnx-pyannote-segmentation-3-0/model.onnx')),num_threads=2),embedding=so.SpeakerEmbeddingExtractorConfig(model=str(models/'3dspeaker_eres2netv2_zh.onnx'),num_threads=2),clustering=so.FastClusteringConfig(num_clusters=-1,threshold=a.threshold),min_duration_on=.3,min_duration_off=.5)
start=time.monotonic();engine=so.OfflineSpeakerDiarization(config);turns=engine.process(wave).sort_by_start_time()
report={'inputSha256':hashlib.sha256(a.audio.read_bytes()).hexdigest(),'durationMs':len(wave)*1000//sr,'expectedSpeakers':a.expected,'rawSpeakers':len({t.speaker for t in turns}),'rawTurns':len(turns),'threshold':a.threshold,'elapsedSeconds':time.monotonic()-start,'automatic':True}
if a.features:
 extractor=so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model=str(models/'3dspeaker_eres2netv2_zh.onnx'),num_threads=2));observations=[]
 for t in turns:
  # Integer-ms boundaries mirror the production Kotlin path exactly.
  lo,hi=max(0,int(t.start*1000)),min(report['durationMs'],int(t.end*1000));ranges=[(lo,hi)]
  for other in turns:
   if other.speaker==t.speaker:continue
   left,right=max(0,int(other.start*1000)),min(report['durationMs'],int(other.end*1000));new=[]
   for l,h in ranges:
    if right<=l or left>=h:new.append((l,h))
    else:
     if left>l:new.append((l,min(h,left)))
     if right<h:new.append((max(l,right),h))
   ranges=new
  clean_ms=sum(h-l for l,h in ranges);vector=None
  if clean_ms>=300:
   audio=np.concatenate([wave[l*16:h*16] for l,h in ranges])[:6000*16]
   stream=extractor.create_stream();stream.accept_waveform(sr,audio);stream.input_finished()
   if extractor.is_ready(stream):vector=extractor.compute(stream)
  observations.append({'startMs':lo,'endMs':hi,'rawSpeaker':t.speaker,'cleanMs':clean_ms,'embedding':vector})
 # Explicit output location is always a user-global private cache, never relative to the repo.
 path=root/(a.label+'-private-features.json');path.touch(mode=0o600,exist_ok=True);path.chmod(0o600);path.write_text(json.dumps({'expectedSpeakers':a.expected,'observations':observations}));report['privateFeatures']=str(path)
(root/(a.label+'-metadata.json')).write_text(json.dumps(report,indent=2));print(json.dumps(report))
