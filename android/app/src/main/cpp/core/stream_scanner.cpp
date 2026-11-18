#include "core/stream_scanner.h"
#include "core/filesystem.h"
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <string_view>
#include <sys/vfs.h>
#include <unistd.h>

namespace disksize {
namespace {
using Clock = std::chrono::steady_clock;
ScanErrorCode classify(int e) {
    switch (e) {
    case EACCES: case EPERM: return ScanErrorCode::PermissionDenied;
    case ENOENT: return ScanErrorCode::NotFound;
    case ELOOP: case ESTALE: case ENOTDIR: return ScanErrorCode::ChangedDuringScan;
    case EMFILE: case ENFILE: case ENOMEM: return ScanErrorCode::ResourceLimit;
    default: return ScanErrorCode::Io;
    }
}
bool metadata(ScanNode& n, const struct stat& s) {
    n.kind = S_ISDIR(s.st_mode) ? NodeKind::Directory : S_ISREG(s.st_mode) ? NodeKind::Regular :
        S_ISLNK(s.st_mode) ? NodeKind::Symlink : NodeKind::Special;
    n.mode = s.st_mode; n.uid = s.st_uid; n.gid = s.st_gid; n.device = s.st_dev;
    n.inode = s.st_ino; n.link_count = s.st_nlink;
    n.mtime_seconds = s.st_mtim.tv_sec; n.mtime_nanoseconds = s.st_mtim.tv_nsec;
    if (s.st_size < 0 || s.st_blocks < 0 ||
        static_cast<std::uint64_t>(s.st_blocks) > static_cast<std::uint64_t>(INT64_MAX) / 512) return false;
    n.raw_size = s.st_size; n.raw_allocated_bytes = static_cast<std::uint64_t>(s.st_blocks) * 512;
    n.metadata_valid = true; return true;
}
struct Frame {
    ScanNode node;
    struct stat original{};
    std::unique_ptr<internal::DirStream> directory;
};
class Stream {
public:
    Stream(const ScanConfiguration& c, CancellationToken& token, const StreamCallbacks& cb,
           StreamMetadata strategy, EntryMeasurements* measurements)
        : c_(c), token_(token), cb_(cb), entries_(strategy, measurements) {}
    StreamResult run() {
        try { walk(); }
        catch (...) { consumer_failed_ = true; }
        // On cancellation, ancestors already discovered remain explicitly incomplete.
        while (!stack_.empty()) {
            auto& frame = stack_.back();
            frame.node.complete = false;
            if (token_.requested()) frame.node.skipped = SkipReason::Cancelled;
            if (!consumer_failed_ && cb_.on_directory) {
                try { cb_.on_directory(frame.node); } catch (...) { consumer_failed_ = true; }
            }
            stack_.pop_back();
        }
        auto& r = result_.scan;
        r.state = consumer_failed_ || failed_ ? SessionState::Failed : token_.requested() ? SessionState::Cancelled :
            partial_ ? SessionState::Partial : SessionState::Completed;
        r.metrics.elapsed_microseconds = elapsed();
        r.metrics.peak_open_fds = fds_.peak.load(); r.metrics.open_fds_at_finish = fds_.current.load();
        r.metrics.peak_active_workers = 1;
        return std::move(result_);
    }
private:
    void issue(ScanNode* node, ScanErrorCode code, const char* op, int error) {
        ++result_.scan.summary.issue_count; partial_ = true;
        if (node) { ++node->issue_count; node->complete = false; }
        if (cb_.on_issue) cb_.on_issue({node ? node->id : 0, code, op, error});
    }
    void deliver(ScanNode& node) {
        if (cb_.on_nodes) cb_.on_nodes(std::span<const ScanNode>(&node, 1));
    }
    std::uint64_t elapsed() const {
        return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start_).count();
    }
    void progress() {
        const auto now = Clock::now();
        if (now - last_ < std::chrono::milliseconds(c_.progress_interval_ms)) return;
        last_ = now;
        if (cb_.on_progress) cb_.on_progress({result_.node_count, observed_, result_.scan.summary.issue_count, elapsed()});
    }
    bool push(ScanNode node, internal::Fd fd, const struct stat& original) {
        auto dir = std::make_unique<internal::DirStream>(std::move(fd), fds_);
        if (!dir->get()) {
            issue(&node, classify(errno), "fdopendir", errno);
            if (cb_.on_directory) cb_.on_directory(node);
            return false;
        }
        stack_.push_back({std::move(node), original, std::move(dir)}); return true;
    }
    void walk() {
        if (c_.root_path.empty() || c_.root_path[0] != '/' || c_.root_path.find('\0') != std::string::npos ||
            c_.max_depth > 512 || c_.max_nodes == 0 || c_.follow_symbolic_links || c_.cross_mount_points) {
            failed_ = true; issue(nullptr, ScanErrorCode::InvalidConfiguration, "validate-stream", EINVAL); return;
        }
        if (token_.requested()) return;
        std::unique_ptr<char, decltype(&std::free)> path(realpath(c_.root_path.c_str(), nullptr), &std::free);
        if (!path) { failed_ = true; issue(nullptr, classify(errno), "resolve-root", errno); return; }
        auto& r = result_.scan;
        r.canonical_root = path.get();
        auto root = internal::open_canonical_directory(r.canonical_root, fds_);
        struct stat s{}; struct statfs fs{};
        if (!root || fstat(root.get(), &s) || fstatfs(root.get(), &fs)) {
            failed_ = true; issue(nullptr, classify(errno), "open-stat-root", errno); return;
        }
        r.root_mount_id = internal::mount_id_for_fd(root.get(), fds_);
        r.root_filesystem_type = static_cast<std::uint64_t>(fs.f_type);
        if (fs.f_bsize > 0 && fs.f_blocks <= static_cast<std::uint64_t>(INT64_MAX) / fs.f_bsize &&
            fs.f_bfree <= fs.f_blocks && fs.f_bavail <= fs.f_bfree) {
            result_.volume = {true, fs.f_blocks * fs.f_bsize, fs.f_bfree * fs.f_bsize, fs.f_bavail * fs.f_bsize,
                static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count())};
        }
        if (!r.root_mount_id) { failed_ = true; issue(nullptr, ScanErrorCode::Unsupported, "root-mount", 0); return; }
        ScanNode n; n.id = 1; n.mount_id = r.root_mount_id;
        if (!metadata(n, s)) { failed_ = true; issue(nullptr, ScanErrorCode::Io, "root-metadata", EOVERFLOW); return; }
        result_.node_count = 1;
        if (internal::excluded_system_root(r.canonical_root) || internal::pseudo_filesystem(r.root_filesystem_type)) {
            n.skipped = SkipReason::PseudoFilesystem; partial_ = true; deliver(n); return;
        }
        deliver(n); push(n, std::move(root), s);
        while (!stack_.empty() && !token_.requested()) {
            auto& frame = stack_.back();
            const auto parent = frame.node.id;
            const int fd = dirfd(frame.directory->get());
            errno = 0;
            const auto entry = readdir(frame.directory->get());
            if (!entry) {
                const int error = errno;
                frame.node.complete = error == 0 && frame.node.issue_count == 0;
                frame.node.directory_enumerated = error == 0;
                if (error) issue(&frame.node, classify(error), "readdir", error);
                struct stat after{};
                if (fstat(fd, &after) || !internal::same_identity(frame.original, after) ||
                    frame.original.st_mtim.tv_sec != after.st_mtim.tv_sec || frame.original.st_mtim.tv_nsec != after.st_mtim.tv_nsec ||
                    frame.original.st_ctim.tv_sec != after.st_ctim.tv_sec || frame.original.st_ctim.tv_nsec != after.st_ctim.tv_nsec) {
                    issue(&frame.node, ScanErrorCode::ChangedDuringScan, "directory-changed", 0);
                }
                if (cb_.on_directory) cb_.on_directory(frame.node);
                stack_.pop_back(); continue;
            }
            const std::string_view name(entry->d_name);
            if (name == "." || name == "..") continue;
            if (result_.node_count >= c_.max_nodes) {
                frame.node.skipped = SkipReason::ResourceLimit;
                issue(&frame.node, ScanErrorCode::ResourceLimit, "max-nodes", 0); break;
            }
            ScanNode child; child.id = ++result_.node_count; child.parent_id = parent;
            child.name = name; child.depth = frame.node.depth + 1;
            struct stat st{};
            const bool found = entries_.read(fd, child.name.c_str(), st, child.mount_id, fds_);
            if (!found || !metadata(child, st)) {
                const int e = found ? EOVERFLOW : errno;
                child.mount_id = 0;
                deliver(child); issue(&child, classify(e), "fstatat", e);
                continue;
            }
            if (!child.mount_id) child.skipped = SkipReason::UnknownMount;
            else if (child.mount_id != r.root_mount_id || child.device != n.device) child.skipped = SkipReason::MountBoundary;
            if (child.kind == NodeKind::Directory && child.skipped == SkipReason::None) {
                for (const auto& ancestor : stack_) {
                    if (internal::same_identity(ancestor.original, st)) { child.skipped = SkipReason::DirectoryAlias; break; }
                }
            }
            child.complete = child.skipped == SkipReason::None && child.kind != NodeKind::Directory;
            if (child.kind == NodeKind::Regular) {
                if (observed_ > static_cast<std::uint64_t>(INT64_MAX) - child.raw_size) throw std::overflow_error("counter overflow");
                observed_ += child.raw_size;
            }
            if (child.kind == NodeKind::Symlink && child.skipped == SkipReason::None) {
                char target[4097]; const auto size = readlinkat(fd, child.name.c_str(), target, sizeof(target));
                if (size >= 0 && size <= 4096) child.symlink_target.assign(target, size);
                else { deliver(child); issue(&child, ScanErrorCode::Io, "readlinkat", size < 0 ? errno : EOVERFLOW); continue; }
            }
            if (child.kind == NodeKind::Directory && child.depth >= c_.max_depth) child.skipped = SkipReason::ResourceLimit;
            if (child.skipped != SkipReason::None) { partial_ = true; child.complete = false; }
            deliver(child); progress();
            if (child.kind != NodeKind::Directory || child.skipped != SkipReason::None) continue;
            // openat + identity + mount verification closes replacement/symlink races.
            internal::Fd next(openat(fd, child.name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), fds_);
            struct stat opened{};
            if (!next || fstat(next.get(), &opened)) {
                issue(&child, classify(errno), "open-directory", errno);
                if (cb_.on_directory) cb_.on_directory(child); continue;
            }
            if (!internal::same_identity(st, opened) || internal::mount_id_for_fd(next.get(), fds_) != r.root_mount_id) {
                child.skipped = SkipReason::ChangedDuringScan;
                issue(&child, ScanErrorCode::ChangedDuringScan, "directory-replaced", 0);
                if (cb_.on_directory) cb_.on_directory(child); continue;
            }
            push(child, std::move(next), opened);
        }
    }
    const ScanConfiguration& c_; CancellationToken& token_; const StreamCallbacks& cb_;
    internal::EntryReader entries_;
    StreamResult result_; internal::FdMetrics fds_; std::vector<Frame> stack_;
    Clock::time_point start_ = Clock::now(), last_ = start_;
    std::uint64_t observed_ = 0;
    bool partial_ = false, failed_ = false, consumer_failed_ = false;
};
}
StreamResult stream_directory(const ScanConfiguration& c, CancellationToken& token, const StreamCallbacks& cb,
                              StreamMetadata strategy, EntryMeasurements* measurements) {
    return Stream(c, token, cb, strategy, measurements).run();
}
}
