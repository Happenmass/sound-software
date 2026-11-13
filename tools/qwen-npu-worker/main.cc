#include <onnxruntime_cxx_api.h>

#include <array>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <unordered_map>

struct Htp {
    Ort::Env env{ORT_LOGGING_LEVEL_WARNING,"qwen-sm8850"};
    Ort::SessionOptions options;
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
    Htp(const std::string& runtime) {
        const char* provider = "QNNExecutionProvider";
        env.RegisterExecutionProviderLibrary(provider,(runtime+"/libonnxruntime_providers_qnn.so").c_str());
        std::vector<Ort::ConstEpDevice> selected;
        for (auto device : env.GetEpDevices()) {
            if (selected.empty() && std::strcmp(device.EpName(),provider)==0) selected.push_back(device);
        }
        if (selected.empty()) throw std::runtime_error("No QNN EP device");
        std::unordered_map<std::string,std::string> config;
        config.emplace("backend_path",(runtime+"/libQnnHtp.so").c_str());
        config.emplace("htp_performance_mode","sustained_high_performance");
        options.SetIntraOpNumThreads(1);
        options.SetInterOpNumThreads(1);
        options.AddConfigEntry("session.disable_cpu_ep_fallback","1");
        options.AddConfigEntry("ep.share_ep_contexts","1");
        options.AppendExecutionProvider_V2(env,selected,config);
    }
    Ort::Session open(const std::string& model) {
        return Ort::Session(env,model.c_str(),options);
    }
    template<typename T, size_t N> Ort::Value tensor(std::vector<T>& data,const std::array<int64_t,N>& dims) {
        return Ort::Value::CreateTensor<T>(memory,data.data(),data.size(),dims.data(),N);
    }
    std::vector<Ort::Value> encode(Ort::Session& session,std::vector<float>& mel,std::vector<float>& mask) {
        std::array<Ort::Value,2> inputs = {
            tensor(mel,std::array<int64_t,3>{1,128,800}),
            tensor(mask,std::array<int64_t,4>{1,1,1,104})
        };
        constexpr const char* names[]={"mel","mask"};
        constexpr const char* outputs[]={"feats"};
        return session.Run(Ort::RunOptions{nullptr},names,inputs.data(),inputs.size(),outputs,1);
    }
};

#include <algorithm>
#include <cstdint>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "mel.h"

struct Assets {
    size_t size;
    const unsigned char* data;
    uint32_t count, bytes;
    const uint32_t* tokens;
    const float* scale;
    const uint32_t* offsets;
    const float* filters;
    const char* strings;
    const int8_t* embeddings;

    explicit Assets(const std::string& path) {
        int fd=open(path.c_str(),O_RDONLY | O_CLOEXEC);
        if (fd<0) throw std::runtime_error("Missing assets");
        struct stat info{};
        if (fstat(fd,&info)<0 || info.st_size!=90964736) {
            close(fd);
            throw std::runtime_error("Invalid assets size");
        }
        size=info.st_size;
        data=static_cast<const unsigned char*>(mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0));
        close(fd);
        if (data==MAP_FAILED) throw std::runtime_error("Map assets");
        const auto* header=reinterpret_cast<const uint32_t*>(data);
        count=header[2];
        bytes=header[3];
        if (std::memcmp(data,"QASR",4)!=0 || header[1]!=1 || count!=87193 || bytes!=529798) {
            munmap(const_cast<unsigned char*>(data),size);
            throw std::runtime_error("Invalid assets header");
        }
        tokens=header+4;
        scale=reinterpret_cast<const float*>(tokens+count);
        offsets=reinterpret_cast<const uint32_t*>(scale+count);
        filters=reinterpret_cast<const float*>(offsets+count+1);
        strings=reinterpret_cast<const char*>(filters+128*201);
        embeddings=reinterpret_cast<const int8_t*>(data+size-count*1024);
    }
    ~Assets() { munmap(const_cast<unsigned char*>(data),size); }
    Assets(const Assets&)=delete;
    void embedding(uint32_t id,float* output) const {
        const auto* found=std::lower_bound(tokens,tokens+count,id);
        if (found==tokens+count || *found!=id) throw std::runtime_error("Unknown prompt ID");
        size_t index=found-tokens;
        std::transform(embeddings+index*1024,embeddings+(index+1)*1024,output,
                       [factor=scale[index]](int8_t q) { return q*factor; });
    }
    std::string_view text(size_t id) const {
        if (id>=count || offsets[id+1]>bytes || offsets[id]>offsets[id+1])
            throw std::runtime_error("Bad decoded ID");
        return {strings+offsets[id],offsets[id+1]-offsets[id]};
    }
};

struct Decoder {
    static constexpr int layers=28,heads=8,cache=512,headDim=128,embedDim=1024;
    Htp& htp;
    Ort::Session prefill;
    Ort::Session step;
    std::vector<std::vector<Ort::Float16_t>> kv;
    std::vector<std::string> inputNames{"x","pos","mask"};
    std::vector<const char*> names;
    int length=0;

    Decoder(Htp& htp,const std::string& root):htp(htp),
        prefill(htp.open(root+"/qasr_ar64.sm8850.ctx.onnx")),
        step(htp.open(root+"/qasr_ar1.sm8850.ctx.onnx")),
        kv(2*layers,std::vector<Ort::Float16_t>(heads*cache*headDim)) {
        for (auto* kind : {"past_k_","past_v_"})
            for (int i=0;i<layers;++i) inputNames.emplace_back(kind+std::to_string(i));
        for (const auto& n : inputNames) names.push_back(n.c_str());
    }

    int64_t run(std::vector<float>& x,int ar,int valid) {
        if (valid>ar || length+valid>cache) throw std::runtime_error("Cache full");
        std::vector<int64_t> positions(ar);
        const int width=cache+ar;
        // The graph concatenates the fixed-size cache and this batch's new keys.
        constexpr float closed=-10000.0f, open=0.0f;
        std::vector<float> mask(ar*width,closed);
        for (int i=0;i<ar;++i) {
            positions[i]=length+i;
            std::fill_n(mask.data()+i*width,length,open);
            std::fill_n(mask.data()+i*width+cache,i+1,open);
        }
        std::vector<Ort::Value> inputs;
        inputs.reserve(names.size());
        inputs.push_back(htp.tensor(x,std::array<int64_t,3>{1,ar,embedDim}));
        inputs.push_back(htp.tensor(positions,std::array<int64_t,2>{1,ar}));
        inputs.push_back(htp.tensor(mask,std::array<int64_t,2>{ar,width}));
        for (auto& data : kv) inputs.push_back(htp.tensor(data,std::array<int64_t,4>{1,heads,cache,headDim}));
        constexpr const char* outputs[]={"next_ids","new_k","new_v"};
        auto result=(ar==64?prefill:step).Run(Ort::RunOptions{nullptr},names.data(),inputs.data(),inputs.size(),outputs,3);
        for (int v=0;v<2;++v) {
            const auto* values=result[v+1].GetTensorData<Ort::Float16_t>();
            for (int layer=0;layer<layers;++layer) {
                auto* dest=kv[v*layers+layer].data();
                for (int head=0;head<heads;++head) {
                    const auto* from=values+(layer*heads+head)*ar*headDim;
                    std::copy_n(from,valid*headDim,dest+(head*cache+length)*headDim);
                }
            }
        }
        length+=valid;
        return result[0].GetTensorData<int64_t>()[valid-1];
    }
};

std::string recognize(const std::string& path,Htp& htp,Ort::Session& encoder,
                      Decoder& decoder,const Assets& assets,MelFrontend& frontend) {
    decoder.length=0;
    for (auto& tensor : decoder.kv)
        std::fill(tensor.begin(),tensor.end(),Ort::Float16_t{});
    auto pcm=MelFrontend::readWav(path);
    if (std::all_of(pcm.begin(),pcm.end(),[](float sample){return sample==0;})) return {};
    std::vector<float> mel(128*800), mask(104);
    const int valid=frontend.compute(pcm,assets.filters,mel,mask);
    auto output=htp.encode(encoder,mel,mask);

    // Qwen's empty system turn and audio-only user turn. Audio embeddings replace the pad IDs.
    std::vector<uint32_t> ids={151644,8948,198,151645,198,151644,872,198,151669};
    const size_t audioStart=ids.size();
    ids.insert(ids.end(),valid,151676);
    const size_t audioEnd=ids.size();
    for (uint32_t token : {151670,151645,198,151644,77091,198}) ids.push_back(token);
    const auto* features=output[0].GetTensorData<float>();
    int64_t next=-1;
    for (size_t offset=0;offset<ids.size();offset+=64) {
        int take=std::min<size_t>(64,ids.size()-offset);
        std::vector<float> x(64*Decoder::embedDim);
        for (int i=0;i<take;++i) {
            const size_t index=offset+i;
            float* row=x.data()+i*Decoder::embedDim;
            if (index>=audioStart && index<audioEnd)
                std::copy_n(features+(index-audioStart)*Decoder::embedDim,Decoder::embedDim,row);
            else
                assets.embedding(ids[index],row);
        }
        next=decoder.run(x,64,take);
    }

    std::vector<float> x(Decoder::embedDim);
    std::string text;
    while (true) {
        if (next<0 || static_cast<uint64_t>(next)>=assets.count)
            throw std::runtime_error("Decoded ID out of range");
        const auto orig=assets.tokens[next];
        if (orig==151643 || orig==151645) break;
        if (decoder.length==Decoder::cache) throw std::runtime_error("Decoder cache exhausted");
        text+=assets.text(next);
        assets.embedding(orig,x.data());
        next=decoder.run(x,1,1);
    }
    constexpr std::string_view marker="<asr_text>";
    const auto offset=text.find(marker);
    if (offset!=std::string::npos) text.erase(0,offset+marker.size());
    for (char& c:text) if (c=='\n' || c=='\r' || c=='\t') c=' ';
    return text;
}

int main(int argc,char** argv) try {
    if (argc!=3) throw std::runtime_error("Expected runtime and model directory");
    const std::string model=argv[2];
    Htp htp(argv[1]);
    auto encoder=htp.open(model+"/qasr_enc.sm8850.ctx.onnx");
    Decoder decoder(htp,model);
    Assets assets(model+"/qasr_assets.bin");
    MelFrontend frontend;
    std::cout << "SOUND_QWEN_HTP\tready" << std::endl;
    std::string request;
    while (std::getline(std::cin,request) && request!="quit") {
        const auto split=request.find('\t');
        if (split==0 || split==std::string::npos ||
            request.find_first_not_of("0123456789")!=split)
            throw std::runtime_error("Invalid request");
        const auto result=recognize(request.substr(split+1),htp,encoder,decoder,assets,frontend);
        std::cout << "SOUND_QWEN_HTP\t" << request.substr(0,split) << '\t' << result << std::endl;
    }
} catch (const std::exception&) {
    // ORT writes diagnostics to stderr. Keep the protocol free of paths and transcript text on error.
    std::cout << "SOUND_QWEN_HTP\terror" << std::endl;
    return 1;
}
