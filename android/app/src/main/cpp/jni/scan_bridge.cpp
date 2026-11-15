#include "core/scanner.h"
#include "root_protocol.h"

#include <atomic>
#include <jni.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <unordered_map>

namespace {
struct Session {
    disksize::ScanConfiguration configuration;
    disksize::CancellationToken cancellation;
    std::atomic<bool> running{false};
};
std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong next_session = 1;

std::shared_ptr<Session> session_for(jlong handle) {
    std::lock_guard lock(sessions_mutex);
    const auto found = sessions.find(handle);
    if (found == sessions.end()) throw std::invalid_argument("Unknown native session");
    return found->second;
}
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        const auto type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, message);
    }
}
std::string hex(const std::string& bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (unsigned char value : bytes) { result += digits[value >> 4]; result += digits[value & 15]; }
    return result;
}
std::string json(const disksize::ScanResult& result) {
    std::ostringstream entries;
    std::ostringstream issues;
    std::size_t count = 0;
    std::size_t issue_count = 0;
    std::size_t budget = 0;
    bool truncated = false;
    // Bounded previews keep Binder transactions and Dart memory independent
    // of tree size. Full indexing/queries are introduced in phase 5.
    for (const auto& node : result.nodes) {
        if (count == 64) { truncated = true; break; }
        const auto path = disksize::relative_path(result, node.id);
        if (path.size() > 4096 || budget + path.size() * 2 > 64 * 1024) {
            truncated = true; continue;
        }
        if (count++ != 0) entries << ',';
        budget += path.size() * 2;
        entries << "{\"pathHex\":\"" << hex(path) << "\",\"kind\":\"" << disksize::node_kind_name(node.kind)
            << "\",\"logicalBytes\":" << node.subtree_logical_bytes
            << ",\"allocatedBytes\":" << node.subtree_allocated_bytes
            << ",\"complete\":" << (node.complete ? "true" : "false")
            << ",\"skipped\":\"" << disksize::skip_reason_name(node.skipped) << "\"}";
    }
    budget = 0;
    for (const auto& issue : result.issues) {
        if (issue_count == 64) { truncated = true; break; }
        const auto path = disksize::relative_path(result, issue.node_id);
        if (issue_count == 64 || path.size() > 4096 || budget + path.size() * 2 > 64 * 1024) {
            truncated = true; continue;
        }
        if (issue_count++ != 0) issues << ',';
        budget += path.size() * 2;
        issues << "{\"pathHex\":\"" << hex(path) << "\",\"code\":\"" << disksize::error_code_name(issue.code)
            << "\",\"operation\":\"" << issue.operation << "\",\"systemError\":" << issue.system_error << '}';
    }
    truncated = truncated || result.summary.dropped_issues != 0;
    std::ostringstream output;
    output << "{\"state\":\"" << disksize::state_name(result.state)
        << "\",\"nodeCount\":" << result.nodes.size() << ",\"logicalBytes\":" << result.summary.logical_bytes
        << ",\"allocatedBytes\":" << result.summary.allocated_bytes
        << ",\"complete\":" << (!result.nodes.empty() && result.nodes.front().complete ? "true" : "false")
        << ",\"issueCount\":" << result.summary.issue_count
        << ",\"previewTruncated\":" << (truncated ? "true" : "false")
        << ",\"peakOpenFds\":" << result.metrics.peak_open_fds
        << ",\"openFdsAtFinish\":" << result.metrics.open_fds_at_finish
        << ",\"entries\":[" << entries.str() << "],\"issues\":[" << issues.str() << "]}";
    return output.str();
}
struct ObserverRef {
    JNIEnv* env;
    jobject reference;
    ~ObserverRef() { if (reference) env->DeleteGlobalRef(reference); }
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeCreateScan(JNIEnv* env, jobject, jbyteArray path) {
    try {
        const auto length = env->GetArrayLength(path);
        auto session = std::make_shared<Session>();
        session->configuration.root_path.resize(length);
        env->GetByteArrayRegion(path, 0, length, reinterpret_cast<jbyte*>(session->configuration.root_path.data()));
        if (env->ExceptionCheck()) return 0;
        session->configuration.max_nodes = 100'000;
        session->configuration.max_issues = 64;
        std::lock_guard lock(sessions_mutex);
        const auto handle = next_session++;
        sessions.emplace(handle, std::move(session));
        return handle;
    } catch (...) { fail(env, "Unable to create native scan"); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeCancelScan(JNIEnv* env, jobject, jlong handle) {
    try { session_for(handle)->cancellation.request(); }
    catch (...) { fail(env, "Unknown native scan"); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeReleaseScan(JNIEnv*, jobject, jlong handle) {
    std::lock_guard lock(sessions_mutex);
    sessions.erase(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeRunScan(JNIEnv* env, jobject, jlong handle, jobject observer) {
    try {
        const auto session = session_for(handle);
        if (session->running.exchange(true)) throw std::logic_error("Scan already running");
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK) throw std::runtime_error("No Java VM");
        ObserverRef global{env, env->NewGlobalRef(observer)};
        if (!global.reference) return nullptr;
        const auto type = env->GetObjectClass(observer);
        const auto method = env->GetMethodID(type, "onProgress", "(JJJJ)V");
        env->DeleteLocalRef(type);
        if (!method) return nullptr;
        std::atomic<bool> callback_failed{false};
        disksize::ScanCallbacks callbacks;
        callbacks.on_progress = [&](const disksize::ScanProgress& progress) {
            JNIEnv* callback_env = nullptr;
            const bool attached = vm->GetEnv(reinterpret_cast<void**>(&callback_env), JNI_VERSION_1_6) == JNI_EDETACHED;
            if (attached && vm->AttachCurrentThread(&callback_env, nullptr) != JNI_OK) {
                callback_failed.store(true); session->cancellation.request(); return;
            }
            callback_env->CallVoidMethod(global.reference, method,
                static_cast<jlong>(progress.discovered_nodes), static_cast<jlong>(progress.observed_logical_bytes),
                static_cast<jlong>(progress.issue_count), static_cast<jlong>(progress.elapsed_microseconds));
            if (callback_env->ExceptionCheck()) {
                callback_env->ExceptionClear(); callback_failed.store(true); session->cancellation.request();
            }
            if (attached) vm->DetachCurrentThread();
        };
        const auto result = disksize::scan_directory(session->configuration, session->cancellation, callbacks);
        if (callback_failed.load()) throw std::runtime_error("Progress consumer failed");
        const auto payload = json(result); // ASCII only; arbitrary filenames stay as hex bytes.
        return env->NewStringUTF(payload.c_str());
    } catch (...) { fail(env, "Native scan failed"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_disksize_scan_NativeBridge_nativeRunScanToPipe(
    JNIEnv* env, jobject, jlong handle, jint output, jbyteArray session_id) {
    try {
        const auto session = session_for(handle);
        if (session->running.exchange(true)) throw std::logic_error("Scan already running");
        if (getuid() != 0 || geteuid() != 0) throw std::runtime_error("Root identity required");
        session->configuration.access_mode = disksize::AccessMode::Root;
        if (env->GetArrayLength(session_id) != 16) throw std::runtime_error("Invalid session UUID");
        disksize::wire::Bytes id(16);
        env->GetByteArrayRegion(session_id, 0, 16, reinterpret_cast<jbyte*>(id.data()));
        if (env->ExceptionCheck()) return;
        disksize::wire::Writer writer(output, std::move(id), session->cancellation);
        disksize::wire::Bytes identity;
        disksize::wire::u32(identity, getuid()); disksize::wire::u32(identity, geteuid());
        disksize::wire::u32(identity, getpid()); writer.frame(1, identity, true);
        disksize::ScanCallbacks callbacks;
        callbacks.on_progress = [&](const disksize::ScanProgress& progress) { writer.progress(progress); };
        const auto result = disksize::scan_directory(session->configuration, session->cancellation, callbacks);
        writer.result(result);
    } catch (...) { fail(env, "Root scan/pipe failed"); }
}
