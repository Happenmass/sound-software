#pragma once
#include "qnn_runtime.hpp"
#include "qwen_mel.hpp"
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <string_view>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace qwen {
inline TensorBuffer& named(std::vector<std::unique_ptr<TensorBuffer>>& tensors,const std::string& name) {
    for(auto& tensor:tensors)if(tensor->name==name)return *tensor;
    throw std::runtime_error("Missing QNN tensor: "+name);
}

// All non-singleton dimensions in these exported graph IO shapes are unique.
// Singleton-axis permutations have identical storage, so shape matching also
// handles converter outputs whose permutation metadata is empty.
class TensorView {
    TensorBuffer& tensor_;
    std::vector<uint32_t> shape_;
    std::vector<size_t> strides_;
public:
    TensorView(TensorBuffer& tensor,std::vector<uint32_t> shape):tensor_(tensor),shape_(std::move(shape)) {
        if(shape_.size()!=tensor.dims.size())throw std::runtime_error("Tensor rank mismatch: "+tensor.name);
        std::vector<bool> used(shape_.size(),false);
        std::vector<size_t> native_stride(shape_.size());
        size_t stride=1;
        for(int i=static_cast<int>(shape_.size())-1;i>=0;--i){native_stride[i]=stride;stride*=tensor.dims[i];}
        strides_.resize(shape_.size());
        for(size_t source=0;source<shape_.size();++source) {
            bool found=false;
            for(size_t native=0;native<shape_.size();++native)if(!used[native] && tensor.dims[native]==shape_[source]) {
                used[native]=true;strides_[source]=native_stride[native];found=true;break;
            }
            if(!found)throw std::runtime_error("Tensor layout mismatch: "+tensor.name);
        }
    }
    size_t native(size_t flat) const {
        size_t result=0;
        for(int axis=static_cast<int>(shape_.size())-1;axis>=0;--axis){result+=(flat%shape_[axis])*strides_[axis];flat/=shape_[axis];}
        return result;
    }
    bool contiguous(size_t axis)const{return strides_[axis]==1;}
    float get(size_t flat)const{return tensor_.get(native(flat));}
    void set(size_t flat,float value){tensor_.set(native(flat),value);}
};

template<class T> std::vector<T> read_values(const std::string& file) {
    std::ifstream stream(file,std::ios::binary|std::ios::ate);
    auto bytes=stream.tellg();
    if(bytes<=0 || static_cast<size_t>(bytes)%sizeof(T))throw std::runtime_error("Invalid model file: "+file);
    std::vector<T> values(static_cast<size_t>(bytes)/sizeof(T));stream.seekg(0);
    stream.read(reinterpret_cast<char*>(values.data()),bytes);
    if(!stream)throw std::runtime_error("Cannot read model file: "+file);
    return values;
}

class Embeddings {
    static constexpr size_t vocabulary=151936,width=1024,bytes=vocabulary*width;
    std::vector<float> scales_;
    const int8_t* data_=nullptr;
public:
    explicit Embeddings(const std::string& directory)
        :scales_(read_values<float>(directory+"/embedding-scales.f32")) {
        if(scales_.size()!=vocabulary)throw std::runtime_error("Qwen embedding scale count mismatch");
        const auto file=directory+"/embedding.int8";
        int fd=open(file.c_str(),O_RDONLY);
        if(fd<0)throw std::runtime_error("Cannot open Qwen embeddings");
        struct stat info{};
        if(fstat(fd,&info)!=0 || info.st_size!=bytes){close(fd);throw std::runtime_error("Qwen embedding size mismatch");}
        void* mapped=mmap(nullptr,bytes,PROT_READ,MAP_PRIVATE,fd,0);
        close(fd);
        if(mapped==MAP_FAILED)throw std::runtime_error("Cannot map Qwen embeddings");
        data_=static_cast<const int8_t*>(mapped);
    }

    ~Embeddings(){if(data_)munmap(const_cast<int8_t*>(data_),bytes);}
    Embeddings(const Embeddings&)=delete;
    Embeddings& operator=(const Embeddings&)=delete;
    void row(int token,float* output)const {
        if(token<0 || static_cast<size_t>(token)>=vocabulary)throw std::runtime_error("Invalid Qwen token ID");
        const auto* weights=data_+static_cast<size_t>(token)*width;
        const float scale=scales_[token];
        for(size_t i=0;i<width;++i)output[i]=weights[i]*scale;
    }
};

struct Transcription {
    std::string raw,text,language;
    std::vector<int> tokens;
    double mel_ms=0,encoder_ms=0,decoder_ms=0;
    double prefill_ms=0,decoder_graph_ms=0;
    double prefill_graph_ms=0, prefill_kv_ms=0, decode_kv_ms=0;
    int prefill_calls=0, decode_calls=0;
    bool token_limit=false;
};

class Engine {
    double decoder_graph_ms_=0;
    static constexpr int capacity=1024,prefill_size=64,width=1024;
    QnnRuntime runtime_;
    MelFeatures mel_;
    Embeddings embeddings_;
    std::string directory_;
    std::unique_ptr<QnnGraph> cnn_,window_,prefill_,decode_;
    bool shared_history_ = false;
    double prefill_kv_ms_ = 0, decode_kv_ms_ = 0;
    int prefill_calls_ = 0, decode_calls_ = 0;
    std::vector<int32_t> token_offsets_;
    std::vector<char> token_bytes_;
    using Clock=std::chrono::steady_clock;
    static double milliseconds(Clock::time_point start){return std::chrono::duration<double,std::milli>(Clock::now()-start).count();}
    static std::string trim(std::string text) {
        auto start=text.find_first_not_of(" \t\r\n");
        if(start==std::string::npos)return "";
        return text.substr(start,text.find_last_not_of(" \t\r\n")-start+1);
    }
    std::vector<float> audio(const std::vector<float>& mel,size_t frames) {
        std::vector<float> packed;
        for(size_t start=0;start<frames;start+=100) {
            TensorView input(named(cnn_->inputs,"mel"),{1,128,100});
            for(size_t m=0;m<128;++m)for(size_t t=0;t<100;++t)input.set(m*100+t,mel[(start+t)*128+m]);
            cnn_->execute();
            TensorView output(named(cnn_->outputs,"audio_hidden"),{1,13,896});
            size_t tokens=(std::min<size_t>(100,frames-start)+7)/8;
            for(size_t i=0;i<tokens*896;++i)packed.push_back(output.get(i));
        }
        size_t total=packed.size()/896;
        std::vector<float> result;result.reserve(total*1024);
        for(size_t start=0;start<total;start+=104) {
            size_t tokens=std::min<size_t>(104,total-start);
            TensorView input(named(window_->inputs,"hidden"),{1,104,896});
            TensorView mask(named(window_->inputs,"key_mask"),{1,1,1,104});
            for(size_t i=0;i<104*896;++i)input.set(i,i<tokens*896?packed[start*896+i]:0);
            for(size_t i=0;i<104;++i)mask.set(i,i<tokens?0:-10000);
            window_->execute();
            TensorView output(named(window_->outputs,"audio_hidden"),{1,104,1024});
            for(size_t i=0;i<tokens*1024;++i)result.push_back(output.get(i));
        }
        return result;
    }
    int decoder(const float* hidden,int valid,int position,bool prefill) {
        const int sequence=prefill?prefill_size:1;
        if(valid<=0 || valid>sequence || position+valid>capacity)throw std::runtime_error("Invalid Qwen cache update");
        QnnGraph& graph=*(prefill?prefill_:decode_);
        TensorView input(named(graph.inputs,"hidden"),{1,static_cast<uint32_t>(sequence),width});
        for(size_t i=0;i<sequence*width;++i)input.set(i,i<static_cast<size_t>(valid*width)?hidden[i]:0);
        TensorView cosine(named(graph.inputs,"cos"),{1,1,static_cast<uint32_t>(sequence),128});
        TensorView sine(named(graph.inputs,"sin"),{1,1,static_cast<uint32_t>(sequence),128});
        for(int token=0;token<sequence;++token)for(int feature=0;feature<128;++feature) {
            const double angle=(position+token)*std::pow(1000000.0,-2.0*(feature%64)/128);
            cosine.set(token*128+feature,std::cos(angle));
            sine.set(token*128+feature,std::sin(angle));
        }
        TensorView mask(named(graph.inputs,"attention_mask"),{1,1,static_cast<uint32_t>(sequence),static_cast<uint32_t>(capacity+sequence)});
        for(int row=0;row<sequence;++row)for(int key=0;key<capacity+sequence;++key)
            mask.set(row*(capacity+sequence)+key,(key<position || (key>=capacity && key-capacity<=row && key-capacity<valid))?0:-10000);
        if(prefill) {
            auto& last=named(graph.inputs,"last_row");
            for(int token=0;token<sequence;++token)last.set(token,token==valid-1?1.f:0.f);
        }
        const auto execution_start=Clock::now();
        graph.execute();
        decoder_graph_ms_+=milliseconds(execution_start);
        const int id=named(graph.outputs,"next_id").token_id();
        if(id<0 || id>=151936)throw std::runtime_error("Invalid Qwen token output");
        const auto write_start=Clock::now();
        for(int layer=0;layer<28;++layer)for(const std::string kind:{"key","value"}) {
            const auto suffix=kind+"_"+std::to_string(layer);
            auto& delta=named(graph.outputs,"new_"+suffix);
            TensorView from(delta,{1,8,static_cast<uint32_t>(sequence),128});
            auto update=[&](QnnGraph& target) {
                auto& cache=named(target.inputs,"past_"+suffix);
                TensorView to(cache,{1,8,capacity,128});
                for(int head=0;head<8;++head)for(int token=0;token<valid;++token) {
                    const size_t src=(head*sequence+token)*128;
                    const size_t dst=(head*capacity+position+token)*128;
                    if(delta.half() && cache.half() && from.contiguous(3) && to.contiguous(3)) {
                        const auto* src_data=static_cast<const _Float16*>(delta.pointer());
                        auto* dst_data=static_cast<_Float16*>(cache.pointer());
                        std::memcpy(dst_data+to.native(dst),src_data+from.native(src),128*sizeof(_Float16));
                    } else {
                        for(int feature=0;feature<128;++feature)to.set(dst+feature,from.get(src+feature));
                    }
                }
            };
            update(graph);
            if(prefill && !shared_history_)update(*decode_);
        }
        if (prefill) {
            prefill_kv_ms_ += milliseconds(write_start);
            ++prefill_calls_;
        } else {
            decode_kv_ms_ += milliseconds(write_start);
            ++decode_calls_;
        }
        return id;
    }
public:
    Engine(const std::string& library,const std::string& directory):runtime_(library),mel_((directory+"/mel_filters.f32").c_str()),
        embeddings_(directory),directory_(directory) {
        cnn_=std::move(QnnGraph::load(runtime_,directory+"/audio_cnn.bin",{"audio_cnn"}).front());
        window_=std::move(QnnGraph::load(runtime_,directory+"/audio_window.bin",{"audio_window"}).front());
        shared_history_ = runtime_.shared_io;
        auto graphs=QnnGraph::load(runtime_,directory+"/decoder.bin",{"gqa64","gqa"},shared_history_);
        for (int layer=0; layer<28; ++layer) for (const std::string kind : {"key","value"}) {
            const auto name="past_"+kind+"_"+std::to_string(layer);
            const auto& prefill_cache=named(graphs[0]->inputs,name);
            const auto& decode_cache=named(graphs[1]->inputs,name);
            if (shared_history_ && (!prefill_cache.shared || prefill_cache.shared != decode_cache.shared))
                throw std::runtime_error("Historical KV registration was not shared: " + name);
        }
        prefill_=std::move(graphs[0]);
        decode_=std::move(graphs[1]);
        token_offsets_=read_values<int32_t>(directory+"/token_offsets.i32");
        token_bytes_=read_values<char>(directory+"/token_bytes.bin");
        if(token_offsets_.size()!=151937 || token_offsets_.back()!=static_cast<int32_t>(token_bytes_.size()))throw std::runtime_error("Qwen vocabulary size mismatch");
    }
    Transcription transcribe(const float* pcm,size_t samples,const std::string& language,int max_tokens=1024) {
        QnnRuntime::PerformanceVote performance(runtime_);
        decoder_graph_ms_=0;
        prefill_kv_ms_=decode_kv_ms_=0;
        prefill_calls_=decode_calls_=0;
        Transcription result;result.language=language;
        auto start=Clock::now();size_t frames=0;
        auto mel=mel_.compute(pcm,samples,frames);result.mel_ms=milliseconds(start);
        start=Clock::now();auto audio_embeddings=audio(mel,frames);result.encoder_ms=milliseconds(start);
        auto prefix=read_values<int32_t>(directory_+"/prompt_"+language+"_prefix.i32");
        auto suffix=read_values<int32_t>(directory_+"/prompt_"+language+"_suffix.i32");
        const int audio_tokens=audio_embeddings.size()/width;
        const int prompt_tokens=prefix.size()+audio_tokens+suffix.size();
        if(prompt_tokens>=capacity)throw std::runtime_error("Qwen audio prompt exceeds KV capacity");
        std::vector<float> prompt(prompt_tokens*width);
        for(size_t i=0;i<prefix.size();++i)embeddings_.row(prefix[i],prompt.data()+i*width);
        std::copy(audio_embeddings.begin(),audio_embeddings.end(),prompt.begin()+prefix.size()*width);
        for(size_t i=0;i<suffix.size();++i)embeddings_.row(suffix[i],prompt.data()+(prefix.size()+audio_tokens+i)*width);
        start=Clock::now();
        int position=0,next=-1;
        while(position<prompt_tokens) {
            const int count=std::min(prefill_size,prompt_tokens-position);
            next=decoder(prompt.data()+position*width,count,position,true);
            position+=count;
        }
        result.prefill_ms=milliseconds(start);
        result.prefill_graph_ms=decoder_graph_ms_;
        const int limit=std::min(max_tokens,capacity-position);
        bool ended=false;
        std::vector<float> embedding(width);
        for(int step=0;step<limit;++step) {
            if(next==151643 || next==151645){ended=true;break;}
            result.tokens.push_back(next);
            result.raw.append(token_bytes_.data()+token_offsets_[next],token_offsets_[next+1]-token_offsets_[next]);
            if(step+1<limit){
                embeddings_.row(next,embedding.data());
                next=decoder(embedding.data(),1,position,false);
                ++position;
            }
        }
        result.decoder_ms=milliseconds(start);
        result.decoder_graph_ms=decoder_graph_ms_;
        result.prefill_kv_ms=prefill_kv_ms_;
        result.decode_kv_ms=decode_kv_ms_;
        result.prefill_calls=prefill_calls_;
        result.decode_calls=decode_calls_;
        result.token_limit=!ended;
        auto marker=result.raw.find("<asr_text>");
        result.text=trim(marker==std::string::npos?result.raw:result.raw.substr(marker+10));
        if(language=="auto" && marker!=std::string::npos){auto tag=result.raw.substr(0,marker);auto label=tag.find("language ");if(label!=std::string::npos)result.language=trim(tag.substr(label+9));}
        return result;
    }
};
}
