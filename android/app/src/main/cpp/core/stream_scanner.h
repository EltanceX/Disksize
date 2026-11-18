#pragma once
#include "core/scanner.h"

namespace disksize {
// O(max_depth + batch_size), independent of total nodes. Discovery records are
// provisional; directory closures update local completeness. Ownership and
// ancestor aggregation belong to the ordinary-UID persistent index.
struct StreamCallbacks : ScanCallbacks {
    std::function<void(const ScanNode&)> on_directory;
};
struct StreamResult {
    ScanResult scan; // nodes/issues deliberately remain empty
    std::uint64_t node_count = 0;
    // Captured from the actual root FD and its mount namespace, before walking.
    struct Volume {
        bool valid = false;
        std::uint64_t total = 0, free = 0, available = 0, captured_millis = 0;
    } volume;
};
enum class StreamMetadata { Combined, Separate, FdInfo };
// Optional mode-A diagnostics. Syscall timing adds per-entry clock reads and
// must be measured separately from throughput. FD checks on directories are
// outside these named-entry counters.
struct EntryMeasurements {
    std::uint64_t stat_calls = 0, statx_calls = 0, fd_fallbacks = 0;
    std::uint64_t stat_micros = 0, statx_micros = 0, fd_fallback_micros = 0;
};
[[nodiscard]] StreamResult stream_directory(const ScanConfiguration& configuration,
    CancellationToken& cancellation, const StreamCallbacks& callbacks,
    StreamMetadata metadata = StreamMetadata::Combined, EntryMeasurements* measurements = nullptr);
}
