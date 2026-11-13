// App-owned sequential worker; pinned sherpa recognizer and QNN context live for this process.
#include <chrono>
#include <iostream>
#include <string>
#include <unistd.h>
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/wave-reader.h"

int main(int argc, char** argv) {
  sherpa_onnx::ParseOptions options("Sound QNN worker protocol 1; sense-voice QNN flags, sequential stdin requests.");
  sherpa_onnx::OfflineRecognizerConfig config;
  config.Register(&options);
  options.Read(argc, argv);
  if (options.NumArgs() || config.model_config.provider != "qnn" || !config.Validate()) return 2;
  const auto start = std::chrono::steady_clock::now();
  sherpa_onnx::OfflineRecognizer recognizer(config);  // Exactly one model/context initialization.
  const auto init_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-start).count();
  std::cout << "SOUND_QNN {\"type\":\"ready\",\"protocol\":1,\"pid\":" << getpid()
            << ",\"context\":1,\"initializations\":1,\"loadMs\":" << init_ms << "}" << std::endl;
  std::string line;
  int sequence = 0;
  while (std::getline(std::cin, line)) {
    if (line == "quit") break;
    const auto tab = line.find('\t');
    if (line.size() > 8192 || tab == std::string::npos) return 3;
    const auto request = line.substr(0,tab);
    if (request != std::to_string(sequence+1)) return 3;
    const auto path = line.substr(tab+1);
    int32_t rate = 0; bool ok = false;
    auto samples = sherpa_onnx::ReadWave(path, &rate, &ok);
    if (!ok || rate != 16000 || samples.empty() || samples.size() > 160000) return 4;
    const auto begin = std::chrono::steady_clock::now();
    auto stream = recognizer.CreateStream();
    stream->AcceptWaveform(rate, samples.data(), samples.size());
    auto pointer = stream.get();
    recognizer.DecodeStreams(&pointer, 1);
    const auto elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-begin).count();
    ++sequence;
    std::cout << "SOUND_QNN {\"type\":\"result\",\"protocol\":1,\"pid\":" << getpid()
              << ",\"context\":1,\"initializations\":1,\"sequence\":" << sequence
              << ",\"request\":" << request << ",\"inferenceMs\":" << elapsed
              << ",\"result\":" << stream->GetResult().AsJsonString() << "}" << std::endl;
  }
  return 0;
}
