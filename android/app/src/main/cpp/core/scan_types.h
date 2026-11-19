#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <optional>
#include <vector>

namespace disksize {

enum class AccessMode : std::uint8_t { App, AllFiles, Root };
enum class SessionState : std::uint8_t {
    Idle, Queued, Running, Cancelling, Cancelled, Completed, Partial, Failed, Interrupted
};
enum class ScanErrorCode : std::uint8_t {
    InvalidConfiguration, PermissionDenied, NotFound, Io, Cancelled, Unsupported,
    ChangedDuringScan, ResourceLimit, ConsumerFailure
};

struct ScopeIdentity {
    std::uint64_t device = 0, inode = 0, mount = 0;
};
struct SubtreeScope {
    std::string relative_path;
    std::vector<ScopeIdentity> chain; // Snapshot root through selected directory, inclusive.
    std::uint64_t filesystem = 0;
};

struct ScanConfiguration {
    std::string root_path;
    std::optional<SubtreeScope> subtree;
    AccessMode access_mode = AccessMode::App;
    bool follow_symbolic_links = false;
    bool cross_mount_points = false;
    std::uint32_t worker_count = 2;
    std::uint32_t queue_capacity = 128;
    std::uint32_t batch_size = 256;
    std::uint32_t max_depth = 512;
    std::uint64_t max_nodes = 1'000'000;
    std::uint32_t max_issues = 4096;
    std::uint32_t progress_interval_ms = 150;
};

struct ScanFailure {
    ScanErrorCode code;
    std::string path;
    int system_error = 0;
};

class CancellationToken {
public:
    void request() noexcept { requested_.store(true, std::memory_order_relaxed); }
    [[nodiscard]] bool requested() const noexcept {
        return requested_.load(std::memory_order_relaxed);
    }

private:
    std::atomic<bool> requested_{false};
};

}  // namespace disksize
