#pragma once

#include <atomic>
#include <cstdint>
#include <dirent.h>
#include <string>
#include <sys/stat.h>

namespace disksize::internal {

struct FdMetrics {
    std::atomic<std::uint64_t> current{0};
    std::atomic<std::uint64_t> peak{0};
    void opened() noexcept;
    void closed() noexcept { current.fetch_sub(1); }
};

class Fd {
public:
    Fd() = default;
    Fd(int fd, FdMetrics& metrics) noexcept;
    ~Fd();
    Fd(Fd&& other) noexcept;
    Fd& operator=(Fd&& other) noexcept;
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    [[nodiscard]] int get() const noexcept { return fd_; }
    [[nodiscard]] explicit operator bool() const noexcept { return fd_ >= 0; }
    void reset() noexcept;
    // Ownership and the existing metrics credit transfer to DirStream.
    int release() noexcept;

private:
    int fd_ = -1;
    FdMetrics* metrics_ = nullptr;
};

class DirStream {
public:
    DirStream(Fd&& fd, FdMetrics& metrics) noexcept;
    ~DirStream();
    DirStream(const DirStream&) = delete;
    DirStream& operator=(const DirStream&) = delete;
    [[nodiscard]] DIR* get() const noexcept { return directory_; }

private:
    DIR* directory_ = nullptr;
    FdMetrics& metrics_;
};

[[nodiscard]] Fd open_canonical_directory(const std::string& path, FdMetrics& metrics);
[[nodiscard]] std::uint64_t mount_id_for_fd(int fd, FdMetrics& metrics);
[[nodiscard]] std::uint64_t mount_id_at(
    int parent, const char* name, const struct stat& expected, FdMetrics& metrics);
[[nodiscard]] bool same_identity(const struct stat& left, const struct stat& right) noexcept;
[[nodiscard]] bool pseudo_filesystem(std::uint64_t type) noexcept;
[[nodiscard]] bool excluded_system_root(const std::string& canonical_path) noexcept;

}  // namespace disksize::internal
