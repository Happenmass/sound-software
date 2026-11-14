#include "qwen_engine.hpp"

#include <array>
#include <iostream>

namespace {
constexpr size_t kMaxSamples = 24 * 16000;

std::vector<float> readWav(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    std::array<char, 12> header{};
    if (!file.read(header.data(), header.size()) ||
        std::memcmp(header.data(), "RIFF", 4) || std::memcmp(header.data() + 8, "WAVE", 4)) {
        throw std::runtime_error("Expected RIFF WAV");
    }

    bool validFormat = false;
    std::array<char, 4> chunk{};
    uint32_t bytes = 0;
    while (file.read(chunk.data(), chunk.size()).read(reinterpret_cast<char*>(&bytes), sizeof(bytes))) {
        if (!std::memcmp(chunk.data(), "fmt ", 4)) {
            std::array<uint16_t, 8> fmt{};
            if (bytes < sizeof(fmt) || !file.read(reinterpret_cast<char*>(fmt.data()), sizeof(fmt)) ||
                fmt[0] != 1 || fmt[1] != 1 || fmt[2] != 16000 || fmt[3] != 0 || fmt[7] != 16) {
                throw std::runtime_error("Expected 16 kHz mono PCM16");
            }
            validFormat = true;
            file.seekg(static_cast<int64_t>(bytes) - sizeof(fmt) + (bytes & 1), std::ios::cur);
        } else if (!std::memcmp(chunk.data(), "data", 4)) {
            if (!validFormat || bytes == 0 || bytes % sizeof(int16_t) || bytes > kMaxSamples * sizeof(int16_t)) {
                throw std::runtime_error("Invalid PCM data length");
            }
            std::vector<int16_t> pcm(bytes / sizeof(int16_t));
            if (!file.read(reinterpret_cast<char*>(pcm.data()), bytes)) {
                throw std::runtime_error("Truncated PCM data");
            }
            std::vector<float> samples(pcm.size());
            std::transform(pcm.begin(), pcm.end(), samples.begin(),
                           [](int16_t sample) { return sample / 32768.0f; });
            return samples;
        } else {
            file.seekg(static_cast<int64_t>(bytes) + (bytes & 1), std::ios::cur);
        }
    }
    throw std::runtime_error("Missing WAV data");
}
}

int main(int argc, char** argv) try {
    if (argc != 3) throw std::runtime_error("Expected runtime and model directory");
    qwen::Engine engine(argv[1], argv[2]);
    std::cout << "SOUND_QWEN_HTP\tready" << std::endl;
    std::string request;
    while (std::getline(std::cin, request) && request != "quit") {
        const auto split = request.find('\t');
        if (split == 0 || split == std::string::npos || request.find_first_not_of("0123456789") != split) {
            throw std::runtime_error("Invalid Qwen request");
        }
        const auto samples = readWav(request.substr(split + 1));
        auto result = engine.transcribe(samples.data(), samples.size(), "auto");
        if (result.token_limit) throw std::runtime_error("Qwen KV capacity exhausted");
        for (char& c : result.text) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
        }
        std::cout << "SOUND_QWEN_HTP\t" << request.substr(0, split) << '\t' << result.text << std::endl;
    }
} catch (const std::exception&) {
    std::cout << "SOUND_QWEN_HTP\terror" << std::endl;
    return 1;
}
