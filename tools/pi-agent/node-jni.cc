#include <jni.h>
#include <node.h>
#include <string>

namespace {
std::string stringFromJava(JNIEnv* env, jstring value) {
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) return {};
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_soundsoftware_app_agent_PiNode_run(JNIEnv* env, jobject, jstring script, jstring socket) {
    std::string executable = "soundsoftware-node";
    std::string scriptPath = stringFromJava(env, script);
    std::string socketPath = stringFromJava(env, socket);
    if (env->ExceptionCheck()) return -1;
    char* argv[] = {executable.data(), scriptPath.data(), socketPath.data(), nullptr};
    return node::Start(3, argv);
}
