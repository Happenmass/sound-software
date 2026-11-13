// Diagnostic-only context compatibility probe, isolated from the application's recognizers.
#include <chrono>
#include <iostream>
#include <vector>
#include "sherpa-onnx/csrc/qnn/qnn-backend.h"
#include "sherpa-onnx/csrc/qnn/qnn-model.h"
int main(int argc,char** argv) {
  if(argc!=4)return 2;
  sherpa_onnx::QnnBackend backend(argv[1],true);
  if(!backend.IsInitialized())return 3;
  sherpa_onnx::QnnModel model(argv[3],argv[2],&backend,sherpa_onnx::BinaryContextTag{},true);
  if(!model.IsInitialized()){std::cout<<"SPEAKER_QNN_MODEL_REJECTED"<<std::endl;return 4;}
  std::cout<<"SPEAKER_QNN_MODEL_READY"<<std::endl;
  for(const auto& name:model.InputTensorNames()) {
    const auto shape=model.TensorShape(name);int64_t n=1;
    std::cout<<"INPUT "<<name;
    for(auto v:shape){std::cout<<" "<<v;n*=v;}std::cout<<std::endl;
    if(n<=0 || n>16000000)return 5;
    std::vector<float> input(n,0.0f);if(!model.SetInputTensorData(name,input.data(),input.size()))return 6;
  }
  const auto start=std::chrono::steady_clock::now();
  if(!model.Run())return 7;
  std::cout<<"SPEAKER_QNN_GRAPH_MS "<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<std::endl;
  return 0;
}
