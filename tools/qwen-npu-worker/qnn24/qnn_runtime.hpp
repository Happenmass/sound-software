#pragma once
#include <QnnInterface.h>
#include <System/QnnSystemInterface.h>
#include <HTP/QnnHtpDevice.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <unordered_map>

namespace qwen {
inline void checked(Qnn_ErrorHandle_t status,const char* operation) {
    if (status!=QNN_SUCCESS) {
        std::ostringstream text; text<<operation<<" failed: 0x"<<std::hex<<status;
        throw std::runtime_error(text.str());
    }
}

struct SharedBuffer {
    void* pointer = nullptr;
    void (*release)(void*) = nullptr;
    Qnn_MemHandle_t handle = nullptr;
    QnnMem_DeRegisterFn_t deregister = nullptr;
    std::vector<uint32_t> dims;
    Qnn_DataType_t type = QNN_DATATYPE_UNDEFINED;
    ~SharedBuffer() {
        if (handle) deregister(&handle, 1);
        if (pointer) release(pointer);
    }
};

struct TensorBuffer {
    Qnn_Tensor_t tensor;
    std::string name;
    std::vector<uint32_t> dims;
    std::vector<uint8_t> data;
    std::shared_ptr<SharedBuffer> shared;
    explicit TensorBuffer(const Qnn_Tensor_t& source):tensor(source) {
        if (source.version==QNN_TENSOR_VERSION_1) init(source.v1);
        else if (source.version==QNN_TENSOR_VERSION_2) init(source.v2);
        else throw std::runtime_error("Unsupported QNN tensor version");
    }
    template<class T> void init(const T& source) {
        name=source.name;
        dims.assign(source.dimensions,source.dimensions+source.rank);
        size_t count=1; for (auto n:dims) count*=n;
        size_t width;
        if (source.dataType==QNN_DATATYPE_FLOAT_16) width=2;
        else if (source.dataType==QNN_DATATYPE_FLOAT_32 || source.dataType==QNN_DATATYPE_INT_32 || source.dataType==QNN_DATATYPE_UINT_32) width=4;
        else throw std::runtime_error("Unsupported Qwen graph I/O type: "+name);
        data.resize(count*width);
        if (tensor.version==QNN_TENSOR_VERSION_1) bind(tensor.v1);
        else { bind(tensor.v2); tensor.v2.isDynamicDimensions=nullptr; }
    }
    template<class T> void bind(T& target) {
        target.name=name.c_str(); target.dimensions=dims.data();
        target.memType=QNN_TENSORMEMTYPE_RAW;
        target.clientBuf={data.data(),static_cast<uint32_t>(data.size())};
    }
    size_t elements() const { size_t n=1; for(auto d:dims)n*=d; return n; }
    Qnn_DataType_t data_type()const{return tensor.version==QNN_TENSOR_VERSION_1?tensor.v1.dataType:tensor.v2.dataType;}
    bool half() const {return data_type()==QNN_DATATYPE_FLOAT_16;}
    int32_t token_id() const {
        if(elements()!=1 || (data_type()!=QNN_DATATYPE_INT_32 && data_type()!=QNN_DATATYPE_UINT_32))
            throw std::runtime_error("Invalid Qwen token output");
        int32_t id;
        std::memcpy(&id,pointer(),sizeof(id));
        return id;
    }
    const void* pointer()const{return shared ? shared->pointer : data.data();}
    void* pointer(){return shared ? shared->pointer : data.data();}
    float get(size_t index) const { return half()?static_cast<float>(reinterpret_cast<const _Float16*>(pointer())[index]):reinterpret_cast<const float*>(pointer())[index]; }
    void set(size_t index,float value) { if(half())reinterpret_cast<_Float16*>(pointer())[index]=value; else reinterpret_cast<float*>(pointer())[index]=value; }
};

class QnnRuntime {
    void* backend_library_=nullptr;
    void* system_library_=nullptr;
    void* rpc_library_=nullptr;
    void close() {
        if (device) api.deviceFree(device);
        if (backend) api.backendFree(backend);
        device=nullptr; backend=nullptr;
        if(system_library_)dlclose(system_library_);
        if(backend_library_)dlclose(backend_library_);
        if(rpc_library_)dlclose(rpc_library_);
        system_library_=nullptr; backend_library_=nullptr;
    }
public:
    QNN_INTERFACE_VER_TYPE api{};
    QNN_SYSTEM_INTERFACE_VER_TYPE system{};
    Qnn_BackendHandle_t backend=nullptr;
    Qnn_DeviceHandle_t device=nullptr;
    bool shared_io=false;
    void* (*rpc_alloc)(int,unsigned int,int)=nullptr;
    void (*rpc_free)(void*)=nullptr;
    int (*rpc_fd)(void*)=nullptr;
    class PerformanceVote {
        QnnHtpDevice_PerfInfrastructure_t* perf_=nullptr;
        uint32_t id_=0;
    public:
        explicit PerformanceVote(QnnRuntime& runtime) {
            const char* mode=std::getenv("QWEN_HTP_PERFORMANCE");
            if(!mode || std::string(mode)!="1")return;
            QnnDevice_Infrastructure_t infrastructure=nullptr;
            checked(runtime.api.deviceGetInfrastructure(&infrastructure),"QNN performance infrastructure");
            if(!infrastructure || infrastructure->infraType!=QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF)
                throw std::runtime_error("HTP performance infrastructure unavailable");
            perf_=&infrastructure->perfInfra;
            checked(perf_->createPowerConfigId(0,0,&id_),"QNN power client create");
            QnnHtpPerfInfrastructure_PowerConfig_t config{};
            config.option=QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
            auto& vote=config.dcvsV3Config;
            vote.contextId=id_;vote.setDcvsEnable=1;vote.dcvsEnable=0;
            vote.powerMode=QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
            vote.setSleepLatency=1;vote.sleepLatency=40;
            vote.setBusParams=1;
            vote.busVoltageCornerMin=vote.busVoltageCornerTarget=vote.busVoltageCornerMax=DCVS_VOLTAGE_VCORNER_TURBO;
            vote.setCoreParams=1;
            vote.coreVoltageCornerMin=vote.coreVoltageCornerTarget=vote.coreVoltageCornerMax=DCVS_VOLTAGE_VCORNER_TURBO;
            const QnnHtpPerfInfrastructure_PowerConfig_t* configs[]={&config,nullptr};
            auto status=perf_->setPowerConfig(id_,configs);
            if(status!=QNN_SUCCESS){perf_->destroyPowerConfigId(id_);perf_=nullptr;checked(status,"QNN performance vote");}
        }
        ~PerformanceVote(){if(perf_)perf_->destroyPowerConfigId(id_);}
        PerformanceVote(const PerformanceVote&)=delete;
    };
    explicit QnnRuntime(const std::string& directory) {
        try {
            backend_library_=dlopen((directory+"/libQnnHtp.so").c_str(),RTLD_NOW|RTLD_LOCAL);
            if(!backend_library_)throw std::runtime_error(dlerror());
            system_library_=dlopen((directory+"/libQnnSystem.so").c_str(),RTLD_NOW|RTLD_LOCAL);
            if(!system_library_)throw std::runtime_error(dlerror());
            auto providers=reinterpret_cast<decltype(&QnnInterface_getProviders)>(dlsym(backend_library_,"QnnInterface_getProviders"));
            auto system_providers=reinterpret_cast<decltype(&QnnSystemInterface_getProviders)>(dlsym(system_library_,"QnnSystemInterface_getProviders"));
            if(!providers || !system_providers)throw std::runtime_error("Missing QNN provider entry point");
            const QnnInterface_t** list=nullptr; uint32_t count=0;
            checked(providers(&list,&count),"QNN providers");
            bool found=false;
            for(uint32_t i=0;i<count;++i)if(list[i]->apiVersion.coreApiVersion.major==QNN_API_VERSION_MAJOR && list[i]->apiVersion.coreApiVersion.minor>=QNN_API_VERSION_MINOR) {
                api=list[i]->QNN_INTERFACE_VER_NAME; found=true; break;
            }
            if(!found)throw std::runtime_error("QNN backend API does not match compiled SDK");
            const QnnSystemInterface_t** systems=nullptr;
            checked(system_providers(&systems,&count),"QNN system providers");
            found=false;
            for(uint32_t i=0;i<count;++i)if(systems[i]->systemApiVersion.major==QNN_SYSTEM_API_VERSION_MAJOR && systems[i]->systemApiVersion.minor>=QNN_SYSTEM_API_VERSION_MINOR) {
                system=systems[i]->QNN_SYSTEM_INTERFACE_VER_NAME; found=true; break;
            }
            if(!found)throw std::runtime_error("QNN system API does not match compiled SDK");
            checked(api.backendCreate(nullptr,nullptr,&backend),"QNN backend create");
            checked(api.deviceCreate(nullptr,nullptr,&device),"QNN device create");
            const char* shared=std::getenv("QWEN_HTP_SHARED_IO");
            shared_io=shared && std::string(shared)=="1";
            if(shared_io) {
                rpc_library_=dlopen("libcdsprpc.so",RTLD_NOW|RTLD_LOCAL);
                if(!rpc_library_)throw std::runtime_error(dlerror());
                rpc_alloc=reinterpret_cast<decltype(rpc_alloc)>(dlsym(rpc_library_,"rpcmem_alloc"));
                rpc_free=reinterpret_cast<decltype(rpc_free)>(dlsym(rpc_library_,"rpcmem_free"));
                rpc_fd=reinterpret_cast<decltype(rpc_fd)>(dlsym(rpc_library_,"rpcmem_to_fd"));
                if(!rpc_alloc || !rpc_free || !rpc_fd)throw std::runtime_error("Missing FastRPC shared-memory APIs");
            }
        } catch(...) {close();throw;}
    }
    ~QnnRuntime(){close();}
    QnnRuntime(const QnnRuntime&)=delete;
    QnnRuntime& operator=(const QnnRuntime&)=delete;
};

struct QnnLoadedContext {
    QnnRuntime& runtime;
    Qnn_ContextHandle_t handle=nullptr;
    std::unordered_map<std::string, std::weak_ptr<SharedBuffer>> historical_kv;

    explicit QnnLoadedContext(QnnRuntime& runtime):runtime(runtime) {}
    ~QnnLoadedContext(){if(handle)runtime.api.contextFree(handle,nullptr);}
    QnnLoadedContext(const QnnLoadedContext&)=delete;
    QnnLoadedContext& operator=(const QnnLoadedContext&)=delete;
};

class QnnGraph {
    QnnRuntime& runtime_;
    std::shared_ptr<QnnLoadedContext> context_;
    Qnn_GraphHandle_t graph_=nullptr;
    std::vector<Qnn_Tensor_t> input_tensors_,output_tensors_;
    void register_buffer(TensorBuffer& buffer, bool share_kv = false) {
        auto existing = share_kv ? context_->historical_kv[buffer.name].lock() : nullptr;
        if (existing) {
            if (buffer.dims != existing->dims || buffer.data_type() != existing->type)
                throw std::runtime_error("Shared KV layout mismatch: " + buffer.name);
            buffer.shared = std::move(existing);
        } else {
            const size_t bytes = buffer.data.size();
            auto memory = std::make_shared<SharedBuffer>();
            memory->dims = buffer.dims;
            memory->type = buffer.data_type();
            memory->release = runtime_.rpc_free;
            memory->deregister = runtime_.api.memDeRegister;
            memory->pointer = runtime_.rpc_alloc(25, 1, static_cast<int>(bytes));
            if (!memory->pointer) throw std::runtime_error("FastRPC buffer allocation failed: " + buffer.name);
            std::memset(memory->pointer, 0, bytes);
            const int fd = runtime_.rpc_fd(memory->pointer);
            if (fd < 0) throw std::runtime_error("FastRPC buffer FD failed: " + buffer.name);
            Qnn_MemDescriptor_t descriptor = QNN_MEM_DESCRIPTOR_INIT;
            descriptor.memShape = {static_cast<uint32_t>(memory->dims.size()), memory->dims.data(), nullptr};
            descriptor.dataType = memory->type;
            descriptor.memType = QNN_MEM_TYPE_ION;
            descriptor.ionInfo.fd = fd;
            checked(runtime_.api.memRegister(context_->handle, &descriptor, 1, &memory->handle), "QNN shared IO register");
            buffer.shared = std::move(memory);
            if (share_kv) context_->historical_kv[buffer.name] = buffer.shared;
        }
        if (buffer.tensor.version == QNN_TENSOR_VERSION_1) {
            buffer.tensor.v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
            buffer.tensor.v1.memHandle = buffer.shared->handle;
        } else {
            buffer.tensor.v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
            buffer.tensor.v2.memHandle = buffer.shared->handle;
        }
        std::vector<uint8_t>().swap(buffer.data);
    }
    template<class T> void metadata(const T& info) {
        name=info.graphName;
        for(uint32_t i=0;i<info.numGraphInputs;++i)inputs.emplace_back(new TensorBuffer(info.graphInputs[i]));
        for(uint32_t i=0;i<info.numGraphOutputs;++i)outputs.emplace_back(new TensorBuffer(info.graphOutputs[i]));
        for(auto& t:inputs)input_tensors_.push_back(t->tensor);
        for(auto& t:outputs)output_tensors_.push_back(t->tensor);
    }
public:
    std::string name;
    std::vector<std::unique_ptr<TensorBuffer>> inputs,outputs;
    static std::vector<std::unique_ptr<QnnGraph>> load(
        QnnRuntime& runtime,const std::string& file,const std::vector<std::string>& expected_names, bool share_kv = false
    ) {
        std::ifstream stream(file,std::ios::binary|std::ios::ate);
        const auto length=stream.tellg();
        if(length<=0)throw std::runtime_error("Missing QNN context: "+file);
        std::vector<uint8_t> binary(static_cast<size_t>(length));
        stream.seekg(0);
        stream.read(reinterpret_cast<char*>(binary.data()),length);
        if(!stream)throw std::runtime_error("Cannot read QNN context: "+file);

        QnnSystemContext_Handle_t system_context=nullptr;
        checked(runtime.system.systemContextCreate(&system_context),"QNN system context create");
        auto free_system=[&runtime](void* handle){runtime.system.systemContextFree(handle);};
        std::unique_ptr<void,decltype(free_system)> system_guard(system_context,free_system);

        const QnnSystemContext_BinaryInfo_t* info=nullptr;
        Qnn_ContextBinarySize_t info_size=0;
        checked(runtime.system.systemContextGetBinaryInfo(system_context,binary.data(),binary.size(),&info,&info_size),"QNN context metadata");
        const QnnSystemContext_GraphInfo_t* graphs=nullptr;
        uint32_t count=0;
        if(info->version==QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1){graphs=info->contextBinaryInfoV1.graphs;count=info->contextBinaryInfoV1.numGraphs;}
        else if(info->version==QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2){graphs=info->contextBinaryInfoV2.graphs;count=info->contextBinaryInfoV2.numGraphs;}
        else if(info->version==QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3){graphs=info->contextBinaryInfoV3.graphs;count=info->contextBinaryInfoV3.numGraphs;}
        if(count!=expected_names.size())throw std::runtime_error("Unexpected QNN graph count: "+file);

        auto context=std::make_shared<QnnLoadedContext>(runtime);
        checked(runtime.api.contextCreateFromBinary(runtime.backend,runtime.device,nullptr,binary.data(),binary.size(),&context->handle,nullptr),"QNN context load");
        std::vector<std::unique_ptr<QnnGraph>> loaded(count);
        for(uint32_t i=0;i<count;++i) {
            auto graph=std::unique_ptr<QnnGraph>(new QnnGraph(context,graphs[i],share_kv));
            size_t slot=0;
            while(slot<expected_names.size() && graph->name!=expected_names[slot])++slot;
            if(slot==expected_names.size() || loaded[slot])throw std::runtime_error("Unexpected QNN graph: "+graph->name);
            loaded[slot]=std::move(graph);
        }
        return loaded;
    }

    QnnGraph(const QnnGraph&)=delete;
    QnnGraph& operator=(const QnnGraph&)=delete;
private:
    QnnGraph(std::shared_ptr<QnnLoadedContext> context,const QnnSystemContext_GraphInfo_t& info, bool share_kv)
        :runtime_(context->runtime),context_(std::move(context)) {
        if(info.version==QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1)metadata(info.graphInfoV1);
        else if(info.version==QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2)metadata(info.graphInfoV2);
        else if(info.version==QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3)metadata(info.graphInfoV3);
        else throw std::runtime_error("Unsupported QNN graph metadata version");
        checked(runtime_.api.graphRetrieve(context_->handle,name.c_str(),&graph_),"QNN graph retrieve");
        if(runtime_.shared_io) {
            for(auto& buffer:inputs)register_buffer(*buffer, share_kv && buffer->name.starts_with("past_"));
            for(auto& buffer:outputs)register_buffer(*buffer);
            input_tensors_.clear();
            output_tensors_.clear();
            for(auto& buffer:inputs)input_tensors_.push_back(buffer->tensor);
            for(auto& buffer:outputs)output_tensors_.push_back(buffer->tensor);
        }
    }
public:
    void execute(){checked(runtime_.api.graphExecute(graph_,input_tensors_.data(),input_tensors_.size(),output_tensors_.data(),output_tensors_.size(),nullptr,nullptr),"QNN graph execute");}
};
}
