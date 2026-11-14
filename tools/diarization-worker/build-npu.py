#!/usr/bin/env python3
"""Build a separate experimental HTP worker, retaining the stable CPU build and binary."""
import hashlib,os,runpy,shutil,subprocess,tarfile
from pathlib import Path
repo=Path(__file__).resolve().parents[2];cache=Path.home()/'.cache/codex'
root=cache/'speaker-npu-batch4-worker';root.mkdir(exist_ok=True);source=root/'sherpa-onnx-2.0.1'
archive=cache/'qnn-validation/sherpa-onnx-v2.0.1.tar.gz'
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7'
if not (source/'.sound-source-complete').is_file():
 with tarfile.open(archive) as t:t.extractall(root,filter=lambda m,p:tarfile.data_filter(m,p) if m.isfile() or m.isdir() else None)
 (source/'.sound-source-complete').write_text('pinned source')
# Reproduce the CPU parallel scheduling patch, then copy that exact header only.
subprocess.run(['python3',str(repo/'tools/diarization-worker/build.py')],check=True)
csrc=source/'sherpa-onnx/csrc'
for filename in ['offline-speaker-diarization-pyannote-impl.h','sound-diar-parallel.h']:
 shutil.copyfile(cache/'speaker-parallel-worker/sherpa-onnx-2.0.1/sherpa-onnx/csrc'/filename,csrc/filename)
shutil.copyfile(repo/'tools/diarization-worker/npu-embedding.h',csrc/'qnn/sound-npu-embedding.h')
with tarfile.open(archive) as t:s=t.extractfile('sherpa-onnx-2.0.1/sherpa-onnx/csrc/speaker-embedding-extractor-model.cc').read().decode()
s=s.replace('namespace sherpa_onnx {','#include "sherpa-onnx/csrc/qnn/sound-npu-embedding.h"\n\nnamespace sherpa_onnx {',1)
(csrc/'speaker-embedding-extractor-model.cc').write_text(s)
for filename in ('speaker-embedding-extractor.h','speaker-embedding-extractor.cc','speaker-embedding-extractor-impl.h',
                 'speaker-embedding-extractor-general-impl.h','speaker-embedding-extractor-model.h'):
 with tarfile.open(archive) as t:
  (csrc/filename).write_bytes(t.extractfile('sherpa-onnx-2.0.1/sherpa-onnx/csrc/'+filename).read())
runpy.run_path(str(repo/'tools/diarization-worker/batch4-build-patches.py'))['patch_batch_sources'](csrc)
shutil.copyfile(repo/'tools/diarization-worker/npu-main.cc',csrc/'sound-speaker-npu-worker.cc')
cm=csrc/'CMakeLists.txt';add='add_executable(sound-speaker-npu-worker sound-speaker-npu-worker.cc)\ntarget_link_libraries(sound-speaker-npu-worker sherpa-onnx-core)\ntarget_include_directories(sound-speaker-npu-worker PRIVATE "$ENV{QNN_SDK_ROOT}/include/QNN" "$ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}")\n'
with tarfile.open(archive) as t:base=t.extractfile('sherpa-onnx-2.0.1/sherpa-onnx/csrc/CMakeLists.txt').read().decode()
cm.write_text(base+'\n'+add)
ndk=Path.home()/'Library/Android/sdk/ndk/28.2.13676358';cmake=Path.home()/'Library/Android/sdk/cmake/3.31.6/bin/cmake';build=root/'build';ort=cache/'qnn-validation/onnxruntime-1.28.2'
env=dict(os.environ,QNN_SDK_ROOT=str(cache/'qnn-validation/qnn-include-2.40.0.251030'),SHERPA_ONNXRUNTIME_LIB_DIR=str(ort/'jni/arm64-v8a'),SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort/'headers'))
options=dict(CMAKE_BUILD_TYPE='Release',BUILD_SHARED_LIBS='OFF',ANDROID_ABI='arm64-v8a',ANDROID_PLATFORM='android-28',CMAKE_TOOLCHAIN_FILE=str(ndk/'build/cmake/android.toolchain.cmake'),CMAKE_MAKE_PROGRAM=str(cmake.parent/'ninja'),SHERPA_ONNX_ENABLE_QNN='ON',SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION='ON',SHERPA_ONNX_ENABLE_TTS='OFF',SHERPA_ONNX_ENABLE_JNI='OFF',SHERPA_ONNX_ENABLE_C_API='OFF',SHERPA_ONNX_ENABLE_BINARY='OFF',SHERPA_ONNX_ENABLE_TESTS='OFF',SHERPA_ONNX_ENABLE_CHECK='OFF',SHERPA_ONNX_ENABLE_PORTAUDIO='OFF',SHERPA_ONNX_ENABLE_WEBSOCKET='OFF',SHERPA_ONNX_BUILD_C_API_EXAMPLES='OFF')
for p in (cache/'qnn-validation/build-arm64/_deps').glob('*-src'):options['FETCHCONTENT_SOURCE_DIR_'+p.name[:-4].upper()]=str(p)
for command,name in [([str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja']+[f'-D{k}={v}' for k,v in options.items()],'configure.log'),([str(cmake),'--build',str(build),'--target','sound-speaker-npu-worker','-j','6'],'build.log')]:
 print(name,flush=True)
 with (root/name).open('w') as log:subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
out=root/'staged/sound-speaker-npu-worker';out.parent.mkdir(exist_ok=True);shutil.copyfile(build/'bin/sound-speaker-npu-worker',out)
subprocess.run([str(ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip'),str(out)],check=True);print(out,flush=True)
