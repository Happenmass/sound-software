// Speaker worker with a second request for clean-turn embeddings.
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <unistd.h>
#include "sherpa-onnx/csrc/offline-speaker-diarization.h"
#include "sherpa-onnx/csrc/online-stream.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/qnn/sound-npu-embedding.h"
#include "sherpa-onnx/csrc/speaker-embedding-extractor.h"
#include "sherpa-onnx/csrc/wave-reader.h"

namespace {
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

void Embed(const std::string &request, const std::vector<float> &samples,
           sherpa_onnx::SpeakerEmbeddingExtractor &extractor) {
  if (samples.empty()) throw std::runtime_error("Missing audio for speaker batch");
  std::istringstream groups(request);
  std::string group;
  std::vector<std::unique_ptr<sherpa_onnx::OnlineStream>> streams;
  std::vector<sherpa_onnx::OnlineStream *> inputs;
  while (std::getline(groups, group, '\t')) {
    if (streams.size() == 4) throw std::runtime_error("Too many clean turns");
    auto stream = extractor.CreateStream();
    std::istringstream intervals(group);
    int64_t begin, end;
    size_t total = 0;
    while (intervals >> begin >> end) {
      if (begin < 0 || end <= begin || static_cast<size_t>(end) > samples.size())
        throw std::runtime_error("Invalid clean turn interval");
      total += end - begin;
      if (total > 96000) throw std::runtime_error("Clean turn too long");
      stream->AcceptWaveform(16000, samples.data() + begin, end - begin);
    }
    if (!intervals.eof() || total < 4800) throw std::runtime_error("Invalid clean turn");
    stream->InputFinished();
    inputs.push_back(stream.get());
    streams.push_back(std::move(stream));
  }
  auto vectors = extractor.ComputeBatch(inputs);
  sherpa_onnx::PrintSoundSpeakerNpuStats();
  std::cout << "SOUND_DIAR {\"type\":\"embeddings\",\"vectors\":[";
  for (size_t i = 0; i < vectors.size(); ++i) {
    if (i) std::cout << ',';
    if (vectors[i].empty()) {
      std::cout << "null";
      continue;
    }
    std::cout << '[';
    for (size_t j = 0; j < vectors[i].size(); ++j) {
      if (j) std::cout << ',';
      if (!std::isfinite(vectors[i][j])) throw std::runtime_error("Nonfinite speaker embedding");
      std::cout << std::setprecision(9) << vectors[i][j];
    }
    std::cout << ']';
  }
  std::cout << "]}" << std::endl;
}
}  // namespace

int main(int argc, char **argv) try {
  sherpa_onnx::ParseOptions options("Sound HTP speaker worker");
  sherpa_onnx::OfflineSpeakerDiarizationConfig config;
  config.Register(&options);
  int workers = 1;
  options.Register("parallel-workers", &workers, "Independent CPU forwards; 1 to 4");
  options.Read(argc, argv);
  if (options.NumArgs() || workers < 1 || workers > 4 ||
      config.segmentation.provider != "cpu" || config.embedding.provider != "cpu" ||
      !config.Validate()) return 2;
  setenv("SOUND_SPEAKER_WORKERS", std::to_string(workers).c_str(), 1);
  const auto started = Clock::now();
  sherpa_onnx::OfflineSpeakerDiarization engine(config);
  std::unique_ptr<sherpa_onnx::SpeakerEmbeddingExtractor> extra;
  std::cout << "SOUND_DIAR {\"type\":\"ready\",\"protocol\":1,\"backend\":\"qnn-htp-hybrid\",\"pid\":"
            << getpid() << ",\"loadMs\":" << elapsed(started) << ",\"workers\":" << workers << '}' << std::endl;
  std::vector<float> samples;
  std::string line;
  int sequence = 0;
  while (std::getline(std::cin, line) && line != "quit") {
    if (line.size() > 8192) return 3;
    if (line.rfind("embed\t", 0) == 0) {
      if (!extra) extra = std::make_unique<sherpa_onnx::SpeakerEmbeddingExtractor>(config.embedding);
      Embed(line.substr(6), samples, *extra);
      continue;
    }
    const auto a = line.find('\t'), b = line.find('\t', a + 1);
    if (a == std::string::npos || b == std::string::npos ||
        line.substr(0, a) != std::to_string(sequence + 1)) return 3;
    const int count = std::stoi(line.substr(a + 1, b - a - 1));
    if (count < 0 || count > 12) return 3;
    int32_t rate = 0;
    bool ok = false;
    samples = sherpa_onnx::ReadWave(line.substr(b + 1), &rate, &ok);
    if (!ok || rate != 16000 || samples.empty() || samples.size() > 16000LL * 60 * 80) return 4;
    auto cfg = config;
    cfg.clustering.num_clusters = count == 0 ? -1 : count;
    engine.SetConfig(cfg);
    const auto start = Clock::now();
    auto result = engine.Process(samples.data(), samples.size());
    const auto ms = elapsed(start);
    sherpa_onnx::PrintSoundSpeakerNpuStats();
    std::cout << "SOUND_DIAR {\"type\":\"result\",\"protocol\":1,\"backend\":\"qnn-htp-hybrid\",\"pid\":"
              << getpid() << ",\"sequence\":" << ++sequence << ",\"elapsedMs\":" << ms << ",\"turns\":[";
    bool first = true;
    for (const auto &segment : result.SortByStartTime()) {
      if (!first) std::cout << ',';
      first = false;
      std::cout << '[' << static_cast<int64_t>(segment.Start()*1000.f) << ','
                << static_cast<int64_t>(segment.End()*1000.f) << ',' << segment.Speaker() << ']';
    }
    std::cout << "]}" << std::endl;
  }
  return 0;
} catch (const std::exception &error) {
  std::cerr << "SOUND_NPU_ERROR " << error.what() << std::endl;
  return 5;
}
