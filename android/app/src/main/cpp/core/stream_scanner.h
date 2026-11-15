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
};
[[nodiscard]] StreamResult stream_directory(const ScanConfiguration& configuration,
    CancellationToken& cancellation, const StreamCallbacks& callbacks);
}
