#include <kiss_fftr.h>
#include <cmath>

struct MelFrontend {
    static constexpr int fftSize=400, hop=160, frames=800, bins=128;
    std::unique_ptr<kiss_fftr_state,decltype(&free)> fft{kiss_fftr_alloc(fftSize,0,nullptr,nullptr),free};
    std::array<float,fftSize> hann{};
    explicit MelFrontend() {
        if (!fft) throw std::bad_alloc();
        for (int i=0;i<fftSize;++i) hann[i]=0.5-0.5*std::cos(2*std::acos(-1.0)*i/fftSize);
    }

    static std::vector<float> readWav(const std::string& path) {
        std::ifstream file(path,std::ios::binary);
        std::array<char,12> header{};
        if (!file.read(header.data(),header.size()) || std::memcmp(header.data(),"RIFF",4) ||
            std::memcmp(header.data()+8,"WAVE",4)) throw std::runtime_error("Not a RIFF WAV");
        bool validFormat=false;
        while (file) {
            std::array<char,4> id{};
            uint32_t size=0;
            file.read(id.data(),id.size()).read(reinterpret_cast<char*>(&size),sizeof(size));
            if (!file) break;
            if (!std::memcmp(id.data(),"fmt ",4)) {
                std::array<uint16_t,8> fmt{};
                if (size<16 || !file.read(reinterpret_cast<char*>(fmt.data()),16) ||
                    fmt[0]!=1 || fmt[1]!=1 || fmt[2]!=16000 || fmt[3]!=0 || fmt[7]!=16)
                    throw std::runtime_error("Requires 16 kHz mono PCM16");
                file.seekg(size-16+(size&1),std::ios::cur);
                validFormat=true;
            } else if (!std::memcmp(id.data(),"data",4)) {
                if (!validFormat || size==0 || size%2!=0 || size>frames*hop*2)
                    throw std::runtime_error("Invalid WAV data length");
                std::vector<int16_t> shorts(size/2);
                if (!file.read(reinterpret_cast<char*>(shorts.data()),size))
                    throw std::runtime_error("Truncated WAV");
                std::vector<float> result(shorts.size());
                std::transform(shorts.begin(),shorts.end(),result.begin(),[](int16_t sample){ return sample/32768.0f; });
                return result;
            } else file.seekg(static_cast<int64_t>(size)+(size&1),std::ios::cur);
        }
        throw std::runtime_error("WAV data chunk missing");
    }

    int compute(const std::vector<float>& pcm,const float* filter,std::vector<float>& mel,std::vector<float>& mask) {
        std::array<float,fftSize> window;
        std::array<kiss_fft_cpx,fftSize/2+1> spectrum;
        std::array<float,bins> power;
        float maxLog=-10;
        for (int frame=0;frame<frames;++frame) {
            for (int i=0;i<fftSize;++i) {
                int index=frame*hop+i-fftSize/2;
                if (index<0) index=-index;
                if (index>=frames*hop) index=2*frames*hop-index-2;
                window[i]=index<static_cast<int>(pcm.size()) ? pcm[index]*hann[i] : 0;
            }
            kiss_fftr(fft.get(),window.data(),spectrum.data());
            power.fill(0);
            for (size_t i=0;i<spectrum.size();++i) {
                auto bin=spectrum[i];
                float value=bin.r*bin.r+bin.i*bin.i;
                for (int k=0;k<bins;++k) power[k]+=value*filter[i*bins+k];
            }
            for (int k=0;k<bins;++k) {
                float value=std::log10(std::max(power[k],1e-10f));
                mel[k*frames+frame]=value;
                maxLog=std::max(maxLog,value);
            }
        }
        for (auto& value:mel) value=(std::max(value,maxLog-8)+4)/4;
        int n=pcm.size()/hop;
        int valid=13*(n/100)+((n%100)+7)/8;
        if (valid==0) valid=1;
        std::fill(mask.begin()+valid,mask.end(),-10000);
        return valid;
    }
};
