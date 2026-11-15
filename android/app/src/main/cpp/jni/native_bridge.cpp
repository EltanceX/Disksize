#include "core/diagnostics.h"

#include <array>
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(disksize::kCoreVersion);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeAbi(JNIEnv* env, jobject) {
    return env->NewStringUTF(disksize::native_abi());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject) {
    const auto info = disksize::read_runtime_info();
    const std::array<jlong, 7> values{
        disksize::kProtocolVersion, info.process_id, info.uid, info.effective_uid,
        info.page_size_bytes, info.pointer_bits, info.self_test_passed ? 1 : 0,
    };
    const auto length = static_cast<jsize>(values.size());
    jlongArray result = env->NewLongArray(length);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, length, values.data());
    }
    return result;
}
