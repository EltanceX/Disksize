#include "core/stream_scanner.h"
#include "../android/app/src/main/cpp/jni/index_protocol.h"
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <unistd.h>
#include <sched.h>
#include <sys/mount.h>
#include <sys/wait.h>

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
        require(result.volume.valid && result.volume.total > 0 && result.volume.free <= result.volume.total &&
            result.volume.available <= result.volume.free && result.volume.captured_millis > 0, "volume sample");
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
        // A retained parent descriptor cannot be redirected through a replaced entry.
        c.max_nodes = 100000; cb = {};
        fs::create_directory(std::string(root) + "/replace");
        bool replaced = false, replacement_reported = false;
        cb.on_nodes = [&](std::span<const ScanNode> nodes) {
            if (!replaced && nodes.front().kind == NodeKind::Directory && nodes.front().name == "replace") {
                fs::rename(std::string(root) + "/replace", std::string(root) + "/moved");
                require(symlink("nested", (std::string(root) + "/replace").c_str()) == 0, "replace symlink"); replaced = true;
            }
        };
        cb.on_issue = [&](const ScanIssue& i) { if (i.operation == "open-directory") replacement_reported = true; };
        CancellationToken mutation;
        const auto changed = stream_directory(c, mutation, cb);
        require(replaced && replacement_reported && changed.scan.state == SessionState::Partial, "replacement escaped");
        // Long-lived directory FDs are bounded by depth, not width or node count.
        const auto deep = std::string(root) + "/deep"; fs::create_directory(deep);
        std::string path = deep;
        for (int i = 0; i < 100; ++i) { path += "/level"; fs::create_directory(path); }
        c.root_path = deep; cb = {}; CancellationToken depth;
        const auto deep_result = stream_directory(c, depth, cb);
        require(deep_result.scan.state == SessionState::Completed && deep_result.node_count == 101 &&
            deep_result.scan.metrics.peak_open_fds <= 103 && deep_result.scan.metrics.open_fds_at_finish == 0, "deep fd bounds");
        c.root_path = "/proc"; CancellationToken pseudo;
        require(stream_directory(c, pseudo, cb).scan.state == SessionState::Partial, "pseudo filesystem");
        fs::create_directory(std::string(root) + "/bind");
        const auto pid = fork(); require(pid >= 0, "fork");
        if (pid == 0) {
            if (unshare(CLONE_NEWNS) || mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr)) _exit(77);
            if (mount((std::string(root) + "/nested").c_str(), (std::string(root) + "/bind").c_str(), nullptr, MS_BIND, nullptr)) _exit(1);
            c.root_path = root; bool boundary = false;
            cb.on_nodes = [&](std::span<const ScanNode> nodes) {
                if (nodes.front().name == "bind") boundary = nodes.front().skipped == SkipReason::MountBoundary;
            };
            CancellationToken mounted;
            const auto scanned = stream_directory(c, mounted, cb);
            _exit(boundary && scanned.scan.state == SessionState::Partial ? 0 : 1);
        }
        int status = 0; require(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0, "mount boundary");
        fs::remove_all(root);
        std::cout << "{\"suite\":\"stream\",\"tests\":8,\"failed\":0}\n";
        return 0;
    } catch (const std::exception& e) { fs::remove_all(root); std::cerr << e.what() << '\n'; return 1; }
}
