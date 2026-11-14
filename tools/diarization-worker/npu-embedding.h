#pragma once
// HTP convolution body, original FP32 statistics/dense head. No changed speaker weights.
#include <chrono>
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
    runtime_=r;models_=m;
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
    Ort::SessionOptions options;options.SetIntraOpNumThreads(1);
    head_=std::make_unique<Ort::Session>(env_,(m+"/embedding-head.onnx").c_str(),options);
  }
  ~SoundNpuEmbedding(){head_.reset();batch4_.reset();batch1_.reset();if(power_id_)perf_.destroyPowerConfigId(power_id_);backend_.reset();}
  std::vector<Ort::Value> ComputeBatch(const std::vector<Ort::Value>& inputs, int batch) {
    constexpr size_t kFeatures = 80, kFrames = 1024;
    constexpr size_t kChannels = 1024 * 10, kColumns = 128;
    if ((batch != 1 && batch != 4) || inputs.empty() || inputs.size() > static_cast<size_t>(batch))
      throw std::runtime_error("Invalid HTP speaker batch");
    const auto pack_start = Clock::now();
    std::vector<float> features(batch * kFeatures * kFrames);
    std::vector<size_t> lengths;
    for (size_t lane = 0; lane < inputs.size(); ++lane) {
      const auto shape = inputs[lane].GetTensorTypeAndShapeInfo().GetShape();
      if (shape.size() != 3 || shape[0] != 1 || shape[2] != kFeatures ||
          shape[1] < 9 || shape[1] > kFrames)
        throw std::runtime_error("Unsupported NPU embedding length");
      const size_t length = shape[1];
      lengths.push_back(length);
      const auto *source = inputs[lane].GetTensorData<float>();
      for (size_t t = 0; t < length; ++t)
        for (size_t f = 0; f < kFeatures; ++f)
          features[(lane * kFeatures + f) * kFrames + t] = source[t * kFeatures + f];
    }

    const auto pack_ms = Milliseconds(pack_start);
    std::vector<std::vector<float>> crops(lengths.size());
    for (size_t lane = 0; lane < lengths.size(); ++lane)
      crops[lane].resize(kChannels * ((lengths[lane] + 7) / 8));
    double crop_ms = 0;
    auto crop = [&](const std::vector<float> &trunk) {
      const auto start = Clock::now();
      for (size_t lane = 0; lane < lengths.size(); ++lane) {
        const size_t valid = (lengths[lane] + 7) / 8;
        for (size_t channel = 0; channel < kChannels; ++channel)
          std::copy_n(trunk.data() + (lane * kChannels + channel) * kColumns,
                      valid, crops[lane].data() + channel * valid);
      }
      crop_ms = Milliseconds(start);
    };
    std::vector<float> trunk;
    double graph_ms, output_ms, input_ms, load_ms = 0;
    size_t output_bytes;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto &body = batch == 1 ? batch1_ : batch4_;
      if (!body) {
        const auto started = Clock::now();
        const auto name = batch == 1 ? "/eres2netv2-masked.bin" : "/eres2netv2-masked-batch4.bin";
        body = std::make_unique<QnnModel>(models_ + name, runtime_ + "/libQnnSystem.so",
                                          backend_.get(), BinaryContextTag{}, false);
        if (!body->IsInitialized()) throw std::runtime_error("HTP embedding context rejected");
        if (body->TensorShape("features") != std::vector<int32_t>{batch,1,80,1024} ||
            body->TensorShape("trunk") != std::vector<int32_t>{batch,1024,10,128})
          throw std::runtime_error("Unexpected HTP tensor layout");
        load_ms = Milliseconds(started);
      }
      auto started = Clock::now();
      if (!body->SetInputTensorData("features", features.data(), features.size()))
        throw std::runtime_error("HTP input rejected");
      for (size_t width : {1024, 512, 256, 128}) {
        const size_t stride = kFrames / width;
        std::vector<float> mask(batch * width);
        for (size_t lane = 0; lane < lengths.size(); ++lane)
          std::fill_n(mask.data() + lane * width, (lengths[lane] + stride - 1) / stride, 1.f);
        if (!body->SetInputTensorData("mask" + std::to_string(width), mask.data(), mask.size()))
          throw std::runtime_error("HTP mask rejected");
      }
      input_ms = Milliseconds(started);
      started = Clock::now();
      if (!body->Run()) throw std::runtime_error("HTP graphExecute failed");
      graph_ms = Milliseconds(started);
      started = Clock::now();
      trunk = body->GetOutputTensorData("trunk");
      output_ms = Milliseconds(started);
      output_bytes = body->TensorSizeInBytes("trunk");
      if (trunk.size() != batch * kChannels * kColumns)
        throw std::runtime_error("HTP output length changed");
      if (batch == 1) crop(trunk);
      ++executions_;
      embeddings_ += inputs.size();
    }

    if (batch == 4) crop(trunk);
    const auto head_start = Clock::now();
    const auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    const char *in = "trunk", *out = "embedding";
    std::vector<Ort::Value> embeddings;
    for (size_t lane = 0; lane < lengths.size(); ++lane) {
      const size_t valid = (lengths[lane] + 7) / 8;
      auto &cropped = crops[lane];
      const int64_t dims[] = {1, 1024, 10, static_cast<int64_t>(valid)};
      auto input = Ort::Value::CreateTensor(memory, cropped.data(), cropped.size(), dims, 4);
      auto result = head_->Run({}, &in, &input, 1, &out, 1);
      const auto count = result.front().GetTensorTypeAndShapeInfo().GetElementCount();
      const auto *vector = result.front().GetTensorData<float>();
      if (count != 192 || !std::all_of(vector, vector + count, [](float value) {
            return std::isfinite(value);
          }))
        throw std::runtime_error("Invalid HTP speaker embedding");
      embeddings.push_back(std::move(result.front()));
    }
    const auto head_ms = Milliseconds(head_start);
    if (profile_) {
      std::lock_guard<std::mutex> lock(profile_mutex_);
      auto &stats = batch == 1 ? single_stats_ : batch_stats_;
      ++stats.calls;
      stats.load += load_ms;
      stats.pack += pack_ms;
      stats.input += input_ms;
      stats.graph += graph_ms;
      stats.output += output_ms;
      stats.crop += crop_ms;
      stats.head += head_ms;
      stats.output_bytes += output_bytes;
    }
    return embeddings;
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
    std::cout<<"SOUND_NPU_STATS {\"htpExecutions\":"<<executions_<<",\"embeddings\":"<<embeddings_<<",\"validated\":"<<validated_<<",\"minCosine\":"<<min_cosine_<<",\"maxAbsError\":"<<max_error_<<",\"cpuSubstitutions\":0,\"burst\":"<<(burst_?"true":"false")<<"}"<<std::endl;
  }
  void PrintProfile(const char *phase) {
    if (!profile_) return;
    std::lock_guard<std::mutex> lock(profile_mutex_);
    for (const auto *stats : {&single_stats_, &batch_stats_}) {
      if (stats->calls == 0) continue;
      std::cout << "SOUND_SPEAKER_PROFILE {\"phase\":\"" << phase
                << "\",\"batch\":" << (stats == &single_stats_ ? 1 : 4)
                << ",\"calls\":" << stats->calls << ",\"loadMs\":" << stats->load
                << ",\"packMs\":" << stats->pack << ",\"inputMs\":" << stats->input
                << ",\"graphMs\":" << stats->graph << ",\"outputMs\":" << stats->output
                << ",\"cropMs\":" << stats->crop << ",\"headMs\":" << stats->head
                << ",\"outputBytes\":" << stats->output_bytes << '}' << std::endl;
    }
    single_stats_ = {};
    batch_stats_ = {};
  }
 private:
  using Clock = std::chrono::steady_clock;
  static double Milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
  }
  struct Profile {
    size_t calls = 0, output_bytes = 0;
    double load = 0, pack = 0, input = 0, graph = 0, output = 0, crop = 0, head = 0;
  };
  bool profile_ = std::getenv("SOUND_SPEAKER_NPU_PROFILE") != nullptr;
  std::mutex profile_mutex_;
  Profile single_stats_, batch_stats_;
  std::string runtime_, models_;
  Ort::Env env_;
  std::unique_ptr<QnnBackend> backend_;
  std::unique_ptr<QnnModel> batch1_, batch4_;
  std::unique_ptr<Ort::Session> head_;
  QnnHtpDevice_PerfInfrastructure_t perf_{};uint32_t power_id_=0;bool burst_=false;
  std::mutex mutex_;int executions_=0,embeddings_=0,validated_=0;double min_cosine_=1,max_error_=0;
};
inline SoundNpuEmbedding *& SoundSpeakerNpuInstance() {
  static SoundNpuEmbedding *instance = nullptr;
  return instance;
}
inline SoundNpuEmbedding& SoundSpeakerNpu() {
  static SoundNpuEmbedding instance;
  static const bool registered = (SoundSpeakerNpuInstance() = &instance, true);
  (void)registered;
  return instance;
}
inline int SoundSpeakerFirstBatch() {
  return std::getenv("SOUND_SPEAKER_FIRST_BATCH4") ? 4 : 1;
}
inline void PrintSoundSpeakerNpuStats(const char *phase) {
  if (auto *instance = SoundSpeakerNpuInstance()) {
    instance->PrintProfile(phase);
    instance->PrintStats();
  }
  else std::cout << R"(SOUND_NPU_STATS {"htpExecutions":0,"embeddings":0,"validated":0,"minCosine":1,"maxAbsError":0,"cpuSubstitutions":0,"burst":false})"
                 << std::endl;
}
} // namespace sherpa_onnx
