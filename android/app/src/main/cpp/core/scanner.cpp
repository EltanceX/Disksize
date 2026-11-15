#include "core/scanner.h"
#include "core/filesystem.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <sys/vfs.h>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <unistd.h>

namespace disksize {
namespace {

using Clock = std::chrono::steady_clock;
using internal::Fd;

struct Identity {
    std::uint64_t device;
    std::uint64_t inode;
    bool operator==(const Identity&) const = default;
};
struct IdentityHash {
    std::size_t operator()(const Identity& identity) const noexcept {
        return std::hash<std::uint64_t>{}(identity.device) ^
               (std::hash<std::uint64_t>{}(identity.inode) << 1);
    }
};
enum class Traversal { None, Delivering, Pending, Queued, Active, Finished };
struct Draft {
    ScanNode node;
    std::optional<ScanIssue> issue;
};
struct PathPart {
    std::string name;
    Identity identity;
};

ScanErrorCode classify(int error) {
    switch (error) {
        case EACCES: case EPERM: return ScanErrorCode::PermissionDenied;
        case ENOENT: return ScanErrorCode::NotFound;
        case ESTALE: case ELOOP: case ENOTDIR: return ScanErrorCode::ChangedDuringScan;
        case EMFILE: case ENFILE: case ENOMEM: return ScanErrorCode::ResourceLimit;
        default: return ScanErrorCode::Io;
    }
}

NodeKind kind_for(mode_t mode) {
    if (S_ISDIR(mode)) return NodeKind::Directory;
    if (S_ISREG(mode)) return NodeKind::Regular;
    if (S_ISLNK(mode)) return NodeKind::Symlink;
    return NodeKind::Special;
}

bool fill_metadata(ScanNode& node, const struct stat& metadata) {
    node.kind = kind_for(metadata.st_mode);
    node.mode = metadata.st_mode;
    node.uid = metadata.st_uid;
    node.gid = metadata.st_gid;
    node.device = metadata.st_dev;
    node.inode = metadata.st_ino;
    node.link_count = metadata.st_nlink;
    node.mtime_seconds = metadata.st_mtim.tv_sec;
    node.mtime_nanoseconds = metadata.st_mtim.tv_nsec;
    if (metadata.st_size < 0 || metadata.st_blocks < 0 ||
        static_cast<std::uint64_t>(metadata.st_blocks) >
            std::numeric_limits<std::uint64_t>::max() / 512) return false;
    node.raw_size = static_cast<std::uint64_t>(metadata.st_size);
    node.raw_allocated_bytes = static_cast<std::uint64_t>(metadata.st_blocks) * 512;
    node.metadata_valid = true;
    return true;
}

std::uint64_t checked_add(std::uint64_t left, std::uint64_t right) {
    if (right > std::numeric_limits<std::uint64_t>::max() - left) {
        throw std::overflow_error("scan counter overflow");
    }
    return left + right;
}

bool byte_less(const std::string& left, const std::string& right) {
    return std::lexicographical_compare(left.begin(), left.end(), right.begin(), right.end(),
        [](unsigned char a, unsigned char b) { return a < b; });
}

class Engine {
public:
    Engine(const ScanConfiguration& configuration, CancellationToken& cancellation,
           const ScanCallbacks& callbacks)
        : configuration_(configuration), cancellation_(cancellation), callbacks_(callbacks) {}

    ScanResult run() {
        if (!validate()) return finish();
        if (cancellation_.requested()) return finish();
        std::unique_ptr<char, decltype(&std::free)> canonical(
            ::realpath(configuration_.root_path.c_str(), nullptr), &std::free);
        if (!canonical) {
            issue({0, classify(errno), "resolve-root", errno});
            root_failed_ = true;
            return finish();
        }
        result_.canonical_root = canonical.get();
        root_ = internal::open_canonical_directory(result_.canonical_root, fd_metrics_);
        if (!root_) {
            issue({0, classify(errno), "open-root", errno});
            root_failed_ = true;
            return finish();
        }
        struct stat metadata {};
        struct statfs filesystem {};
        if (::fstat(root_.get(), &metadata) != 0 || ::fstatfs(root_.get(), &filesystem) != 0) {
            issue({0, classify(errno), "stat-root", errno});
            root_failed_ = true;
            return finish();
        }
        result_.root_mount_id = internal::mount_id_for_fd(root_.get(), fd_metrics_);
        result_.root_filesystem_type = static_cast<std::uint64_t>(filesystem.f_type);
        if (result_.root_mount_id == 0) {
            issue({0, ScanErrorCode::Unsupported, "identify-root-mount", errno});
            root_failed_ = true;
            return finish();
        }
        ScanNode root_node;
        root_node.id = 1;
        root_node.mount_id = result_.root_mount_id;
        if (!fill_metadata(root_node, metadata)) {
            issue({0, ScanErrorCode::Io, "invalid-root-metadata", EOVERFLOW});
            root_failed_ = true;
            return finish();
        }
        const bool excluded = internal::excluded_system_root(result_.canonical_root) ||
                              internal::pseudo_filesystem(result_.root_filesystem_type);
        if (excluded) root_node.skipped = SkipReason::PseudoFilesystem;
        result_.nodes.push_back(root_node);
        traversals_.push_back(excluded ? Traversal::Finished : Traversal::Pending);
        deliver_nodes(std::span<const ScanNode>(&root_node, 1));
        progress(true);
        if (excluded || stop()) return finish();

        std::vector<std::thread> workers;
        try {
            for (std::uint32_t index = 0; index < configuration_.worker_count; ++index) {
                workers.emplace_back([this] { worker(); });
            }
        } catch (...) {
            worker_failed_.store(true);
        }
        schedule();
        for (auto& thread : workers) thread.join();
        return finish();
    }

private:
    bool validate() {
        const auto& c = configuration_;
        if (c.root_path.empty() || c.root_path.front() != '/' ||
            c.root_path.find('\0') != std::string::npos || c.worker_count == 0 ||
            c.worker_count > 8 || c.queue_capacity == 0 || c.queue_capacity > 4096 ||
            c.batch_size == 0 || c.batch_size > 4096 || c.max_nodes == 0 ||
            c.max_depth > 4096 || c.progress_interval_ms == 0) {
            issue({0, ScanErrorCode::InvalidConfiguration, "validate", EINVAL});
            root_failed_ = true;
            return false;
        }
        // This prototype has one selected mount and never follows directory entries.
        if (c.follow_symbolic_links || c.cross_mount_points) {
            issue({0, ScanErrorCode::Unsupported, "validate-traversal-policy", ENOTSUP});
            root_failed_ = true;
            return false;
        }
        return true;
    }

    bool stop() const noexcept {
        return cancellation_.requested() || limit_reached_.load() ||
               consumer_failed_.load() || worker_failed_.load();
    }

    void issue(ScanIssue value) {
        {
            std::lock_guard lock(mutex_);
            ++result_.summary.issue_count;
            if (value.node_id > 0 && value.node_id <= result_.nodes.size()) {
                ++result_.nodes[value.node_id - 1].issue_count;
            }
            if (result_.issues.size() < configuration_.max_issues) result_.issues.push_back(value);
            else ++result_.summary.dropped_issues;
        }
        if (callbacks_.on_issue && !consumer_failed_.load()) {
            std::lock_guard callback_lock(callback_mutex_);
            if (consumer_failed_.load()) return;
            try { callbacks_.on_issue(value); }
            catch (...) { consumer_failed_.store(true); changed_.notify_all(); }
        }
    }

    void deliver_nodes(std::span<const ScanNode> nodes) {
        if (!callbacks_.on_nodes || nodes.empty() || consumer_failed_.load()) return;
        std::lock_guard callback_lock(callback_mutex_);
        if (consumer_failed_.load()) return;
        try { callbacks_.on_nodes(nodes); }
        catch (...) { consumer_failed_.store(true); changed_.notify_all(); }
    }

    void progress(bool force = false) {
        if (!callbacks_.on_progress || consumer_failed_.load()) return;
        std::lock_guard callback_lock(callback_mutex_);
        if (consumer_failed_.load()) return;
        const auto now = Clock::now();
        if (!force && now - last_progress_ <
            std::chrono::milliseconds(configuration_.progress_interval_ms)) return;
        last_progress_ = now;
        ScanProgress value;
        {
            std::lock_guard lock(mutex_);
            value.discovered_nodes = result_.nodes.size();
            value.observed_logical_bytes = observed_logical_;
            value.issue_count = result_.summary.issue_count;
        }
        value.elapsed_microseconds = elapsed();
        try { callbacks_.on_progress(value); }
        catch (...) { consumer_failed_.store(true); changed_.notify_all(); }
    }

    void skip(NodeId id, SkipReason reason) {
        std::lock_guard lock(mutex_);
        result_.nodes[id - 1].skipped = reason;
    }

    void flush(std::vector<Draft>& drafts, NodeId parent) {
        if (drafts.empty()) return;
        std::vector<ScanNode> delivered;
        std::vector<ScanIssue> issues;
        bool new_limit = false;
        {
            std::lock_guard lock(mutex_);
            for (auto& draft : drafts) {
                if (result_.nodes.size() >= configuration_.max_nodes) {
                    new_limit = !limit_reached_.exchange(true);
                    break;
                }
                auto& node = draft.node;
                node.id = result_.nodes.size() + 1;
                if (node.metadata_valid && node.kind == NodeKind::Regular) {
                    observed_logical_ = checked_add(observed_logical_, node.raw_size);
                }
                if (draft.issue) {
                    draft.issue->node_id = node.id;
                    issues.push_back(*draft.issue);
                }
                traversals_.push_back(node.kind == NodeKind::Directory &&
                    node.metadata_valid && node.skipped == SkipReason::None
                    ? Traversal::Delivering : Traversal::None);
                result_.nodes.push_back(std::move(node));
                delivered.push_back(result_.nodes.back());
            }
        }
        for (auto& value : issues) issue(std::move(value));
        deliver_nodes(delivered);
        {
            std::lock_guard lock(mutex_);
            for (const auto& node : delivered) {
                if (traversals_[node.id - 1] == Traversal::Delivering) {
                    traversals_[node.id - 1] = Traversal::Pending;
                }
            }
        }
        if (new_limit) issue({parent, ScanErrorCode::ResourceLimit, "node-limit", ENOSPC});
        drafts.clear();
        changed_.notify_all();
        progress();
    }

    Fd open_directory(NodeId id) {
        std::vector<PathPart> parts;
        {
            std::lock_guard lock(mutex_);
            for (auto cursor = id; cursor > 1; cursor = result_.nodes[cursor - 1].parent_id) {
                const auto& node = result_.nodes[cursor - 1];
                parts.push_back({node.name, {node.device, node.inode}});
            }
        }
        Fd current(::openat(root_.get(), ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC), fd_metrics_);
        if (!current) return {};
        for (auto iterator = parts.rbegin(); iterator != parts.rend(); ++iterator) {
            if (stop()) { errno = ECANCELED; return {}; }
            Fd next(::openat(current.get(), iterator->name.c_str(),
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), fd_metrics_);
            if (!next) return {};
            struct stat actual {};
            if (::fstat(next.get(), &actual) != 0) return {};
            if (actual.st_dev != iterator->identity.device || actual.st_ino != iterator->identity.inode) {
                errno = ESTALE;
                return {};
            }
            const auto mount_id = internal::mount_id_for_fd(next.get(), fd_metrics_);
            if (mount_id == 0) return {};
            if (mount_id != result_.root_mount_id) { errno = ESTALE; return {}; }
            current = std::move(next);
        }
        return current;
    }

    Draft inspect(int parent_fd, const char* name, NodeId parent, std::uint32_t depth) {
        Draft draft;
        auto& node = draft.node;
        node.parent_id = parent;
        node.depth = depth;
        node.name = name;
        struct stat metadata {};
        if (::fstatat(parent_fd, name, &metadata, AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT) != 0) {
            draft.issue = ScanIssue{0, classify(errno), "stat-entry", errno};
            return draft;
        }
        if (!fill_metadata(node, metadata)) {
            draft.issue = ScanIssue{0, ScanErrorCode::Io, "invalid-entry-metadata", EOVERFLOW};
            return draft;
        }
        node.mount_id = internal::mount_id_at(parent_fd, name, metadata, fd_metrics_);
        if (node.mount_id == 0) {
            const int error = errno;
            node.skipped = error == ESTALE ? SkipReason::ChangedDuringScan : SkipReason::UnknownMount;
            draft.issue = ScanIssue{0, classify(error), "identify-entry-mount", error};
            return draft;
        }
        if (node.mount_id != result_.root_mount_id) {
            node.skipped = SkipReason::MountBoundary;
            return draft;
        }
        if (result_.canonical_root == "/" && parent == 1 &&
            (node.name == "proc" || node.name == "sys" || node.name == "dev")) {
            node.skipped = SkipReason::PseudoFilesystem;
            return draft;
        }
        if (node.kind == NodeKind::Directory && depth > configuration_.max_depth) {
            node.skipped = SkipReason::ResourceLimit;
            draft.issue = ScanIssue{0, ScanErrorCode::ResourceLimit, "directory-depth-limit", EOVERFLOW};
        } else if (node.kind == NodeKind::Symlink) {
            std::vector<char> target(256);
            while (true) {
                const auto length = ::readlinkat(parent_fd, name, target.data(), target.size());
                if (length < 0) {
                    draft.issue = ScanIssue{0, classify(errno), "read-link", errno};
                    break;
                }
                if (static_cast<std::size_t>(length) < target.size()) {
                    node.symlink_target.assign(target.data(), static_cast<std::size_t>(length));
                    break;
                }
                if (target.size() == 65536) {
                    draft.issue = ScanIssue{0, ScanErrorCode::ResourceLimit, "link-target-limit", ENAMETOOLONG};
                    break;
                }
                target.resize(target.size() * 2);
            }
        }
        return draft;
    }

    void process_directory(NodeId id) {
        Fd directory = open_directory(id);
        if (!directory) {
            const int error = errno;
            if (stop() && error == ECANCELED) return;
            if (classify(error) == ScanErrorCode::ChangedDuringScan || error == ENOENT) {
                skip(id, SkipReason::ChangedDuringScan);
            }
            issue({id, classify(error), "open-directory", error});
            return;
        }
        struct stat metadata {};
        struct statfs filesystem {};
        if (::fstat(directory.get(), &metadata) != 0 || ::fstatfs(directory.get(), &filesystem) != 0) {
            issue({id, classify(errno), "stat-directory", errno});
            return;
        }
        if (internal::pseudo_filesystem(static_cast<std::uint64_t>(filesystem.f_type))) {
            skip(id, SkipReason::PseudoFilesystem);
            return;
        }
        std::uint32_t depth;
        {
            std::lock_guard lock(mutex_);
            if (!visited_.insert({static_cast<std::uint64_t>(metadata.st_dev), metadata.st_ino}).second) {
                result_.nodes[id - 1].skipped = SkipReason::DirectoryAlias;
                return;
            }
            depth = result_.nodes[id - 1].depth;
        }
        const int directory_fd = directory.get();
        internal::DirStream stream(std::move(directory), fd_metrics_);
        if (stream.get() == nullptr) {
            issue({id, classify(errno), "open-directory-stream", errno});
            return;
        }
        std::vector<Draft> drafts;
        drafts.reserve(configuration_.batch_size);
        bool enumerated = false;
        while (!stop()) {
            errno = 0;
            const auto* entry = ::readdir(stream.get());
            if (entry == nullptr) {
                if (errno != 0) issue({id, classify(errno), "read-directory", errno});
                else enumerated = true;
                break;
            }
            if (entry->d_name[0] == '.' && (entry->d_name[1] == '\0' ||
                (entry->d_name[1] == '.' && entry->d_name[2] == '\0'))) continue;
            drafts.push_back(inspect(directory_fd, entry->d_name, id, depth + 1));
            if (drafts.size() >= configuration_.batch_size) flush(drafts, id);
        }
        flush(drafts, id);
        {
            std::lock_guard lock(mutex_);
            result_.nodes[id - 1].directory_enumerated = enumerated;
        }
    }

    void worker() noexcept {
        while (true) {
            NodeId id;
            {
                std::unique_lock lock(mutex_);
                changed_.wait(lock, [this] { return shutdown_ || stop() || !queue_.empty(); });
                if (shutdown_ || stop()) return;
                id = queue_.front();
                queue_.pop_front();
                traversals_[id - 1] = Traversal::Active;
                ++active_;
                result_.metrics.peak_active_workers = std::max(result_.metrics.peak_active_workers, active_);
            }
            changed_.notify_all();
            try { process_directory(id); }
            catch (...) { worker_failed_.store(true); }
            {
                std::lock_guard lock(mutex_);
                traversals_[id - 1] = Traversal::Finished;
                --active_;
            }
            changed_.notify_all();
        }
    }

    void schedule() {
        std::unique_lock lock(mutex_);
        std::size_t cursor = 0;
        while (!stop()) {
            while (cursor < traversals_.size() && queue_.size() < configuration_.queue_capacity) {
                if (traversals_[cursor] == Traversal::Delivering) break;
                if (traversals_[cursor] == Traversal::Pending) {
                    queue_.push_back(cursor + 1);
                    traversals_[cursor] = Traversal::Queued;
                    result_.metrics.peak_queued_directories =
                        std::max<std::uint64_t>(result_.metrics.peak_queued_directories, queue_.size());
                }
                ++cursor;
            }
            changed_.notify_all();
            if (cursor == traversals_.size() && queue_.empty() && active_ == 0) break;
            // CancellationToken can be requested by a JNI/Binder thread that
            // does not own this condition variable. Recheck it even when all
            // workers observe cancellation before dequeuing and exit silently.
            changed_.wait_for(lock, std::chrono::milliseconds(25), [this, &cursor] {
                return stop() ||
                    (cursor < traversals_.size() && traversals_[cursor] != Traversal::Delivering &&
                     queue_.size() < configuration_.queue_capacity) ||
                    (cursor == traversals_.size() && queue_.empty() && active_ == 0);
            });
        }
        shutdown_ = true;
        queue_.clear();
        lock.unlock();
        changed_.notify_all();
    }

    void aggregate() {
        std::unordered_map<Identity, NodeId, IdentityHash> owners;
        for (auto& node : result_.nodes) {
            if (node.kind == NodeKind::Directory && !node.directory_enumerated &&
                node.skipped == SkipReason::None && node.issue_count == 0) {
                if (cancellation_.requested()) node.skipped = SkipReason::Cancelled;
                else if (limit_reached_.load()) node.skipped = SkipReason::ResourceLimit;
            }
            node.complete = node.metadata_valid && node.issue_count == 0 &&
                node.skipped == SkipReason::None &&
                (node.kind != NodeKind::Directory || node.directory_enumerated);
            switch (node.kind) {
                case NodeKind::Regular: ++result_.summary.regular_entries; break;
                case NodeKind::Directory: ++result_.summary.directories; break;
                case NodeKind::Symlink: ++result_.summary.symlinks; break;
                case NodeKind::Special: ++result_.summary.special_entries; break;
                case NodeKind::Unknown: ++result_.summary.unknown_entries; break;
            }
            if (node.skipped != SkipReason::None) ++result_.summary.skipped_entries;
            if (!node.metadata_valid || node.skipped != SkipReason::None) continue;
            node.counted_allocated_bytes = node.raw_allocated_bytes;
            if (node.kind == NodeKind::Directory) continue;
            if (node.kind == NodeKind::Regular) node.counted_logical_bytes = node.raw_size;
            const Identity identity{node.device, node.inode};
            const auto [position, inserted] = owners.emplace(identity, node.id);
            if (inserted && node.kind == NodeKind::Regular) ++result_.summary.unique_regular_files;
            if (!inserted) {
                auto& previous = result_.nodes[position->second - 1];
                if (node.raw_size != previous.raw_size || node.raw_allocated_bytes != previous.raw_allocated_bytes ||
                    node.mtime_seconds != previous.mtime_seconds || node.mtime_nanoseconds != previous.mtime_nanoseconds) {
                    issue({node.id, ScanErrorCode::ChangedDuringScan, "hardlink-metadata-changed", ESTALE});
                    node.complete = false;
                    previous.complete = false;
                }
                if (byte_less(relative_path(result_, node.id), relative_path(result_, previous.id))) {
                    position->second = node.id;
                }
            }
        }
        for (auto& node : result_.nodes) {
            if (node.kind != NodeKind::Directory && node.metadata_valid && node.skipped == SkipReason::None) {
                node.hardlink_owner_id = owners.at({node.device, node.inode});
                if (node.hardlink_owner_id != node.id) {
                    node.counted_logical_bytes = 0;
                    node.counted_allocated_bytes = 0;
                    ++result_.summary.hardlink_aliases;
                }
            }
            node.subtree_logical_bytes = node.counted_logical_bytes;
            node.subtree_allocated_bytes = node.counted_allocated_bytes;
        }
        for (auto iterator = result_.nodes.rbegin(); iterator != result_.nodes.rend(); ++iterator) {
            if (iterator->parent_id == kNoNode) continue;
            auto& parent = result_.nodes[iterator->parent_id - 1];
            parent.subtree_logical_bytes = checked_add(parent.subtree_logical_bytes, iterator->subtree_logical_bytes);
            parent.subtree_allocated_bytes = checked_add(parent.subtree_allocated_bytes, iterator->subtree_allocated_bytes);
            parent.complete = parent.complete && iterator->complete;
        }
        if (!result_.nodes.empty()) {
            result_.summary.logical_bytes = result_.nodes.front().subtree_logical_bytes;
            result_.summary.allocated_bytes = result_.nodes.front().subtree_allocated_bytes;
        }
    }

    std::uint64_t elapsed() const {
        return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - started_).count();
    }

    ScanResult finish() {
        root_.reset();
        progress(true);
        if (consumer_failed_.load()) issue({0, ScanErrorCode::ConsumerFailure, "consumer-callback", 0});
        if (worker_failed_.load()) issue({0, ScanErrorCode::Io, "worker-exception", EIO});
        try { aggregate(); }
        catch (const std::overflow_error&) {
            issue({0, ScanErrorCode::ResourceLimit, "aggregate-counter-overflow", EOVERFLOW});
            root_failed_ = true;
            if (!result_.nodes.empty()) result_.nodes.front().complete = false;
        }
        if (root_failed_ || worker_failed_.load() || consumer_failed_.load()) result_.state = SessionState::Failed;
        else if (cancellation_.requested()) result_.state = SessionState::Cancelled;
        else if (limit_reached_.load() || result_.summary.issue_count != 0 || result_.nodes.empty() ||
                 !result_.nodes.front().complete) result_.state = SessionState::Partial;
        else result_.state = SessionState::Completed;
        if (result_.state != SessionState::Completed && !result_.nodes.empty()) {
            result_.nodes.front().complete = false;
        }
        result_.metrics.elapsed_microseconds = elapsed();
        result_.metrics.peak_open_fds = fd_metrics_.peak.load();
        result_.metrics.open_fds_at_finish = fd_metrics_.current.load();
        return std::move(result_);
    }

    const ScanConfiguration& configuration_;
    CancellationToken& cancellation_;
    const ScanCallbacks& callbacks_;
    Clock::time_point started_ = Clock::now();
    Clock::time_point last_progress_{};
    internal::FdMetrics fd_metrics_;
    Fd root_;
    ScanResult result_;
    std::vector<Traversal> traversals_;
    std::unordered_set<Identity, IdentityHash> visited_;
    std::deque<NodeId> queue_;
    std::mutex mutex_;
    std::mutex callback_mutex_;
    std::condition_variable changed_;
    std::uint64_t active_ = 0;
    std::uint64_t observed_logical_ = 0;
    std::atomic<bool> limit_reached_{false};
    std::atomic<bool> consumer_failed_{false};
    std::atomic<bool> worker_failed_{false};
    bool shutdown_ = false;
    bool root_failed_ = false;
};

}  // namespace

ScanResult scan_directory(const ScanConfiguration& configuration, CancellationToken& cancellation,
                          const ScanCallbacks& callbacks) {
    return Engine(configuration, cancellation, callbacks).run();
}

std::string relative_path(const ScanResult& result, NodeId id) {
    std::vector<const std::string*> parts;
    while (id > 1) {
        const auto& node = result.nodes.at(id - 1);
        parts.push_back(&node.name);
        id = node.parent_id;
    }
    std::string path;
    for (auto iterator = parts.rbegin(); iterator != parts.rend(); ++iterator) {
        if (!path.empty()) path += '/';
        path += **iterator;
    }
    return path;
}

const char* state_name(SessionState state) noexcept {
    switch (state) {
        case SessionState::Idle: return "idle";
        case SessionState::Queued: return "queued";
        case SessionState::Running: return "running";
        case SessionState::Cancelling: return "cancelling";
        case SessionState::Cancelled: return "cancelled";
        case SessionState::Completed: return "completed";
        case SessionState::Partial: return "partial";
        case SessionState::Failed: return "failed";
        case SessionState::Interrupted: return "interrupted";
    }
    return "unknown";
}
const char* node_kind_name(NodeKind kind) noexcept {
    switch (kind) {
        case NodeKind::Unknown: return "unknown";
        case NodeKind::Directory: return "directory";
        case NodeKind::Regular: return "regular";
        case NodeKind::Symlink: return "symlink";
        case NodeKind::Special: return "special";
    }
    return "unknown";
}
const char* skip_reason_name(SkipReason reason) noexcept {
    switch (reason) {
        case SkipReason::None: return "none";
        case SkipReason::MountBoundary: return "mount-boundary";
        case SkipReason::PseudoFilesystem: return "pseudo-filesystem";
        case SkipReason::DirectoryAlias: return "directory-alias";
        case SkipReason::ResourceLimit: return "resource-limit";
        case SkipReason::Cancelled: return "cancelled";
        case SkipReason::ChangedDuringScan: return "changed-during-scan";
        case SkipReason::UnknownMount: return "unknown-mount";
    }
    return "unknown";
}
const char* error_code_name(ScanErrorCode code) noexcept {
    switch (code) {
        case ScanErrorCode::InvalidConfiguration: return "invalid-configuration";
        case ScanErrorCode::PermissionDenied: return "permission-denied";
        case ScanErrorCode::NotFound: return "not-found";
        case ScanErrorCode::Io: return "io";
        case ScanErrorCode::Cancelled: return "cancelled";
        case ScanErrorCode::Unsupported: return "unsupported";
        case ScanErrorCode::ChangedDuringScan: return "changed-during-scan";
        case ScanErrorCode::ResourceLimit: return "resource-limit";
        case ScanErrorCode::ConsumerFailure: return "consumer-failure";
    }
    return "unknown";
}

}  // namespace disksize
