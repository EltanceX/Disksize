#include "core/stream_scanner.h"
#include "../android/app/src/main/cpp/jni/index_protocol.h"
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <unistd.h>

namespace fs = std::filesystem;
using namespace disksize;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
int main() {
    char pattern[] = "/data/local/tmp/disksize-stream-XXXXXX";
    const char* root = mkdtemp(pattern);
    if (!root) return 1;
    try {
        fs::create_directory(std::string(root) + "/nested");
        for (int i = 0; i < 20000; ++i) {
            const auto path = std::string(root) + "/nested/f" + std::to_string(i);
            const int fd = open(path.c_str(), O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
            require(fd >= 0, "create fixture"); close(fd);
        }
        const std::string raw = std::string(root) + "/raw\xff\n";
        const int fd = open(raw.c_str(), O_CREAT | O_WRONLY, 0600);
        require(fd >= 0 && write(fd, "abc", 3) == 3, "raw fixture"); close(fd);
        require(link(raw.c_str(), (std::string(root) + "/alias").c_str()) == 0, "link");
        require(symlink("nested", (std::string(root) + "/link").c_str()) == 0, "symlink");
        ScanConfiguration c; c.root_path = root;
        std::uint64_t count = 0, closures = 0; bool raw_seen = false;
        StreamCallbacks cb;
        cb.on_nodes = [&](std::span<const ScanNode> nodes) {
            require(nodes.size() == 1, "unbounded discovery");
            for (const auto& n : nodes) {
                require(n.id == ++count && n.parent_id < n.id, "discovery order");
                if (n.name == std::string("raw\xff\n")) raw_seen = n.raw_size == 3 && n.metadata_valid;
            }
        };
        cb.on_directory = [&](const ScanNode& n) { require(n.complete && n.directory_enumerated, "directory closure"); ++closures; };
        CancellationToken token;
        const auto result = stream_directory(c, token, cb);
        require(result.scan.state == SessionState::Completed && count == 20005 && closures == 2 && raw_seen, "stream result");
        require(result.scan.nodes.empty() && result.scan.issues.empty(), "retained full tree");
        require(result.scan.metrics.peak_open_fds <= 6 && result.scan.metrics.open_fds_at_finish == 0, "fd bounds");
        CancellationToken cancel;
        cb.on_directory = {};
        cb.on_nodes = [&](std::span<const ScanNode> nodes) { if (nodes.front().id == 10) cancel.request(); };
        const auto cancelled = stream_directory(c, cancel, cb);
        require(cancelled.scan.state == SessionState::Cancelled && cancelled.node_count == 10 && cancelled.scan.metrics.open_fds_at_finish == 0, "cancel");
        CancellationToken limit;
        c.max_nodes = 11;
        cb.on_nodes = {};
        const auto limited = stream_directory(c, limit, cb);
        require(limited.scan.state == SessionState::Partial && limited.node_count == 11, "limit");
        CancellationToken fail;
        cb.on_nodes = [](std::span<const ScanNode>) { throw std::runtime_error("consumer"); };
        const auto failed = stream_directory(c, fail, cb);
        require(failed.scan.state == SessionState::Failed && failed.scan.metrics.open_fds_at_finish == 0, "consumer failure");
        fs::remove_all(root);
        std::cout << "{\"suite\":\"stream\",\"tests\":4,\"failed\":0}\n";
        return 0;
    } catch (const std::exception& e) { fs::remove_all(root); std::cerr << e.what() << '\n'; return 1; }
}
