#!/usr/bin/env python3
"""Build an isolated CPU speaker worker from pinned source; model weights are never copied."""
import hashlib,json,os,shutil,subprocess,tarfile
from pathlib import Path
R=Path(__file__).resolve().parents[2];cache=Path.home()/'.cache/codex';root=cache/'speaker-parallel-worker';root.mkdir(parents=True,exist_ok=True)
archive=cache/'qnn-validation/sherpa-onnx-v2.0.1.tar.gz'
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7'
source=root/'sherpa-onnx-2.0.1'
if not (source/'.sound-source-complete').is_file():
 with tarfile.open(archive) as t:t.extractall(root,filter=lambda m,p:tarfile.data_filter(m,p) if m.isfile() or m.isdir() else None)
 (source/'.sound-source-complete').write_text('pinned v2.0.1 regular files only')
# Always patch from the verified archive, so repeated builds cannot stack edits.
with tarfile.open(archive) as t:
 entry='sherpa-onnx-2.0.1/sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h';s=t.extractfile(entry).read().decode()
s=s.replace('#include <algorithm>','#include <algorithm>\n#include "sherpa-onnx/csrc/sound-diar-parallel.h"')
start=s.index('    ans.reserve(num_chunks + has_last_chunk);');end=s.index('    return ans;',start)
s=s[:start]+'''    ans.resize(num_chunks + has_last_chunk);
    SoundSpeakerParallelFor(num_chunks,[&](int32_t i){ans[i]=ProcessChunk(audio+i*window_shift);});
    if(has_last_chunk){std::vector<float> buf(window_size);const float* p=audio+num_chunks*window_shift;std::copy(p,audio+n,buf.data());ans[num_chunks]=ProcessChunk(buf.data());}

'''+s[end:]
anchor='    int32_t cur_row_index = 0;'
assert s.count(anchor)==1
parallel='''
    if(!callback && SoundSpeakerWorkers()>1){
      std::vector<std::vector<float>> vectors(sample_indexes.size());
      SoundSpeakerParallelFor(sample_indexes.size(),[&](int32_t i){
        auto stream=embedding_extractor_.CreateStream();
        for(const auto& p:sample_indexes[i]){int32_t end=(p.second<=n)?p.second:n;int32_t count=end-p.first;if(count>0)stream->AcceptWaveform(sample_rate,audio+p.first,count);}
        stream->InputFinished();
        if(!embedding_extractor_.IsReady(stream.get()))throw std::runtime_error("speaker input too short");
        vectors[i]=embedding_extractor_.Compute(stream.get());
      });
      for(int32_t i=0;i<static_cast<int32_t>(vectors.size());++i){const auto& embedding=vectors[i];
        if(std::none_of(embedding.begin(),embedding.end(),IsNaNWrapper)){std::copy(embedding.begin(),embedding.end(),&ans(cur_row_index,0));++cur_row_index;valid_indexes->push_back(i);}
      }
      if(cur_row_index!=ans.rows())ans.conservativeResize(cur_row_index,ans.cols());
      return ans;
    }
'''
s=s.replace(anchor,anchor+parallel);(source/'sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h').write_text(s)
for name,target in [('main.cc','sound-speaker-worker.cc'),('parallel.h','sound-diar-parallel.h')]:shutil.copyfile(R/'tools/diarization-worker'/name,source/'sherpa-onnx/csrc'/target)
cm=source/'sherpa-onnx/csrc/CMakeLists.txt';add='add_executable(sound-speaker-worker sound-speaker-worker.cc)\ntarget_link_libraries(sound-speaker-worker sherpa-onnx-core)\n'
if add not in cm.read_text():cm.write_text(cm.read_text()+'\n'+add)
ndk=Path.home()/'Library/Android/sdk/ndk/28.2.13676358';cmake=Path.home()/'Library/Android/sdk/cmake/3.31.6/bin/cmake';build=root/'build';ort=cache/'qnn-validation/onnxruntime-1.28.2'
env=dict(os.environ,SHERPA_ONNXRUNTIME_LIB_DIR=str(ort/'jni/arm64-v8a'),SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort/'headers'))
options=dict(CMAKE_BUILD_TYPE='Release',BUILD_SHARED_LIBS='OFF',ANDROID_ABI='arm64-v8a',ANDROID_PLATFORM='android-28',CMAKE_TOOLCHAIN_FILE=str(ndk/'build/cmake/android.toolchain.cmake'),CMAKE_MAKE_PROGRAM=str(cmake.parent/'ninja'),SHERPA_ONNX_ENABLE_QNN='OFF',SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION='ON',SHERPA_ONNX_ENABLE_TTS='OFF',SHERPA_ONNX_ENABLE_JNI='OFF',SHERPA_ONNX_ENABLE_C_API='OFF',SHERPA_ONNX_ENABLE_BINARY='OFF',SHERPA_ONNX_ENABLE_TESTS='OFF',SHERPA_ONNX_ENABLE_CHECK='OFF',SHERPA_ONNX_ENABLE_PORTAUDIO='OFF',SHERPA_ONNX_ENABLE_WEBSOCKET='OFF',SHERPA_ONNX_BUILD_C_API_EXAMPLES='OFF')
for p in (cache/'qnn-validation/build-arm64/_deps').glob('*-src'):options['FETCHCONTENT_SOURCE_DIR_'+p.name[:-4].upper()]=str(p)
commands=[[str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja']+[f'-D{k}={v}' for k,v in options.items()],[str(cmake),'--build',str(build),'--target','sound-speaker-worker','-j','6']]
for command,name in zip(commands,['configure.log','build.log']):
 print(name,flush=True)
 with (root/name).open('w') as log:subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
staged=root/'staged';staged.mkdir(exist_ok=True);out=staged/'sound-speaker-worker';shutil.copy2(build/'bin/sound-speaker-worker',out);subprocess.run([str(ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip'),str(out)],check=True)
(root/'build-info.json').write_text(json.dumps({'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'sources':{n:hashlib.sha256((R/'tools/diarization-worker'/n).read_bytes()).hexdigest() for n in ['main.cc','parallel.h','build.py']},'binarySha256':hashlib.sha256(out.read_bytes()).hexdigest()},indent=2));print(out,flush=True)
