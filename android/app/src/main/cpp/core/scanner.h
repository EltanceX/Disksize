#pragma once

#include "core/scan_types.h"

#include <functional>
#include <span>
#include <vector>

namespace disksize {

using NodeId = std::uint64_t;
inline constexpr NodeId kNoNode = 0;

enum class NodeKind : std::uint8_t { Unknown, Directory, Regular, Symlink, Special };
enum class SkipReason : std::uint8_t {
    None, MountBoundary, PseudoFilesystem, DirectoryAlias, ResourceLimit,
    Cancelled, ChangedDuringScan, UnknownMount
};

struct ScanNode {
    NodeId id = kNoNode;
    NodeId parent_id = kNoNode;
    std::string name;  // Raw filename bytes; no UTF-8 conversion.
    std::string symlink_target;
    NodeKind kind = NodeKind::Unknown;
    std::uint32_t depth = 0;
    std::uint32_t mode = 0;
    std::uint32_t uid = 0;
    std::uint32_t gid = 0;
    std::uint64_t device = 0;
    std::uint64_t inode = 0;
    std::uint64_t mount_id = 0;
    std::uint64_t link_count = 0;
    std::uint64_t raw_size = 0;
    std::uint64_t raw_allocated_bytes = 0;
    std::int64_t mtime_seconds = 0;
    std::int64_t mtime_nanoseconds = 0;
    bool metadata_valid = false;
    bool directory_enumerated = false;
    bool complete = false;
    SkipReason skipped = SkipReason::None;
    std::uint64_t issue_count = 0;
    NodeId hardlink_owner_id = kNoNode;
    std::uint64_t counted_logical_bytes = 0;
    std::uint64_t counted_allocated_bytes = 0;
    std::uint64_t subtree_logical_bytes = 0;
    std::uint64_t subtree_allocated_bytes = 0;
};

struct ScanIssue {
    NodeId node_id = kNoNode;
    ScanErrorCode code = ScanErrorCode::Io;
    std::string operation;
    int system_error = 0;
};

struct ScanProgress {
    std::uint64_t discovered_nodes = 0;
    std::uint64_t observed_logical_bytes = 0;  // Provisional, not hardlink-deduplicated.
    std::uint64_t issue_count = 0;
    std::uint64_t elapsed_microseconds = 0;
};

struct ScanMetrics {
    std::uint64_t elapsed_microseconds = 0;
    std::uint64_t peak_queued_directories = 0;
    std::uint64_t peak_active_workers = 0;
    std::uint64_t peak_open_fds = 0;
    std::uint64_t open_fds_at_finish = 0;
};

struct ScanSummary {
    std::uint64_t regular_entries = 0;
    std::uint64_t unique_regular_files = 0;
    std::uint64_t hardlink_aliases = 0;
    std::uint64_t directories = 0;
    std::uint64_t symlinks = 0;
    std::uint64_t special_entries = 0;
    std::uint64_t unknown_entries = 0;
    std::uint64_t skipped_entries = 0;
    std::uint64_t logical_bytes = 0;
    std::uint64_t allocated_bytes = 0;
    std::uint64_t issue_count = 0;
    std::uint64_t dropped_issues = 0;
};

struct ScanResult {
    SessionState state = SessionState::Failed;
    std::string canonical_root;
    std::uint64_t root_mount_id = 0;
    std::uint64_t root_filesystem_type = 0;
    std::vector<ScanNode> nodes;
    std::vector<ScanIssue> issues;
    ScanSummary summary;
    ScanMetrics metrics;
};

struct ScanCallbacks {
    // Calls are serialized and synchronous: returning applies backpressure.
    // Discovery records do not contain final aggregates/ownership. A directory
    // cannot be scheduled until its discovery callback has returned.
    std::function<void(std::span<const ScanNode>)> on_nodes;
    std::function<void(const ScanProgress&)> on_progress;
    std::function<void(const ScanIssue&)> on_issue;
};

[[nodiscard]] ScanResult scan_directory(
    const ScanConfiguration& configuration,
    CancellationToken& cancellation,
    const ScanCallbacks& callbacks = {});

[[nodiscard]] std::string relative_path(const ScanResult& result, NodeId node_id);
[[nodiscard]] const char* state_name(SessionState state) noexcept;
[[nodiscard]] const char* node_kind_name(NodeKind kind) noexcept;
[[nodiscard]] const char* skip_reason_name(SkipReason reason) noexcept;
[[nodiscard]] const char* error_code_name(ScanErrorCode code) noexcept;

}  // namespace disksize
