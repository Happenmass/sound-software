#pragma once
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>
namespace sherpa_onnx {
inline int SoundSpeakerWorkers(){const char* v=std::getenv("SOUND_SPEAKER_WORKERS");return v?std::clamp(std::atoi(v),1,4):1;}
template<class Fn> void SoundSpeakerParallelFor(int count,Fn fn){
 const int workers=std::min(count,SoundSpeakerWorkers());
 if(workers<=1){for(int i=0;i<count;++i)fn(i);return;}
 std::atomic<int> next{0};std::atomic<bool> stopped{false};std::exception_ptr error;std::mutex mutex;std::vector<std::thread> threads;
 for(int i=0;i<workers;++i)threads.emplace_back([&]{try{while(!stopped){int k=next.fetch_add(1);if(k>=count)break;fn(k);}}catch(...){std::lock_guard<std::mutex> lock(mutex);if(!error)error=std::current_exception();stopped=true;}});
 for(auto& thread:threads)thread.join();if(error)std::rethrow_exception(error);
}
}
