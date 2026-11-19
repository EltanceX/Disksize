#pragma once
#include "core/filesystem.h"
#include "core/scan_types.h"
#include <vector>

namespace disksize::internal {
struct ScopeError {
    const char* operation;
    int error;
};

// Pins the complete path binding, not just the target inode. No target is opened through a symlink.
class ScopedRoot {
public:
    ScopedRoot(const ScanConfiguration& configuration, FdMetrics& metrics, CancellationToken& token);
    [[nodiscard]] Fd duplicate() const;
    void verify() const;
    [[nodiscard]] const std::string& canonical() const { return canonical_; }
private:
    const ScanConfiguration& configuration_;
    FdMetrics& metrics_;
    std::string canonical_;
    std::vector<std::string> names_;
    std::vector<Fd> chain_;
    std::vector<struct stat> opened_;
};
}
