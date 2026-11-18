#include "core/stream_scanner.h"
#include "../android/app/src/main/cpp/jni/index_protocol.h"
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <unistd.h>
#include <sched.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <tuple>

namespace fs = std::filesystem;
using namespace disksize;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }

auto metadata_fields(const ScanNode& n) {
    return std::tie(n.id, n.parent_id, n.name, n.symlink_target, n.kind, n.depth, n.mode, n.uid,
        n.gid, n.device, n.inode, n.mount_id, n.link_count, n.raw_size, n.raw_allocated_bytes,
        n.mtime_seconds, n.mtime_nanoseconds, n.metadata_valid, n.complete, n.skipped);
}

// Compare the full v4 byte stream apart from the nondeterministic timing
// counters. Use a file so this encoder check doesn't depend on scheduling.
void check_v4_buffers(const std::string& root) {
    using namespace disksize::wire;
    const auto file = root + "/wire";
    const int fd = open(file.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    require(fd >= 0 && unlink(file.c_str()) == 0, "wire fixture");
    const auto encode = [&](bool reuse) {
        require(ftruncate(fd, 0) == 0 && lseek(fd, 0, SEEK_SET) == 0, "reset wire fixture");
        CancellationToken token;
        {
            IndexWriter writer(fd, Bytes(16, 0xcd), token, reuse);
            ScanNode n; n.id = 1; n.kind = NodeKind::Directory; writer.node(n);
            n.parent_id = 1; n.depth = 1; n.kind = NodeKind::Regular;
            for (n.id = 2; n.id <= 580; ++n.id) {
                n.name = n.id <= 270 ? "x" : std::string(255, '\xff');
                n.symlink_target = n.id == 550 ? std::string(4096, 'y') : "";
                n.kind = n.id == 550 ? NodeKind::Symlink : NodeKind::Regular;
                writer.node(n);
            }
            writer.issue({580, ScanErrorCode::Io, "fixture", EIO});
            n.id = 1; n.directory_enumerated = true; writer.directory(n);
            StreamResult result; result.node_count = 580;
            result.scan.state = SessionState::Partial; result.scan.summary.issue_count = 1;
            writer.finish(result);
        }
        const auto size = lseek(fd, 0, SEEK_CUR);
        require(size > 0 && lseek(fd, 0, SEEK_SET) == 0, "rewind wire fixture");
        Bytes value(size);
        std::size_t read_bytes = 0;
        while (read_bytes < value.size()) {
            const auto got = read(fd, value.data() + read_bytes, value.size() - read_bytes);
            require(got > 0, "read wire fixture"); read_bytes += got;
        }
        // Last two frames: header + 40 byte timing body, header + empty end.
        const auto timing = value.size() - 36 - 40;
        std::fill_n(value.begin() + timing, 24, 0);
        return value;
    };
    const auto baseline = encode(false);
    const auto optimized = encode(true);
    close(fd);
    require(baseline == optimized, "v4 buffer reuse altered frame bytes");
}

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
        const int sparse = open((std::string(root) + "/sparse").c_str(), O_CREAT | O_WRONLY, 0600);
        require(sparse >= 0 && ftruncate(sparse, 1LL << 32) == 0, "sparse fixture"); close(sparse);
        require(mkfifo((std::string(root) + "/fifo").c_str(), 0600) == 0, "fifo fixture");
        ScanConfiguration c; c.root_path = root;
        std::uint64_t count = 0, closures = 0; bool raw_seen = false;
        std::vector<ScanNode> expected;
        StreamCallbacks cb;
        cb.on_nodes = [&](std::span<const ScanNode> nodes) {
            require(nodes.size() == 1, "unbounded discovery");
            for (const auto& n : nodes) {
                require(n.id == ++count && n.parent_id < n.id, "discovery order");
                expected.push_back(n);
                if (n.name == std::string("raw\xff\n")) raw_seen = n.raw_size == 3 && n.metadata_valid;
                if (n.name == "sparse") require(n.raw_size == 1ULL << 32 && n.raw_allocated_bytes < n.raw_size,
                    "sparse allocation");
                if (n.name == "fifo") require(n.kind == NodeKind::Special && n.metadata_valid, "fifo metadata");
            }
        };
        cb.on_directory = [&](const ScanNode& n) { require(n.complete && n.directory_enumerated, "directory closure"); ++closures; };
        CancellationToken token;
        const auto result = stream_directory(c, token, cb);
        require(result.scan.state == SessionState::Completed && count == 20007 && closures == 2 && raw_seen, "stream result");
        require(result.scan.nodes.empty() && result.scan.issues.empty(), "retained full tree");
        require(result.volume.valid && result.volume.total > 0 && result.volume.free <= result.volume.total &&
            result.volume.available <= result.volume.free && result.volume.captured_millis > 0, "volume sample");
        require(result.scan.metrics.peak_open_fds <= 6 && result.scan.metrics.open_fds_at_finish == 0, "fd bounds");
        for (const auto strategy : {StreamMetadata::Separate, StreamMetadata::FdInfo}) {
            std::size_t seen = 0;
            StreamCallbacks comparison;
            comparison.on_nodes = [&](std::span<const ScanNode> nodes) {
                require(seen < expected.size() && metadata_fields(nodes.front()) == metadata_fields(expected[seen]),
                    "fallback changed metadata");
                ++seen;
            };
            CancellationToken compatible;
            EntryMeasurements stats;
            const auto fallback = stream_directory(c, compatible, comparison, strategy, &stats);
            require(fallback.scan.state == SessionState::Completed && seen == expected.size() &&
                fallback.scan.root_mount_id == result.scan.root_mount_id &&
                fallback.scan.metrics.open_fds_at_finish == 0, "fallback result");
            require(stats.stat_calls == seen - 1, "fallback stat count");
            if (strategy == StreamMetadata::FdInfo)
                require(stats.statx_calls == 0 && stats.fd_fallbacks == seen - 1, "forced fdinfo count");
        }
        check_v4_buffers(root);
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
            for (const auto strategy : {StreamMetadata::Combined, StreamMetadata::FdInfo}) {
                CancellationToken mounted; boundary = false;
                const auto scanned = stream_directory(c, mounted, cb, strategy);
                if (!boundary || scanned.scan.state != SessionState::Partial) _exit(1);
            }
            _exit(0);
        }
        int status = 0; require(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0, "mount boundary");
        fs::remove_all(root);
        std::cout << "{\"suite\":\"stream\",\"tests\":10,\"failed\":0}\n";
        return 0;
    } catch (const std::exception& e) { fs::remove_all(root); std::cerr << e.what() << '\n'; return 1; }
}
