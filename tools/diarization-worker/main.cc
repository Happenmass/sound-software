// Same pinned CPU models, window grid and clustering; independent forwards may run concurrently.
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <unistd.h>
#include "sherpa-onnx/csrc/offline-speaker-diarization.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/wave-reader.h"
int main(int argc,char** argv){
 sherpa_onnx::ParseOptions options("Sound CPU speaker worker protocol 1");
 sherpa_onnx::OfflineSpeakerDiarizationConfig config;config.Register(&options);int workers=1;
 options.Register("parallel-workers",&workers,"Independent forwards; 1 to 4");options.Read(argc,argv);
 if(options.NumArgs() || workers<1 || workers>4 || config.segmentation.provider!="cpu" || config.embedding.provider!="cpu" || !config.Validate())return 2;
 setenv("SOUND_SPEAKER_WORKERS",std::to_string(workers).c_str(),1);
 const auto begin=std::chrono::steady_clock::now();sherpa_onnx::OfflineSpeakerDiarization engine(config);
 const auto load=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();
 std::cout<<"SOUND_DIAR {\"type\":\"ready\",\"protocol\":1,\"backend\":\"cpu-parallel\",\"pid\":"<<getpid()<<",\"loadMs\":"<<load<<",\"workers\":"<<workers<<"}"<<std::endl;
 std::string line;int sequence=0;
 try{while(std::getline(std::cin,line)){
  if(line=="quit")break;auto a=line.find('\t');auto b=line.find('\t',a+1);
  if(a==std::string::npos || b==std::string::npos || line.size()>8192 || line.substr(0,a)!=std::to_string(sequence+1))return 3;
  int count=std::stoi(line.substr(a+1,b-a-1));if(count<0 || count>12)return 3;
  int32_t rate=0;bool ok=false;auto samples=sherpa_onnx::ReadWave(line.substr(b+1),&rate,&ok);
  if(!ok || rate!=16000 || samples.empty() || samples.size()>16000LL*60*80)return 4;
  auto cfg=config;cfg.clustering.num_clusters=count==0?-1:count;engine.SetConfig(cfg);
  const auto start=std::chrono::steady_clock::now();auto result=engine.Process(samples.data(),samples.size());
  const auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  ++sequence;std::cout<<"SOUND_DIAR {\"type\":\"result\",\"protocol\":1,\"backend\":\"cpu-parallel\",\"pid\":"<<getpid()<<",\"sequence\":"<<sequence<<",\"elapsedMs\":"<<elapsed<<",\"turns\":[";
  bool first=true;for(const auto& segment:result.SortByStartTime()){
   if(!first)std::cout<<",";first=false;
   std::cout<<"["<<static_cast<int64_t>(segment.Start()*1000.f)<<","<<static_cast<int64_t>(segment.End()*1000.f)<<","<<segment.Speaker()<<"]";
  }
  std::cout<<"]}"<<std::endl;
 }}catch(...){return 5;}
 return 0;
}
