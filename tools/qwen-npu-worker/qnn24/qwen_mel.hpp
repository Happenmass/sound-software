#pragma once
#include <algorithm>
#include <cmath>
#include <complex>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace qwen {
class MelFeatures {
    std::vector<float> filters_;
    std::vector<double> hann_;
    std::vector<std::vector<std::complex<double>>> twiddle_;

    void fft(const double* data, int n, int stride, std::complex<double>* out) const {
        if (n == 25) {
            const auto& roots = twiddle_[25];
            for (int k = 0; k < 25; ++k) {
                std::complex<double> value(0,0);
                for (int t = 0; t < 25; ++t) value += data[t*stride] * roots[(k*t)%25];
                out[k] = value;
            }
            return;
        }
        const int half = n/2;
        std::vector<std::complex<double>> even(half), odd(half);
        fft(data, half, stride*2, even.data());
        fft(data+stride, half, stride*2, odd.data());
        for (int k=0; k<half; ++k) {
            auto rotated = twiddle_[n][k] * odd[k];
            out[k] = even[k] + rotated;
            out[k+half] = even[k] - rotated;
        }
    }

public:
    explicit MelFeatures(const char* filter_file) : hann_(400), twiddle_(401) {
        filters_.resize(201*128);
        std::ifstream input(filter_file, std::ios::binary);
        input.read(reinterpret_cast<char*>(filters_.data()), filters_.size()*sizeof(float));
        if (!input) throw std::runtime_error("Cannot read Qwen mel filters");
        constexpr double pi = 3.14159265358979323846;
        for (int i=0; i<400; ++i) hann_[i] = 0.5*(1-std::cos(2*pi*i/400));
        for (int n : {25,50,100,200,400}) {
            twiddle_[n].resize(n);
            for (int i=0; i<n; ++i) twiddle_[n][i] = std::polar(1.0,-2*pi*i/n);
        }
    }

    // Time-major [padded_frames,128]; padded mel frames are zero.
    std::vector<float> compute(const float* pcm, size_t samples, size_t& valid_frames) const {
        if (samples == 0 || samples > 24 * 16000) throw std::runtime_error("Qwen audio must contain 1 to 384000 samples");
        const size_t length = std::max<size_t>(samples,8000);
        valid_frames = length/160;
        const size_t padded_frames = ((valid_frames+99)/100)*100;
        std::vector<float> result(padded_frames*128,0);
        double frame[400];
        std::complex<double> spectrum[400];
        double power[201];
        float maximum = -1e30f;
        for (size_t t=0; t<valid_frames; ++t) {
            for (int i=0; i<400; ++i) {
                long index = static_cast<long>(t*160)+i-200;
                if (index<0) index = -index;
                if (index>=static_cast<long>(length)) index = 2*length-2-index;
                frame[i] = (static_cast<size_t>(index)<samples ? pcm[index] : 0) * hann_[i];
            }
            fft(frame,400,1,spectrum);
            for (int bin=0; bin<201; ++bin) power[bin] = std::norm(spectrum[bin]);
            for (int mel=0; mel<128; ++mel) {
                double value = 0;
                for (int bin=0; bin<201; ++bin) value += power[bin]*filters_[bin*128+mel];
                const float log_value = std::log10(std::max(value,1e-10));
                result[t*128+mel] = log_value;
                maximum = std::max(maximum,log_value);
            }
        }
        for (size_t i=0; i<valid_frames*128; ++i)
            result[i] = (std::max(result[i],maximum-8)+4)/4;
        return result;
    }
};
}
