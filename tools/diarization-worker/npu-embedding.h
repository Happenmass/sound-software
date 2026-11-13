#pragma once
// HTP convolution body, original FP32 statistics/dense head. No changed speaker weights.
#include <cmath>
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_cxx_api.h"
#include "HTP/QnnHtpDevice.h"
#include "sherpa-onnx/csrc/qnn/qnn-backend.h"
#include "sherpa-onnx/csrc/qnn/qnn-model.h"

namespace sherpa_onnx {
class SoundNpuEmbedding {
 public:
  SoundNpuEmbedding():env_(ORT_LOGGING_LEVEL_ERROR) {
    const char* runtime=std::getenv("SOUND_SPEAKER_NPU_RUNTIME");
    const char* models=std::getenv("SOUND_SPEAKER_NPU_MODELS");
    if(!runtime || !models)throw std::runtime_error("Missing NPU runtime/models");
    std::string r(runtime),m(models);
    backend_=std::make_unique<QnnBackend>(r+"/libQnnHtp.so",true);
    if(!backend_->IsInitialized())throw std::runtime_error("HTP backend unavailable");
    if(std::getenv("SOUND_SPEAKER_NPU_BURST")) {
      QnnDevice_Infrastructure_t infrastructure=nullptr;
      if(backend_->QnnInterface().deviceGetInfrastructure(&infrastructure)!=QNN_SUCCESS || !infrastructure)throw std::runtime_error("HTP performance infrastructure unavailable");
      auto* htp=static_cast<QnnHtpDevice_Infrastructure_t*>(infrastructure);
      if(htp->infraType!=QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF)throw std::runtime_error("Unexpected HTP infrastructure");
      perf_=htp->perfInfra;
      if(perf_.createPowerConfigId(0,0,&power_id_)!=QNN_SUCCESS)throw std::runtime_error("HTP performance context rejected");
      QnnHtpPerfInfrastructure_PowerConfig_t config{};
      config.option=QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
      auto& p=config.dcvsV3Config;p.contextId=power_id_;p.setDcvsEnable=1;p.dcvsEnable=0;
      p.powerMode=QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
      p.setSleepLatency=1;p.sleepLatency=40;p.setBusParams=1;p.setCoreParams=1;
      p.busVoltageCornerMin=p.busVoltageCornerTarget=p.busVoltageCornerMax=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      p.coreVoltageCornerMin=p.coreVoltageCornerTarget=p.coreVoltageCornerMax=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      const QnnHtpPerfInfrastructure_PowerConfig_t* configs[]={&config,nullptr};
      if(perf_.setPowerConfig(power_id_,configs)!=QNN_SUCCESS){perf_.destroyPowerConfigId(power_id_);power_id_=0;throw std::runtime_error("HTP burst vote rejected");}
      burst_=true;
    }
    body_=std::make_unique<QnnModel>(m+"/eres2netv2-masked.bin",r+"/libQnnSystem.so",backend_.get(),BinaryContextTag{},false);
    if(!body_->IsInitialized())throw std::runtime_error("HTP embedding context rejected");
    if(body_->TensorShape("features")!=std::vector<int32_t>{1,1,80,1024} || body_->TensorShape("trunk")!=std::vector<int32_t>{1,1024,10,128})throw std::runtime_error("Unexpected HTP tensor layout");
    Ort::SessionOptions options;options.SetIntraOpNumThreads(1);
    head_=std::make_unique<Ort::Session>(env_,(m+"/embedding-head.onnx").c_str(),options);
  }
  ~SoundNpuEmbedding(){head_.reset();body_.reset();if(power_id_)perf_.destroyPowerConfigId(power_id_);backend_.reset();}
  Ort::Value Compute(const Ort::Value& x) {
    const auto shape=x.GetTensorTypeAndShapeInfo().GetShape();
    if(shape.size()!=3 || shape[0]!=1 || shape[2]!=80 || shape[1]<9 || shape[1]>1024)throw std::runtime_error("Unsupported NPU embedding length; no silent CPU substitution");
    const int n=shape[1],valid=(n+7)/8;
    const float* source=x.GetTensorData<float>();
    std::vector<float> features(80*1024,0.f);
    for(int t=0;t<n;++t)for(int f=0;f<80;++f)features[f*1024+t]=source[t*80+f];
    std::vector<float> cropped(1024*10*valid);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if(!body_->SetInputTensorData("features",features.data(),features.size()))throw std::runtime_error("HTP input rejected");
      for(int width:{1024,512,256,128}){
        const int scale=1024/width,keep=(n+scale-1)/scale;
        std::vector<float> mask(width,0.f);std::fill(mask.begin(),mask.begin()+keep,1.f);
        if(!body_->SetInputTensorData("mask"+std::to_string(width),mask.data(),mask.size()))throw std::runtime_error("HTP mask rejected");
      }
      if(!body_->Run())throw std::runtime_error("HTP graphExecute failed");
      ++executions_;
      const auto values=body_->GetOutputTensorData("trunk");
      if(values.size()!=1024*10*128)throw std::runtime_error("HTP output length changed");
      for(int channel=0;channel<1024*10;++channel)std::copy_n(values.data()+channel*128,valid,cropped.data()+channel*valid);
    }
    auto memory=Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator,OrtMemTypeDefault);
    const int64_t dims[4]={1,1024,10,valid};
    auto input=Ort::Value::CreateTensor(memory,cropped.data(),cropped.size(),dims,4);
    const char* in="trunk";const char* out="embedding";
    auto result=head_->Run({},&in,&input,1,&out,1);
    return std::move(result[0]);
  }
  void Validate(const Ort::Value& reference,const Ort::Value& accelerated) {
    auto count=reference.GetTensorTypeAndShapeInfo().GetElementCount();
    if(count!=accelerated.GetTensorTypeAndShapeInfo().GetElementCount())throw std::runtime_error("Embedding dimensions differ");
    const float* a=reference.GetTensorData<float>();const float* b=accelerated.GetTensorData<float>();
    double dot=0,aa=0,bb=0,max=0;
    for(size_t i=0;i<count;++i){if(!std::isfinite(a[i]) || !std::isfinite(b[i]))throw std::runtime_error("Nonfinite embedding");dot+=double(a[i])*b[i];aa+=double(a[i])*a[i];bb+=double(b[i])*b[i];max=std::max(max,std::abs(double(a[i])-b[i]));}
    const double cosine=dot/std::sqrt(aa*bb);
    std::lock_guard<std::mutex> lock(mutex_);min_cosine_=std::min(min_cosine_,cosine);max_error_=std::max(max_error_,max);++validated_;
    if(!std::isfinite(cosine) || cosine<.9999)throw std::runtime_error("HTP embedding numeric quality gate failed");
  }
  void PrintStats() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::cout<<"SOUND_NPU_STATS {\"htpExecutions\":"<<executions_<<",\"validated\":"<<validated_<<",\"minCosine\":"<<min_cosine_<<",\"maxAbsError\":"<<max_error_<<",\"cpuSubstitutions\":0,\"burst\":"<<(burst_?"true":"false")<<"}"<<std::endl;
  }
 private:
  Ort::Env env_;std::unique_ptr<QnnBackend> backend_;std::unique_ptr<QnnModel> body_;std::unique_ptr<Ort::Session> head_;
  QnnHtpDevice_PerfInfrastructure_t perf_{};uint32_t power_id_=0;bool burst_=false;
  std::mutex mutex_;int executions_=0,validated_=0;double min_cosine_=1,max_error_=0;
};
inline SoundNpuEmbedding& SoundSpeakerNpu(){static SoundNpuEmbedding instance;return instance;}
} // namespace sherpa_onnx
